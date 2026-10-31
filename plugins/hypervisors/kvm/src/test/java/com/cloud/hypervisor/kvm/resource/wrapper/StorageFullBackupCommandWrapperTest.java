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

package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Collections;
import org.apache.cloudstack.backup.StorageBackupVolume;
import org.apache.cloudstack.backup.StartBackupAnswer;
import org.apache.cloudstack.backup.StartBackupCommand;
import org.apache.cloudstack.backup.StartNBDServerCommand;
import org.apache.cloudstack.backup.StopBackupCommand;
import org.apache.cloudstack.backup.StopNBDServerCommand;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.script.Script;

public class StorageFullBackupCommandWrapperTest {
    static final String POOL = "00000000-0000-0000-0000-000000000001";
    static final String VOL = "00000000-0000-0000-0000-000000000002";
    static final String BACKUP = "00000000-0000-0000-0000-000000000003";
    static final String TRANSFER = "00000000-0000-0000-0000-000000000004";
    StorageBackupVolume volume = new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL, 8589934592L);
    LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
    KVMStoragePool pool = mock(KVMStoragePool.class);

    @Before
    public void setUp() {
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class);
        when(resource.getStoragePoolMgr()).thenReturn(manager);
        when(manager.getStoragePool(StoragePoolType.RBD, POOL)).thenReturn(pool);
        when(pool.getType()).thenReturn(StoragePoolType.RBD);
        when(pool.getUuid()).thenReturn(POOL);
        when(pool.supportsFullBackupSnapshot()).thenReturn(true);
    }

    @Test
    public void fullBackupCreatesSnapshotWithoutFileCheckpointsForRunningOrStoppedVm() {
        for (boolean stopped : new boolean[]{false, true}) {
            StartBackupCommand cmd = new StartBackupCommand("vm", null, null, null, BACKUP,
                    Collections.emptyMap(), Collections.emptyMap(), stopped);
            cmd.setStorageBackupVolume(volume);
            Answer answer = new LibvirtStartBackupCommandWrapper().execute(cmd, resource);
            assertTrue(answer.getDetails(), answer.getResult());
            assertNull(((StartBackupAnswer) answer).getCheckpointCreateTime());
        }
        verify(pool, times(2)).createFullBackupSnapshot(VOL, BACKUP);
    }

    @Test
    public void providerCheckpointIdsAreUsedForRetainedStorageSnapshot() {
        String checkpoint = "00000000-0000-0000-0000-000000000005";
        StartBackupCommand cmd = new StartBackupCommand("vm", checkpoint, "00000000-0000-0000-0000-000000000006", null, checkpoint,
                Collections.emptyMap(), Collections.emptyMap(), false);
        cmd.setStorageBackupVolume(volume);
        assertTrue(new LibvirtStartBackupCommandWrapper().execute(cmd, resource).getResult());
        verify(pool).createFullBackupSnapshot(VOL, checkpoint);
    }

    @Test
    public void downloadResolvesOwnedCloneAndUsesExplicitReadOnlyRawFormat() throws Exception {
        StartNBDServerCommand cmd = nbd("download");
        when(cmd.getBackupUuid()).thenReturn(BACKUP);
        when(pool.getFullBackupSnapshotPath(VOL, BACKUP)).thenReturn("/dev/drbd/by-res/clone/0");
        LibvirtStartNBDServerCommandWrapper wrapper = new LibvirtStartNBDServerCommandWrapper();
        assertEquals("/dev/drbd/by-res/clone/0", wrapper.resolveStorageSnapshotPath(cmd, resource));
        String command = wrapper.createSystemdRunCmd(cmd, "unit", "/dev/drbd/by-res/clone/0", VOL, TRANSFER, true);
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--image-opts 'driver=raw,file.driver=host_device,"));
        assertTrue(command.contains("size=8589934592'"));
        assertFalse(command.contains("driver=qcow2"));
        verify(pool, never()).getPhysicalDisk(anyString());
    }

    @Test
    public void missingLogicalSizeCannotExposeAnUnboundedDevice() {
        StartNBDServerCommand cmd = nbd("upload");
        when(cmd.getStorageBackupVolume()).thenReturn(new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new LibvirtStartNBDServerCommandWrapper().resolveStorageSnapshotPath(cmd, resource));
        verifyNoInteractions(pool);
    }

    @Test
    public void uploadConnectsOnlyCanonicalRawTarget() {
        StartNBDServerCommand cmd = nbd("upload");
        when(pool.connectPhysicalDisk(VOL, Collections.emptyMap())).thenReturn(true);
        KVMPhysicalDisk disk = mock(KVMPhysicalDisk.class);
        when(pool.getPhysicalDisk(VOL)).thenReturn(disk);
        when(disk.getFormat()).thenReturn(QemuImg.PhysicalDiskFormat.RAW);
        when(disk.getPath()).thenReturn("/dev/drbd1000");
        assertEquals("/dev/drbd1000", new LibvirtStartNBDServerCommandWrapper().resolveStorageSnapshotPath(cmd, resource));
        try {
            String command = new LibvirtStartNBDServerCommandWrapper().createSystemdRunCmd(cmd, "unit", "/dev/drbd1000", VOL, TRANSFER, true);
            assertTrue(command.contains("size=8589934592'"));
            assertFalse(command.contains("--read-only"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        verify(pool, never()).getFullBackupSnapshotPath(anyString(), anyString());
    }

    @Test
    public void stopReleasesSnapshotWithoutLookingUpOrAbortingGuestDomain() {
        StopBackupCommand cmd = new StopBackupCommand("vm", 1L, 2L);
        cmd.setStorageSnapshot(volume, BACKUP);
        assertTrue(new LibvirtStopBackupCommandWrapper().execute(cmd, resource).getResult());
        verify(pool).deleteFullBackupSnapshot(VOL, BACKUP);
        verify(resource, never()).getLibvirtUtilitiesHelper();
    }

    @Test
    public void failedNbdStopCannotDisconnectWritableTarget() {
        StopNBDServerCommand cmd = mock(StopNBDServerCommand.class);
        when(cmd.getTransferId()).thenReturn(TRANSFER);
        when(cmd.getStorageBackupVolume()).thenReturn(volume);
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (script, context) -> {
            when(script.execute()).thenReturn(context.getCount() == 1 ? null : "stop failed");
        })) {
            assertFalse(new LibvirtStopNBDServerCommandWrapper().execute(cmd, resource).getResult());
            assertEquals(2, scripts.constructed().size());
            verifyNoInteractions(pool);
        }
    }

    @Test
    public void providerMustOptInBeforeSnapshotMutation() {
        when(pool.supportsFullBackupSnapshot()).thenReturn(false);
        StopBackupCommand cmd = new StopBackupCommand("vm", 1L, 2L);
        cmd.setStorageSnapshot(volume, BACKUP);
        assertFalse(new LibvirtStopBackupCommandWrapper().execute(cmd, resource).getResult());
        verify(pool, never()).deleteFullBackupSnapshot(anyString(), anyString());
    }

    @Test
    public void wrongPoolIdentityPreventsStorageMutation() {
        when(pool.getUuid()).thenReturn(BACKUP);
        StopBackupCommand cmd = new StopBackupCommand("vm", 1L, 2L);
        cmd.setStorageSnapshot(volume, BACKUP);
        assertFalse(new LibvirtStopBackupCommandWrapper().execute(cmd, resource).getResult());
        verify(pool, never()).deleteFullBackupSnapshot(anyString(), anyString());
    }

    private StartNBDServerCommand nbd(String direction) {
        StartNBDServerCommand cmd = mock(StartNBDServerCommand.class);
        when(cmd.getStorageBackupVolume()).thenReturn(volume);
        when(cmd.getTransferId()).thenReturn(TRANSFER);
        when(cmd.getSocket()).thenReturn(TRANSFER);
        when(cmd.getExportName()).thenReturn(VOL);
        when(cmd.getDirection()).thenReturn(direction);
        return cmd;
    }
}
