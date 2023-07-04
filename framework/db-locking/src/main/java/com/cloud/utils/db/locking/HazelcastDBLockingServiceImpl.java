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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.cloud.utils.component.AdapterBase;
import com.hazelcast.config.Config;
import com.hazelcast.config.cp.CPSubsystemConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;

public class HazelcastDBLockingServiceImpl extends AdapterBase implements DBLockingService {
    private static final Logger LOGGER = Logger.getLogger(DBLockingManagerImpl.class);
    private HashMap<String, FencedLock> locks;

    private HazelcastInstance hazelcastInstance1 = null;
    private HazelcastInstance hazelcastInstance2 = null;
    private HazelcastInstance hazelcastInstance3 = null;

    @Override
    public void init() throws IOException {
        locks = new HashMap<>();
        Config config = new Config();
        CPSubsystemConfig cpSubsystemConfig = config.getCPSubsystemConfig();
        cpSubsystemConfig.setCPMemberCount(3);
        hazelcastInstance1 = Hazelcast.newHazelcastInstance(config);
        hazelcastInstance2 = Hazelcast.newHazelcastInstance(config);
        hazelcastInstance3 = Hazelcast.newHazelcastInstance(config);
    }

    @Override
    public boolean lock(String name, int timeoutSeconds) {
        boolean locked = false;
        try {
            FencedLock lock = hazelcastInstance1.getCPSubsystem().getLock(name);
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
        hazelcastInstance1.shutdown();
        hazelcastInstance2.shutdown();
        hazelcastInstance3.shutdown();
    }

    @Override
    public String getName() {
        return "hazelcast";
    }
}
