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

package org.apache.cloudstack.framework.websocket.server;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

public class WebSocketServerRoutingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    protected static Logger LOGGER = LogManager.getLogger(WebSocketServerRoutingHandler.class);

    private final WebSocketServerHelper serverHelper;

    public WebSocketServerRoutingHandler(WebSocketServerHelper serverHelper) {
        this.serverHelper = serverHelper;
    }

    protected void closeChannelWithErrorResponse(ChannelHandlerContext ctx, FullHttpRequest req, String message) {
        LOGGER.warn("Error with request: {}, closing connection", message);
        FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Check for WebSocket upgrade headers
        String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
        String connection = req.headers().get(HttpHeaderNames.CONNECTION);
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket") ||
                connection == null || !connection.toLowerCase().contains("upgrade")) {
            closeChannelWithErrorResponse(ctx, req, "Only WebSocket upgrade requests are supported");
            return;
        }

        String uri = new QueryStringDecoder(req.uri()).path();
        if (StringUtils.isBlank(uri)) {
            closeChannelWithErrorResponse(ctx, req, "Empty URI");
            return;
        }
        String[] uriParts = uri.split("/");
        String route = uriParts.length > 1 ? "/" + uriParts[1] : uri;
        ChannelInboundHandlerAdapter handler = serverHelper.getRouteHandler(route);
        if (handler == null) {
            closeChannelWithErrorResponse(ctx, req, "Unknown URI: " + uri);
            return;
        }
        if (ctx.pipeline().get(handler.getClass()) == null) {
            LOGGER.debug("Adding handler [{}] for URI [{}]", handler.getClass().getSimpleName(), uri);
            ctx.pipeline().addLast(handler);
            ctx.pipeline().addLast(new WebSocketServerProtocolHandler(route, null, true));
            ctx.pipeline().addLast("idleStateHandler",
                    new IdleStateHandler(0, serverHelper.getRouteIdleTimeout(route), 0, TimeUnit.SECONDS));
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(req.retain());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("Exception in WebSocketServerRoutingHandler", cause);
        ctx.close();
    }
}
