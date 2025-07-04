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

import org.apache.cloudstack.framework.config.ConfigKey;

import io.netty.channel.ChannelInboundHandlerAdapter;

public interface WebSocketServerManager {
    int WS_PORT = 8822;
    int SERVER_SESSION_IDLE_TIMEOUT_SECONDS = 60;

    ConfigKey<Integer> WebSocketServerPort = new ConfigKey<>("Advanced", Integer.class,
            "websocket.server.port", String.valueOf(WS_PORT),
            "The port to be used for WebSocket Server",
            false,
            ConfigKey.Scope.Global);

    void startWebSocketServer();
    void stopWebSocketServer();
    boolean isServerRunning();

    int getServerPort();

    void registerRoute(String route, ChannelInboundHandlerAdapter handler, int idleTimeoutSeconds);

    void unregisterRoute(String route);
}
