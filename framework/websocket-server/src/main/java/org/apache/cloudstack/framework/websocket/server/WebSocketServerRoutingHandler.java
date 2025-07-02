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

import java.util.Map;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

public class WebSocketServerRoutingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    protected static Logger LOGGER = LogManager.getLogger(WebSocketServerRoutingHandler.class);

    private final WebSocketServerHelper serverHelper;

    public WebSocketServerRoutingHandler(WebSocketServerHelper serverHelper) {
        this.serverHelper = serverHelper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = new QueryStringDecoder(req.uri()).path();
        if (StringUtils.isBlank(uri)) {
            LOGGER.warn("Received request with empty URI, closing connection");
            ctx.close();
            return;
        }
        Map<String, ChannelInboundHandlerAdapter> routeHandlers = serverHelper.getRouteHandlers();
        if (MapUtils.isNotEmpty(routeHandlers)) {
            ChannelInboundHandlerAdapter handler = routeHandlers.get(uri);
            if (handler != null) {
                ctx.pipeline().addLast(handler);
                ctx.pipeline().remove(this);
            }
        }
        ctx.fireChannelRead(req.retain());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("Exception in WebSocketServerRoutingHandler", cause);
        ctx.close();
    }
}
