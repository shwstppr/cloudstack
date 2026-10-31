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
package org.apache.cloudstack.backup;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import org.apache.cloudstack.api.command.admin.backup.FinalizeBackupCmd;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.ImageTransferDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;

public class StorageKVMBackupExportServiceTest {
    static final String POOL = "00000000-0000-0000-0000-000000000001";
    static final String VOL = "00000000-0000-0000-0000-000000000002";
    static final String BACKUP = "00000000-0000-0000-0000-000000000003";
    KVMBackupExportServiceImpl service = spy(new KVMBackupExportServiceImpl());
    VolumeDao volumes = mock(VolumeDao.class);
    VolumeDetailsDao details = mock(VolumeDetailsDao.class);
    PrimaryDataStoreDao pools = mock(PrimaryDataStoreDao.class);
    BackupDetailsDao backupDetails = mock(BackupDetailsDao.class);
    BackupDao backups = mock(BackupDao.class);
    ImageTransferDao transfers = mock(ImageTransferDao.class);
    AgentManager agent = mock(AgentManager.class);
    VolumeVO volume = mock(VolumeVO.class);
    VMInstanceVO vm = mock(VMInstanceVO.class);
    BackupVO backup = mock(BackupVO.class);

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "volumeDao", volumes);
        ReflectionTestUtils.setField(service, "volumeDetailsDao", details);
        ReflectionTestUtils.setField(service, "primaryDataStoreDao", pools);
        ReflectionTestUtils.setField(service, "backupDetailsDao", backupDetails);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "imageTransferDao", transfers);
        ReflectionTestUtils.setField(service, "agentManager", agent);
        when(vm.getId()).thenReturn(1L);
        when(vm.getDataCenterId()).thenReturn(7L);
        when(volumes.findByInstance(1L)).thenReturn(List.of(volume));
        when(volume.getPoolId()).thenReturn(5L);
        when(volume.getId()).thenReturn(10L);
        when(volume.getUuid()).thenReturn(VOL);
        when(volume.getPath()).thenReturn(VOL);
        when(volume.getDataCenterId()).thenReturn(7L);
        when(volume.getInstanceId()).thenReturn(null);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pools.findById(5L)).thenReturn(pool);
        when(pool.getPoolType()).thenReturn(StoragePoolType.RBD);
        when(pool.getUuid()).thenReturn(POOL);
        Mockito.doReturn(new BackupStorageCapabilities(true, true, true,
                com.cloud.storage.Storage.ImageFormat.RAW, 1, 14400)).when(service).getStorageCapabilities(pool);
        when(backup.getId()).thenReturn(20L);
        when(backup.getUuid()).thenReturn(BACKUP);
        when(backup.getHostId()).thenReturn(2L);
        when(backup.getVmId()).thenReturn(1L);
        when(backup.getStatus()).thenReturn(Backup.Status.Queued);
    }

    @Test
    public void capabilitiesAreResolvedFromTheRegisteredStorageDriver() {
        KVMBackupExportServiceImpl implementation = new KVMBackupExportServiceImpl();
        DataStoreManager manager = mock(
                DataStoreManager.class);
        DataStore store = mock(
                DataStore.class);
        PrimaryDataStoreDriver driver = mock(
                PrimaryDataStoreDriver.class, Mockito.CALLS_REAL_METHODS);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(5L);
        ReflectionTestUtils.setField(implementation, "dataStoreManager", manager);
        assertSame(BackupStorageCapabilities.UNSUPPORTED, implementation.getStorageCapabilities(pool));
        when(manager.getPrimaryDataStore(5L)).thenReturn(store);
        when(store.getDriver()).thenReturn(driver);
        assertSame(BackupStorageCapabilities.UNSUPPORTED, implementation.getStorageCapabilities(pool));
        BackupStorageCapabilities enabled = new BackupStorageCapabilities(true, true, true,
                com.cloud.storage.Storage.ImageFormat.RAW, 1, 14400);
        when(driver.getBackupStorageCapabilities(pool)).thenReturn(enabled);
        assertSame(enabled, implementation.getStorageCapabilities(pool));
        assertSame(BackupStorageCapabilities.UNSUPPORTED, implementation.getStorageCapabilities(null));
    }

    @Test
    public void disabledProviderNeverReachesAgentOrLegacyCapture() {
        StoragePoolVO pool = pools.findById(5L);
        Mockito.doReturn(new BackupStorageCapabilities(true, true, false,
                com.cloud.storage.Storage.ImageFormat.RAW, 1, 14400)).when(service).getStorageCapabilities(pool);
        assertThrows(CloudRuntimeException.class, () -> service.storageSnapshotSource(vm));
        verifyNoInteractions(agent);
    }

    @Test
    public void storageHandleValidationBelongsToProviderRatherThanWireDto() {
        StorageBackupVolume handle = new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, "provider-owned-handle");
        StorageBackupVolume copy = new Gson().fromJson(new Gson().toJson(handle), StorageBackupVolume.class);
        copy.validate();
        assertEquals(StoragePoolType.RBD, copy.getPoolType());
        assertEquals("provider-owned-handle", copy.getVolumePath());
    }

    @Test
    public void sourceRejectsMixedStorageAndMultipleDisks() {
        when(volumes.findByInstance(1L)).thenReturn(List.of(volume, mock(VolumeVO.class)));
        assertThrows(CloudRuntimeException.class, () -> service.storageSnapshotSource(vm));
        verifyNoInteractions(agent);
    }

    @Test
    public void sourceUsesCanonicalPoolIdentityAndRejectsEncryption() {
        StorageBackupVolume identity = service.storageSnapshotSource(vm);
        assertEquals(POOL, identity.getPoolUuid());
        assertEquals(VOL, identity.getVolumePath());
        when(volume.getEncryptFormat()).thenReturn("LUKS");
        assertThrows(CloudRuntimeException.class, () -> service.storageSnapshotSource(vm));
    }

    @Test
    public void uploadRequiresFreshUnattachedRawTargetAndMatchingOwnershipMarker() {
        when(volume.getState()).thenReturn(Volume.State.Ready);
        assertThrows(CloudRuntimeException.class, () -> service.validateStorageSnapshotRestoreTarget(volume, ImageTransfer.Format.raw));
        when(details.findDetail(10L, KVMBackupExportService.RestoreTargetDetail))
                .thenReturn(new VolumeDetailVO(10L, KVMBackupExportService.RestoreTargetDetail, VOL, false));
        service.validateStorageSnapshotRestoreTarget(volume, ImageTransfer.Format.raw);
        assertThrows(CloudRuntimeException.class, () -> service.validateStorageSnapshotRestoreTarget(volume, ImageTransfer.Format.cow));
        when(volume.getInstanceId()).thenReturn(99L);
        assertThrows(CloudRuntimeException.class, () -> service.validateStorageSnapshotRestoreTarget(volume, ImageTransfer.Format.raw));
        when(volume.getInstanceId()).thenReturn(null);
        when(volume.getAttached()).thenReturn(new Date());
        assertThrows(CloudRuntimeException.class, () -> service.validateStorageSnapshotRestoreTarget(volume, ImageTransfer.Format.raw));
        verifyNoInteractions(agent);
    }

    @Test
    public void startPersistsNoQcowsOrCheckpointArguments() throws Exception {
        when(agent.send(eq(2L), any(Command.class))).thenReturn(new Answer(null, true, null));
        doNothing().when(service).queueBackupFinalizeWaitWorkJob(vm, backup);
        service.startStorageSnapshotBackup(vm, backup, new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL));
        ArgumentCaptor<Command> command = ArgumentCaptor.forClass(Command.class);
        verify(agent).send(eq(2L), command.capture());
        StartBackupCommand sent = (StartBackupCommand) command.getValue();
        assertNull(sent.getFromCheckpointId());
        assertNull(sent.getToCheckpointId());
        assertTrue(sent.getDiskPathUuidMap().isEmpty());
        assertEquals(VOL, sent.getStorageBackupVolume().getVolumeUuid());
        assertEquals(BACKUP, sent.getSocket());
    }

    @Test
    public void repeatedStartDoesNotRecaptureAnExportedSnapshot() {
        when(backup.getStatus()).thenReturn(Backup.Status.ReadyForImageTransfer);
        assertSame(backup, service.startStorageSnapshotBackup(vm, backup, new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL)));
        verifyNoInteractions(agent);
    }

    @Test
    public void failedStartAttemptsOwnedCleanupAndRetainsMetadata() throws Exception {
        when(agent.send(eq(2L), any(Command.class))).thenReturn(new Answer(null, false, "failed"), new Answer(null, true, null));
        assertThrows(CloudRuntimeException.class,
                () -> service.startStorageSnapshotBackup(vm, backup, new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL)));
        ArgumentCaptor<Command> command = ArgumentCaptor.forClass(Command.class);
        verify(agent, times(2)).send(eq(2L), command.capture());
        StopBackupCommand cleanup = (StopBackupCommand) command.getAllValues().get(1);
        assertEquals(BACKUP, cleanup.getBackupUuid());
        assertEquals(VOL, cleanup.getStorageBackupVolume().getVolumePath());
        verify(backups, never()).remove(anyLong());
    }

    @Test
    public void durableMetadataRoundTripContainsOnlyCanonicalIdentities() {
        String json = new Gson().toJson(new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL));
        when(backupDetails.findDetail(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME))
                .thenReturn(new BackupDetailVO(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME, json, false));
        assertEquals(POOL, service.storageSnapshotBackupVolume(backup).getPoolUuid());
        assertFalse(json.contains("/dev/"));
        assertFalse(json.contains("passphrase"));
    }

    @Test
    public void failedUnregisterNeverStopsDeviceOrRemovesOwnership() throws Exception {
        ImageTransferVO transfer = mock(ImageTransferVO.class);
        when(transfer.getHostId()).thenReturn(2L);
        when(transfer.getUuid()).thenReturn(BACKUP);
        when(agent.send(eq(2L), any(Command.class))).thenReturn(new Answer(null, false, "busy"));
        assertThrows(CloudRuntimeException.class, () -> service.closeStorageSnapshotImageTransfer(transfer));
        verify(agent, times(1)).send(eq(2L), any(Command.class));
        verifyNoInteractions(details, transfers);
    }

    @Test
    public void removedTransferRetryDoesNotTouchAnAlreadyReusedDevice() {
        ImageTransferVO transfer = mock(ImageTransferVO.class);
        when(transfer.getId()).thenReturn(30L);
        when(transfer.getPhase()).thenReturn(ImageTransfer.Phase.finished);
        when(transfers.findByIdIncludingRemoved(30L)).thenReturn(transfer);
        assertTrue(service.finishStorageSnapshotImageTransfer(transfer, false));
        verifyNoInteractions(agent);
    }

    @Test
    public void failedBackupCannotBeFinalizedAsSuccessful() {
        when(backup.getStatus()).thenReturn(Backup.Status.Error);
        when(backups.findById(20L)).thenReturn(backup);
        when(backupDetails.findDetail(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME))
                .thenReturn(new BackupDetailVO(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME,
                        new Gson().toJson(new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL)), false));
        VMInstanceDao instances = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(service, "vmInstanceDao", instances);
        when(instances.findById(1L)).thenReturn(vm);
        when(transfers.listByBackupId(20L)).thenReturn(Collections.emptyList());
        doNothing().when(service).releaseStorageSnapshot(any(), any());
        FinalizeBackupCmd command = mock(FinalizeBackupCmd.class);
        when(command.getVmId()).thenReturn(1L);
        when(command.getBackupId()).thenReturn(20L);
        service.finalizeBackupInternal(command);
        verify(backup, never()).setStatus(Backup.Status.BackedUp);
        verify(backups, never()).remove(anyLong());
    }

    @Test
    public void recoveryRetainsOwnershipWhenAgentCannotReleaseSnapshot() throws Exception {
        when(backups.listAll()).thenReturn(List.of(backup));
        when(backups.findById(20L)).thenReturn(backup);
        when(backup.getStatus()).thenReturn(Backup.Status.Error);
        when(backupDetails.findDetail(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME))
                .thenReturn(new BackupDetailVO(20L, KVMBackupExportServiceImpl.STORAGE_FULL_VOLUME,
                        new Gson().toJson(new StorageBackupVolume(StoragePoolType.RBD, POOL, VOL, VOL)), false));
        when(transfers.listByBackupId(20L)).thenReturn(Collections.emptyList());
        when(transfers.listAll()).thenReturn(Collections.emptyList());
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get()).when(service).withStorageSnapshotLock(anyString(), any());
        when(agent.send(eq(2L), any(Command.class))).thenReturn(new Answer(null, false, "unavailable"));
        service.reconcileStorageSnapshotSessions();
        verify(agent).send(eq(2L), any(StopBackupCommand.class));
        verify(backups, never()).remove(anyLong());
    }

    @Test
    public void reaperIgnoresBackupsWithoutOwnedMetadata() {
        when(backups.listAll()).thenReturn(List.of(backup));
        when(transfers.listAll()).thenReturn(Collections.emptyList());
        service.reconcileStorageSnapshotSessions();
        verifyNoInteractions(agent);
        verify(backups, never()).remove(anyLong());
    }
}
