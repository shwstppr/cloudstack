// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.logsws.server;

import java.nio.charset.StandardCharsets;

import org.apache.cloudstack.logsws.LogsWebSession;
import org.apache.cloudstack.logsws.LogsWebSessionTokenPayload;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;

@ChannelHandler.Sharable
public class LogsWebSocketRoutingHandler extends ChannelInboundHandlerAdapter {
    protected static Logger LOGGER = LogManager.getLogger(LogsWebSocketRoutingHandler.class);
    public static final AttributeKey<String> LOGGER_ROUTE_ATTR = AttributeKey.valueOf("loggerRoute");
    private final LogsWebSocketRouteManager routeManager;
    private final LogsWebSocketServerHelper serverHelper;

    public LogsWebSocketRoutingHandler(LogsWebSocketRouteManager routeManager,
                                       LogsWebSocketServerHelper serverHelper) {
        this.routeManager = routeManager;
        this.serverHelper = serverHelper;
    }

    protected void closeChannelWithErrorResponse(ChannelHandlerContext ctx, FullHttpRequest req, String message) {
        LOGGER.warn("Error with request: {}, closing connection", message);
        FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    protected LogsWebSession getValidSession(String route, ChannelHandlerContext ctx) {
        LogsWebSessionTokenPayload tokenPayload = serverHelper.parseToken(route);
        if (tokenPayload == null) {
            LOGGER.error("Decrypted token payload is null for route: {}", route);
            return null;
        }
        String sessionUuid = tokenPayload.getSessionUuid();
        if (StringUtils.isBlank(sessionUuid)) {
            LOGGER.error("Session UUID is blank in token payload for route: {}", route);
            return null;
        }
        String creatorAddress = tokenPayload.getCreatorAddress();
        if (StringUtils.isBlank(creatorAddress)) {
            LOGGER.error("Creator address is blank in token payload for route: {}", route);
            return null;
        }
        String requestAddress = ctx.channel().remoteAddress().toString();
        if (!requestAddress.contains(creatorAddress)) {
            LOGGER.error("Request address: {} does not match creator address: {} for session: {}",
                    requestAddress, creatorAddress, sessionUuid);
            return null;
        }
        return serverHelper.getSession(sessionUuid);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest req = (FullHttpRequest) msg;
        String uri = req.uri();
        LOGGER.debug("Original URI: {}", uri);
        final String serverPath = serverHelper.getServerPath();
        final String expectedPathPrefix = serverPath + "/";
        if (!uri.startsWith(expectedPathPrefix)) {
            closeChannelWithErrorResponse(ctx, req,
                    String.format("Invalid request path in URI: %s. Expected path: %s", uri, expectedPathPrefix));
            return;
        }
        // Extract the route portion.
        String route = uri.substring(expectedPathPrefix.length());
        if (route.isEmpty()) {
            closeChannelWithErrorResponse(ctx, req, String.format("Empty route in request URI: %s", uri));
            return;
        }
        LogsWebSession session = getValidSession(route, ctx);
        if (session == null) {
            closeChannelWithErrorResponse(ctx, req,
                    String.format("Unauthorized connection attempt for route: %s", route));
            return;
        }
        // Retrieve or add the route.
        ChannelGroup group = routeManager.getRouteGroup(route);
        if (group == null) {
            routeManager.addRoute(route);
            group = routeManager.getRouteGroup(route);
        } else {
            // If there's already a connection, close it to allow only one connection per route.
            if (!group.isEmpty()) {
                LOGGER.debug("Closing existing connection(s) for route: {}", route);
                group.close(); // This will close all existing channels in the group.
            }
        }

        LOGGER.debug("Connecting to route: {} for context: {}", route, ctx.hashCode());
        ctx.channel().attr(LOGGER_ROUTE_ATTR).set(route);
        group.add(ctx.channel());

        // Rewrite the URI so that the handshake matches the expected sever path
        if (req instanceof DefaultFullHttpRequest) {
            req.setUri(serverPath);
        } else {
            DefaultFullHttpRequest newReq = new DefaultFullHttpRequest(
                    req.protocolVersion(), req.method(), serverPath, req.content().retain());
            newReq.headers().setAll(req.headers());
            req.release();
            req = newReq;
        }
        LOGGER.debug("Rewritten URI: {}", req.uri());
        ctx.pipeline().addLast(new LogsWebSocketBroadcastHandler(session, serverHelper));
        ctx.fireChannelRead(req);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String route = ctx.channel().attr(LogsWebSocketRoutingHandler.LOGGER_ROUTE_ATTR).get();
        if (route != null) {
            LOGGER.debug("Channel is being closed for route: {}, context: {}", route, ctx.hashCode());
            routeManager.removeRoute(route);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("Exception in LoggerWebSocketRoutingHandler", cause);
        ctx.close();
    }
}
