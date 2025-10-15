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

import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.framework.websocket.server.common.WebSocketRouter;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Netty WebSocket server that delegates routing to WebSocketRouter.
 * Replaces the previous helper-based WebSocketServer.
 */
public final class WebSocketServer {
    private static final Logger LOG = LogManager.getLogger(WebSocketServer.class);

    private final String host;
    private final int port;
    private final WebSocketRouter router;
    private final String websocketBasePath;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    public WebSocketServer(int port, WebSocketRouter router) {
        this(null, port, router, null);
    }

    public WebSocketServer(String host, int port, WebSocketRouter router, String websocketBasePath) {
        this.host = StringUtils.isBlank(host) ? "0.0.0.0" : host;
        this.port = port;
        this.router = router;
        this.websocketBasePath = StringUtils.isBlank(websocketBasePath) ?
                WebSocketRouter.WEBSOCKET_PATH_PREFIX : websocketBasePath;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        final WebSocketServerProtocolConfig wsCfg = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(websocketBasePath)
                .checkStartsWith(true)
                .allowExtensions(false).handshakeTimeoutMillis(10_000).build();

        ServerBootstrap b = new ServerBootstrap().group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast(new HttpServerCodec());
                p.addLast(new HttpObjectAggregator(65536));
                p.addLast(new WebSocketServerProtocolHandler(wsCfg));
                p.addLast(new WebSocketServerRoutingHandler(router));
            }
        });

        serverChannel = b.bind(host, port).sync().channel();
        running = true;
        LOG.info("WebSocketServer listening on {}:{} (base path: {}, router={})", host, port,
                websocketBasePath, router);
    }

    public void stop(long maxWaitSeconds) {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(0, maxWaitSeconds, TimeUnit.SECONDS).sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(0, maxWaitSeconds, TimeUnit.SECONDS).sync();
            }
        } catch (InterruptedException e) {
            LOG.warn("Graceful stop interrupted; forcing shutdown", e);
            if (bossGroup != null) bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            if (workerGroup != null) workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        } finally {
            running = false;
            LOG.info("WebSocketServer stopped");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
