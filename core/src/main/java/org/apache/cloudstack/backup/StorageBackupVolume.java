//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.

package org.apache.cloudstack.backup;

import java.util.UUID;
import com.cloud.storage.Storage.StoragePoolType;

/** Canonical storage identities only; never persist a device path or a credential. */
public class StorageBackupVolume {
    private StoragePoolType poolType;
    private String poolUuid;
    private String volumeUuid;
    private String volumePath;

    public StorageBackupVolume(StoragePoolType poolType, String poolUuid, String volumeUuid, String volumePath) {
        this.poolType = poolType;
        this.poolUuid = poolUuid;
        this.volumeUuid = volumeUuid;
        this.volumePath = volumePath;
        validate();
    }

    public void validate() {
        if (poolType == null || volumePath == null || volumePath.isBlank()) {
            throw new IllegalArgumentException("Storage backup requires a pool type and provider volume identifier");
        }
        for (String value : new String[]{poolUuid, volumeUuid}) {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("Storage snapshot backup requires canonical pool and volume UUIDs");
            }
        }
    }

    public StoragePoolType getPoolType() { return poolType; }
    public String getPoolUuid() { return poolUuid; }
    public String getVolumeUuid() { return volumeUuid; }
    public String getVolumePath() { return volumePath; }
}
