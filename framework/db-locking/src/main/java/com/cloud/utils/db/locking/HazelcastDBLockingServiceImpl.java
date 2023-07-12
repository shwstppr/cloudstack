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

package com.cloud.utils.db.locking;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ComponentLifecycle;
import com.cloud.utils.server.ServerProperties;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;

public class HazelcastDBLockingServiceImpl extends AdapterBase implements DBLockingService {
    private static final Logger LOGGER = Logger.getLogger(HazelcastDBLockingServiceImpl.class);
    private static final String LOCKING_SERVICE_CLIENTS_KEY = "locking.service.clients";

    private String lockingServiceClients = null;
    private HashMap<String, FencedLock> locks;

    private Config config;
    private HazelcastInstance hazelcastInstance = null;

    protected HazelcastDBLockingServiceImpl () {
        setRunLevel(ComponentLifecycle.RUN_LEVEL_SYSTEM);
    }

    private void prepareConfig() {
        final File confFile = PropertiesUtil.findConfigFile("server.properties");
        if (confFile == null) {
            LOGGER.warn("Server configuration file not found");
            return;
        }
        LOGGER.info("Server configuration file found: " + confFile.getAbsolutePath());
        try {
            InputStream is = new FileInputStream(confFile);
            final Properties properties = ServerProperties.getServerProperties(is);
            if (properties == null) {
                return;
            }
            lockingServiceClients = properties.getProperty(LOCKING_SERVICE_CLIENTS_KEY);
        } catch (final IOException e) {
            LOGGER.warn("Failed to read configuration from server.properties file", e);
        }
        if (StringUtils.isEmpty(lockingServiceClients)) {
            lockingServiceClients = "localhost";
        }
        config = new Config();
        NetworkConfig network = config.getNetworkConfig();
        network.setPort(5701).setPortCount(20);
        network.setPortAutoIncrement(true);
        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig()
                .addMember(lockingServiceClients).setEnabled(true);
    }

    @Override
    public void init() throws IOException {
        locks = new HashMap<>();
        prepareConfig();
        hazelcastInstance = Hazelcast.newHazelcastInstance(config);
    }

    @Override
    public boolean lock(String name, int timeoutSeconds) {
        boolean locked = false;
        try {
            FencedLock lock = hazelcastInstance.getCPSubsystem().getLock(name);
            locked = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            if (locked) {
                locks.put(name, lock);
            }
        } catch (Exception e) {
            LOGGER.debug(String.format("Unable to acquire Hazelcast lock, %s", name), e);
        }
        LOGGER.debug(String.format("Acquired lock %s: %s", name, locked));
        return locked;
    }

    @Override
    public boolean release(String name) {
        if (locks.containsKey(name)) {
            LOGGER.debug(String.format("No lock found %s", name));
            return false;
        }
        boolean released = false;
        FencedLock lock = locks.get(name);
        if (lock != null) {
            try {
                lock.unlock();
                locks.remove(name);
                released = true;
            } catch (Exception e) {
                LOGGER.debug(String.format("Unable to release lock, %s", name), e);
            }
        }
        LOGGER.debug(String.format("Released lock %s: %s", name, released));
        return released;
    }

    @Override
    public void stopService() {
        for (Map.Entry<String, FencedLock> entry : locks.entrySet()) {
            try {
                entry.getValue().unlock();
            } catch (Exception e) {
                LOGGER.debug(String.format("Unable to release lock, %s", entry.getKey()), e);
            }
        }
        hazelcastInstance.shutdown();
    }

    @Override
    public String getName() {
        return "hazelcast";
    }
}
