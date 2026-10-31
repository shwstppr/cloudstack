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

import com.cloud.agent.api.Command;

public class StopBackupCommand extends Command {
    private String vmName;
    private Long vmId;
    private Long backupId;
    private StorageBackupVolume storageBackupVolume;
    private String backupUuid;

    public StopBackupCommand() {
    }

    public StopBackupCommand(String vmName, Long vmId, Long backupId) {
        this.vmName = vmName;
        this.vmId = vmId;
        this.backupId = backupId;
    }

    public String getVmName() {
        return vmName;
    }

    public StorageBackupVolume getStorageBackupVolume() { return storageBackupVolume; }
    public String getBackupUuid() { return backupUuid; }
    public void setStorageSnapshot(StorageBackupVolume volume, String uuid) {
        storageBackupVolume = volume;
        backupUuid = uuid;
    }

    public Long getVmId() {
        return vmId;
    }

    public Long getBackupId() {
        return backupId;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
