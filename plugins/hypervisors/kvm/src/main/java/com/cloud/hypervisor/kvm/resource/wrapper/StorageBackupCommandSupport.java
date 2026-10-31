// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import org.apache.cloudstack.backup.StorageBackupVolume;
import java.util.UUID;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;

final class StorageBackupCommandSupport {
    private StorageBackupCommandSupport() { }

    static String unitName(String transferId) {
        if (!UUID.fromString(transferId).toString().equals(transferId)) {
            throw new IllegalArgumentException("Invalid Storage snapshot transfer UUID");
        }
        return "qemu-nbd-storage-backup-" + transferId;
    }

    static KVMStoragePool pool(StorageBackupVolume volume, LibvirtComputingResource resource) {
        volume.validate();
        KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(volume.getPoolType(), volume.getPoolUuid());
        if (pool == null || pool.getType() != volume.getPoolType() || !pool.supportsFullBackupSnapshot() || !volume.getPoolUuid().equals(pool.getUuid())) {
            throw new IllegalArgumentException("Storage snapshot backup storage identity does not match the registered pool");
        }
        pool.validateBackupVolume(volume.getVolumePath());
        return pool;
    }
}
