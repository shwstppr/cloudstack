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

package org.apache.cloudstack.framework.websocket.server.manager;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.websocket.server.WebSocketServer;
import org.apache.cloudstack.framework.websocket.server.WebSocketServerHelper;

import com.cloud.utils.component.ManagerBase;

import io.netty.channel.ChannelInboundHandlerAdapter;

public class WebSocketServerManagerImpl extends ManagerBase implements WebSocketServerManager, WebSocketServerHelper, Configurable {

    private int serverPort;
    private WebSocketServer webSocketServer;
    private Map<String, ChannelInboundHandlerAdapter> routeHandlers;
    private Map<String, Integer> routeIdleTimeouts;

    @Override
    public void startWebSocketServer() {
        if (isServerRunning()) {
            logger.info("WebSocket Server is already running!");
            return;
        }
        webSocketServer = new WebSocketServer(serverPort, this);
        try {
            webSocketServer.start();
        } catch (InterruptedException e) {
            logger.error("Failed to start WebSocket Server", e);
        }
    }

    protected void stopWebSocketServer(Integer maxWaitSeconds) {
        if (webSocketServer == null || !webSocketServer.isRunning()) {
            logger.info("WebSocket Server is already stopped!");
            return;
        }
        webSocketServer.stop(maxWaitSeconds == null ? 5 : maxWaitSeconds);
        webSocketServer = null;
    }

    @Override
    public void stopWebSocketServer() {
        stopWebSocketServer(null);
    }

    @Override
    public boolean isServerRunning() {
        return webSocketServer != null && webSocketServer.isRunning();
    }

    @Override
    public int getServerPort() {
        return serverPort;
    }

    @Override
    public void registerRoute(String route, ChannelInboundHandlerAdapter handler, int idleTimeoutSeconds) {
        routeHandlers.put(route, handler);
        routeIdleTimeouts.put(route, idleTimeoutSeconds);
    }

    @Override
    public void unregisterRoute(String route) {
        routeHandlers.remove(route);
    }

    @Override
    public ChannelInboundHandlerAdapter getRouteHandler(String route) {
        return routeHandlers.get(route);
    }

    @Override
    public int getRouteIdleTimeout(String route) {
        return routeIdleTimeouts.getOrDefault(route, SERVER_SESSION_IDLE_TIMEOUT_SECONDS);
    }

    @Override
    public boolean start() {
        super.start();
        serverPort = WebSocketServerPort.value();
        routeHandlers = new HashMap<>();
        routeIdleTimeouts = new HashMap<>();
        startWebSocketServer();
        return true;
    }

    @Override
    public boolean stop() {
        stopWebSocketServer(1);
        return true;
    }

    @Override
    public String getConfigComponentName() {
        return WebSocketServerManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[] {
                WebSocketServerPort
        };
    }
}
