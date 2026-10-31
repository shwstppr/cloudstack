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

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import com.cloud.storage.StoragePool;
import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;
import static org.apache.cloudstack.backup.BackupManager.BackupProviderPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.cloud.utils.db.GlobalLock;

import javax.inject.Inject;

import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import org.apache.cloudstack.api.command.admin.backup.CreateImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.DeleteVmCheckpointCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeBackupCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.ListImageTransfersCmd;
import org.apache.cloudstack.api.command.admin.backup.ListVmCheckpointsCmd;
import org.apache.cloudstack.api.command.admin.backup.StartBackupCmd;
import org.apache.cloudstack.api.response.CheckpointResponse;
import org.apache.cloudstack.api.response.ImageTransferResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.ImageTransferDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.ReflectionUse;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.VmWork;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkJobHandler;
import com.cloud.vm.VmWorkJobHandlerProxy;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class KVMBackupExportServiceImpl extends ManagerBase implements KVMBackupExportService, VmWorkJobHandler {
    public static final String VM_WORK_JOB_HANDLER = KVMBackupExportServiceImpl.class.getSimpleName();
    private static final long BACKUP_FINALIZE_WAIT_CHECK_INTERVAL = 15 * 1000L;
    private static Set<String> COMPATIBLE_BACKUP_PROVIDERS = Set.of("veeam", "dummy");

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Inject
    private BackupDao backupDao;

    @Inject
    private ImageTransferDao imageTransferDao;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private VolumeDetailsDao volumeDetailsDao;

    @Inject
    private AgentManager agentManager;

    @Inject
    private HostDao hostDao;

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    private StoragePoolHostDao storagePoolHostDao;

    @Inject
    private DataCenterDao dataCenterDao;

    @Inject
    AccountService accountService;

    @Inject
    AsyncJobManager asyncJobManager;

    @Inject
    VirtualMachineManager virtualMachineManager;

    @Inject
    private VolumeDataFactory volumeDataFactory;

    VmWorkJobHandlerProxy jobHandlerProxy = new VmWorkJobHandlerProxy(this);
    static final String STORAGE_FULL_VOLUME = "storage.full.volume";
    @Inject
    BackupDetailsDao backupDetailsDao;
    private ScheduledExecutorService storageSnapshotCleanupExecutor;

    protected <T> T withStorageSnapshotLock(String identity, Supplier<T> action) {
        GlobalLock lock = GlobalLock.getInternLock("storage-backup-" + identity);
        boolean acquired = false;
        try {
            acquired = lock.lock(30);
            if (!acquired) {
                throw new CloudRuntimeException("Storage snapshot session is busy; retry the operation");
            }
            return action.get();
        } finally {
            if (acquired) {
                lock.unlock();
            }
            lock.releaseRef();
        }
    }

    private String storageSnapshotTransferLock(ImageTransferVO transfer) {
        if (transfer.getBackupId() != null) {
            return "vm-" + backupDao.findByIdIncludingRemoved(transfer.getBackupId()).getVmId();
        }
        return "volume-" + transfer.getVolumeId();
    }

    @Inject
    private DataStoreManager dataStoreManager;

    @Override
    public BackupStorageCapabilities getStorageCapabilities(StoragePool pool) {
        if (pool == null) {
            return BackupStorageCapabilities.UNSUPPORTED;
        }
        DataStore store = dataStoreManager.getPrimaryDataStore(pool.getId());
        if (store == null || !(store.getDriver() instanceof PrimaryDataStoreDriver)) {
            return BackupStorageCapabilities.UNSUPPORTED;
        }
        BackupStorageCapabilities capabilities = ((PrimaryDataStoreDriver) store.getDriver())
                .getBackupStorageCapabilities(pool);
        return capabilities == null ? BackupStorageCapabilities.UNSUPPORTED : capabilities;
    }

    protected boolean isStorageSnapshot(Volume volume) {
        return volume != null && volume.getPoolId() != null &&
                getStorageCapabilities(primaryDataStoreDao.findById(volume.getPoolId())).supportsNativeSnapshots();
    }

    protected StorageBackupVolume storageSnapshotIdentity(Volume volume) {
        StoragePoolVO pool = primaryDataStoreDao.findById(volume.getPoolId());
        if (!getStorageCapabilities(pool).supportsNativeSnapshots()) {
            throw new CloudRuntimeException("Storage provider does not support native snapshot export");
        }
        if (StringUtils.isNotBlank(volume.getEncryptFormat())) {
            throw new CloudRuntimeException("Encrypted raw snapshot export is not supported by this transport");
        }
        return new StorageBackupVolume(pool.getPoolType(), pool.getUuid(), volume.getUuid(), volume.getPath());
    }

    protected void requireStorageSnapshotEnabled(StoragePool pool) {
        if (!getStorageCapabilities(pool).isFullBackupEnabled()) {
            throw new CloudRuntimeException("Native full backup is disabled by the storage provider");
        }
    }

    protected StorageBackupVolume storageSnapshotSource(VMInstanceVO vm) {
        List<VolumeVO> volumes = volumeDao.findByInstance(vm.getId());
        if (volumes.stream().noneMatch(this::isStorageSnapshot)) {
            return null;
        }
        StoragePoolVO pool = primaryDataStoreDao.findById(volumes.get(0).getPoolId());
        requireStorageSnapshotEnabled(pool);
        if (volumes.size() != 1 || volumes.size() > getStorageCapabilities(pool).getMaximumDisks()) {
            throw new CloudRuntimeException("Native full backup exceeds the provider/transport disk limit; mixed storage is unsupported");
        }
        return storageSnapshotIdentity(volumes.get(0));
    }

    protected StorageBackupVolume storageSnapshotBackupVolume(BackupVO backup) {
        BackupDetailVO detail = backupDetailsDao.findDetail(backup.getId(), STORAGE_FULL_VOLUME);
        if (detail == null) {
            return null;
        }
        StorageBackupVolume volume = new Gson().fromJson(detail.getValue(), StorageBackupVolume.class);
        volume.validate();
        return volume;
    }

    protected void releaseStorageSnapshot(BackupVO backup, StorageBackupVolume volume) {
        StopBackupCommand cmd = new StopBackupCommand(null, backup.getVmId(), backup.getId());
        cmd.setStorageSnapshot(volume, backup.getUuid());
        try {
            Answer answer = agentManager.send(backup.getHostId(), cmd);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException("Storage snapshot snapshot cleanup failed; backup ownership metadata retained");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Storage snapshot snapshot cleanup pending; backup ownership metadata retained", e);
        }
    }

    protected Backup startStorageSnapshotBackup(VMInstanceVO vm, BackupVO backup, StorageBackupVolume volume) {
        if (backup.getStatus() == Backup.Status.ReadyForImageTransfer || backup.getStatus() == Backup.Status.BackedUp) {
            return backup;
        }
        if (backup.getStatus() != Backup.Status.Queued) {
            throw new CloudRuntimeException("Storage snapshot backup is not queued for capture");
        }
        StartBackupCommand cmd = new StartBackupCommand(vm.getInstanceName(), null, null, null,
                backup.getUuid(), Collections.emptyMap(), Collections.emptyMap(), vm.getState() == State.Stopped);
        cmd.setStorageBackupVolume(volume);
        try {
            Answer answer = agentManager.send(backup.getHostId(), cmd);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException("Unable to start Storage snapshot full backup: " + (answer == null ? "no agent answer" : answer.getDetails()));
            }
            updateBackupState(backup, Backup.Status.ReadyForImageTransfer);
            queueBackupFinalizeWaitWorkJob(vm, backup);
            return backup;
        } catch (AgentUnavailableException | OperationTimedoutException | RuntimeException e) {
            updateBackupState(backup, Backup.Status.Error);
            try {
                releaseStorageSnapshot(backup, volume);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new CloudRuntimeException("Storage snapshot full backup failed; session metadata retained for cleanup", e);
        }
    }

    private Boolean isZoneCompatible(Long zoneId) {
        if (!BackupFrameworkEnabled.valueIn(zoneId)) {
            return true;
        }
        if (COMPATIBLE_BACKUP_PROVIDERS.contains(BackupProviderPlugin.valueIn(zoneId))) {
            return true;
        }
        return false;
    }

    private void verifyKVMBackupExportServiceSupported(Long zoneId) {
        if (!isZoneCompatible(zoneId)) {
            throw new CloudRuntimeException("Veeam-KVM backups are disabled because the CloudStack Zone is configured to use the \"" +
                    BackupProviderPlugin.valueIn(zoneId) + "\" backup provider. Acceptable \"backup.framework.provider.plugin\" " +
                    "values are " + COMPATIBLE_BACKUP_PROVIDERS +
                    ". Please refer to the documentation for more details on backup providers compatibility.");
        }
    }

    protected void validateVmVolumesForBackup(VMInstanceVO vm) {
        List<VolumeVO> volumes = volumeDao.findByInstanceAndNotStates(vm.getId(), Volume.State.Ready);
        List<String> nonReadyVolumeIds = volumes
                .stream()
                .map(VolumeVO::getUuid)
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(nonReadyVolumeIds)) {
            throw new CloudRuntimeException(String.format("Volumes [%s] of Instance: %s are not in Ready state",
                    StringUtils.join(nonReadyVolumeIds, ","), vm.getUuid()));
        }
    }

    @Override
    public Backup createBackup(StartBackupCmd cmd) {
        if (volumeDao.findByInstance(cmd.getVmId()).stream().anyMatch(this::isStorageSnapshot)) {
            return withStorageSnapshotLock("vm-" + cmd.getVmId(), () -> createBackupInternal(cmd));
        }
        return createBackupInternal(cmd);
    }

    protected Backup createBackupInternal(StartBackupCmd cmd) {
        Long vmId = cmd.getVmId();

        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("Instance not found: " + vmId);
        }

        verifyKVMBackupExportServiceSupported(vm.getDataCenterId());

        if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
            throw new CloudRuntimeException("Instance must be running or stopped to start Backup");
        }

        Backup existingBackup = backupDao.findByVmId(vmId);
        if (existingBackup != null && existingBackup.getStatus() == Backup.Status.BackingUp) {
            throw new CloudRuntimeException("Backup already in progress for Instance: " + vm.getUuid());
        }

        validateVmVolumesForBackup(vm);
        StorageBackupVolume storageBackupVolume = storageSnapshotSource(vm);
        if (storageBackupVolume != null && existingBackup != null && existingBackup.getStatus() != Backup.Status.BackedUp) {
            throw new CloudRuntimeException("A Storage snapshot backup session already exists for this VM");
        }

        Pair<Long, Long> clusterAndHostId = virtualMachineManager.findClusterAndHostIdForVm(vm, false);
        Long hostId = clusterAndHostId.second();
        if (hostId == null) {
            throw new CloudRuntimeException("Host cannot be determined for Instance: " + vm.getUuid());
        }

        BackupVO backup = new BackupVO();
        backup.setVmId(vmId);
        String name = cmd.getName();
        if (StringUtils.isEmpty(name)) {
            name = vmId + "-" + DateTime.now();
        }
        backup.setName(name);
        final String description = cmd.getDescription();
        if (StringUtils.isNotEmpty(description)) {
            backup.setDescription(description);
        }
        backup.setAccountId(vm.getAccountId());
        backup.setDomainId(vm.getDomainId());
        backup.setZoneId(vm.getDataCenterId());
        backup.setStatus(Backup.Status.Queued);
        backup.setBackupOfferingId(0L);
        backup.setDate(new Date());

        String toCheckpointId = "ckp-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String fromCheckpointId = vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);

        backup.setToCheckpointId(storageBackupVolume == null ? toCheckpointId : null);
        backup.setFromCheckpointId(storageBackupVolume == null ? fromCheckpointId : null);
        backup.setType("FULL");
        backup.setHostId(hostId);

        BackupVO persisted = backupDao.persist(backup);
        if (storageBackupVolume != null) {
            backupDetailsDao.addDetail(persisted.getId(), STORAGE_FULL_VOLUME, new Gson().toJson(storageBackupVolume), false);
        }
        return persisted;
    }

    protected void removeFailedBackup(BackupVO backup) {
        backup.setStatus(Backup.Status.Error);
        backupDao.update(backup.getId(), backup);
        backupDao.remove(backup.getId());
    }

    protected void queueBackupFinalizeWaitWorkJob(final VMInstanceVO vm, final BackupVO backup) {
        final CallContext context = CallContext.current();
        final Account callingAccount = context.getCallingAccount();
        final long callingUserId = context.getCallingUserId();

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkWaitForBackupFinalize.class.getName());
        workJob.setAccountId(callingAccount.getId());
        workJob.setUserId(callingUserId);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.User);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        VmWorkWaitForBackupFinalize workInfo = new VmWorkWaitForBackupFinalize(
                callingUserId, callingAccount.getId(), vm.getId(), VM_WORK_JOB_HANDLER, backup.getId());
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        asyncJobManager.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
    }

    @Override
    public Backup startBackup(StartBackupCmd cmd) {
        BackupVO backup = backupDao.findById(cmd.getEntityId());
        Long vmId = cmd.getVmId();
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            removeFailedBackup(backup);
            throw new CloudRuntimeException("Instance not found for Backup: " + backup.getUuid());
        }
        StorageBackupVolume storageBackupVolume = storageSnapshotBackupVolume(backup);
        if (storageBackupVolume != null) {
            return withStorageSnapshotLock("vm-" + vmId, () -> startStorageSnapshotBackup(vm, backupDao.findById(backup.getId()), storageBackupVolume));
        }
        boolean isVmStopped = vm.getState() == State.Stopped;

        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        Map<String, String> diskPathUuidMap = new HashMap<>();
        Map<String, byte[]> diskPathPassphraseMap = new HashMap<>();
        for (Volume vol : volumes) {
            if (vol.getPoolId() == null) {
                removeFailedBackup(backup);
                throw new CloudRuntimeException("Storage Pool cannot be determined for Volume: " + vol.getUuid());
            }
            String volumePath = getVolumePathForFileBasedBackend(vol);
            diskPathUuidMap.put(volumePath, vol.getUuid());
            if (isVmStopped) {
                VolumeInfo volumeInfo = volumeDataFactory.getVolume(vol.getId());
                diskPathPassphraseMap.put(volumePath, volumeInfo.getPassphrase());
            } else {
                diskPathPassphraseMap.put(volumePath, null);
            }
        }
        Long hostId = backup.getHostId();

        VMInstanceDetailVO lastCheckpointId = vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_ID);
        if (lastCheckpointId != null) {
            try {
                sendDeleteCheckpointCommand(vm, lastCheckpointId.getValue());
            } catch (CloudRuntimeException e) {
                logger.warn("Failed to delete last checkpoint {} for VM {}, proceeding with backup start", lastCheckpointId.getValue(), vmId, e);
            }
        }
        if (hostId == null) {
            removeFailedBackup(backup);
            throw new CloudRuntimeException("Host cannot be found for Backup: " + backup.getUuid());
        }

        Host host = hostDao.findById(hostId);
        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String activeCkpCreateTimeStr = vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME);
        Long fromCheckpointCreateTime = activeCkpCreateTimeStr != null ? NumbersUtil.parseLong(activeCkpCreateTimeStr, 0L) : null;
        StartBackupCommand startCmd = new StartBackupCommand(
            vm.getInstanceName(),
            backup.getToCheckpointId(),
            backup.getFromCheckpointId(),
            fromCheckpointCreateTime,
            backup.getUuid(),
            diskPathUuidMap,
            diskPathPassphraseMap,
            isVmStopped
        );

        StartBackupAnswer answer;
        try {
            answer = (StartBackupAnswer) agentManager.send(hostId, startCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            removeFailedBackup(backup);
            logger.error("Failed to communicate with agent on {} for {} start", host, backup, e);
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }

        if (!answer.getResult()) {
            removeFailedBackup(backup);
            logger.error("Failed to start {} due to: {}", backup, answer.getDetails());
            throw new CloudRuntimeException("Failed to start Backup: " + answer.getDetails());
        }

        // Update backup with checkpoint creation time
        backup.setCheckpointCreateTime(answer.getCheckpointCreateTime());
        updateBackupState(backup, Backup.Status.ReadyForImageTransfer);
        queueBackupFinalizeWaitWorkJob(vm, backup);
        return backup;
    }

    protected void updateBackupState(BackupVO backup, Backup.Status newStatus) {
        backup.setStatus(newStatus);
        backupDao.update(backup.getId(), backup);
    }

    private void updateVmCheckpoints(Long vmId, BackupVO backup) {
        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String oldCheckpointId = vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);
        String oldCreateTimeStr = vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME);
        if (oldCheckpointId != null && oldCreateTimeStr != null) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_ID, oldCheckpointId, false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME, oldCreateTimeStr, false);
        }
        String newCheckpointId = backup.getToCheckpointId();
        Long newCreateTime = backup.getCheckpointCreateTime();
        if (newCheckpointId != null && newCreateTime != null) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID, backup.getToCheckpointId(), false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, String.valueOf(newCreateTime), false);
        } else {
            logger.error("New checkpoint details are missing for backup {} and vm {}", backup.getId(), vmId);
        }
    }

    @Override
    public Backup finalizeBackup(FinalizeBackupCmd cmd) {
        BackupVO backup = backupDao.findByIdIncludingRemoved(cmd.getBackupId());
        if (backup != null && storageSnapshotBackupVolume(backup) != null) {
            return withStorageSnapshotLock("vm-" + backup.getVmId(), () -> {
                BackupVO current = backupDao.findByIdIncludingRemoved(backup.getId());
                if (current.getStatus() == Backup.Status.BackedUp) {
                    return current;
                }
                return finalizeBackupInternal(cmd);
            });
        }
        return finalizeBackupInternal(cmd);
    }

    protected Backup finalizeBackupInternal(FinalizeBackupCmd cmd) {
        Long vmId = cmd.getVmId();
        Long backupId = cmd.getBackupId();

        // Get backup
        BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup not found: " + backupId);
        }

        if (!backup.getVmId().equals(vmId)) {
            throw new CloudRuntimeException("Backup does not belong to VM: " + vmId);
        }

        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + vmId);
        }

        boolean failedStorageSnapshotBackup = storageSnapshotBackupVolume(backup) != null &&
                (backup.getStatus() == Backup.Status.Error || backup.getStatus() == Backup.Status.Failed);
        if (!failedStorageSnapshotBackup) {
            updateBackupState(backup, Backup.Status.FinalizingImageTransfer);
        }

        List<ImageTransferVO> transfers = imageTransferDao.listByBackupId(backupId);
        for (ImageTransferVO transfer : transfers) {
            if (transfer.getPhase() != ImageTransferVO.Phase.finished) {
                logger.warn("Finalize called for backup {} while Image transfer {} is not finalized, attempting to finalize it", backup.getUuid(), transfer.getUuid());
                if (isStorageSnapshotTransfer(transfer)) {
                    finishStorageSnapshotImageTransfer(transfer, false);
                } else {
                    finalizeImageTransfer(transfer.getId());
                }
            }
        }

        StorageBackupVolume storageBackupVolume = storageSnapshotBackupVolume(backup);
        if (storageBackupVolume != null) {
            releaseStorageSnapshot(backup, storageBackupVolume);
            if (failedStorageSnapshotBackup) {
                return backup;
            }
        } else if (vm.getState() == State.Running) {
            StopBackupCommand stopCmd = new StopBackupCommand(vm.getInstanceName(), vmId, backupId);

            StopBackupAnswer answer;
            try {
                answer = (StopBackupAnswer) agentManager.send(backup.getHostId(), stopCmd);
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                removeFailedBackup(backup);
                throw new CloudRuntimeException("Failed to communicate with agent", e);
            }

            if (!answer.getResult()) {
                removeFailedBackup(backup);
                throw new CloudRuntimeException("Failed to stop backup: " + answer.getDetails());
            }
        }

        if (storageBackupVolume == null) {
            updateVmCheckpoints(vmId, backup);
        }

        updateBackupState(backup, Backup.Status.BackedUp);
        backupDao.remove(backup.getId());

        return backup;

    }

    private ImageTransferVO createDownloadImageTransfer(Long backupId, VolumeVO volume, ImageTransfer.Backend backend) {
        final String direction = ImageTransfer.Direction.download.toString();
        BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup not found: " + backupId);
        }
        if (ImageTransfer.Backend.file.equals(backend)) {
            throw new CloudRuntimeException("File backend is not supported for download");
        }

        String transferId = UUID.randomUUID().toString();

        String socket = backup.getUuid();
        VMInstanceVO vm = vmInstanceDao.findById(backup.getVmId());
        if (vm.getState() == State.Stopped) {
            Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(backup.getVmId());
            String volumePath = getVolumePathForFileBasedBackend(volume);
            VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
            startNBDServer(transferId,
                    direction,
                    backup.getHostId(),
                    volume.getUuid(),
                    volumePath,
                    vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID),
                    volumeInfo.getPassphrase());
            socket = transferId;
        }

        HostVO backupHost = hostDao.findById(backup.getHostId());
        if (backupHost == null) {
            throw new CloudRuntimeException("Host not found for backup: " + backupId);
        }
        int idleTimeoutSec = ImageTransferIdleTimeoutSeconds.valueIn(backupHost.getDataCenterId());
        CreateImageTransferCommand transferCmd = new CreateImageTransferCommand(
                transferId,
                direction,
                volume.getUuid(),
                socket,
                backup.getFromCheckpointId(),
                idleTimeoutSec);

        try {
            CreateImageTransferAnswer answer;
            answer = (CreateImageTransferAnswer) agentManager.send(backup.getHostId(), transferCmd);

            if (!answer.getResult()) {
                throw new CloudRuntimeException("Failed to create image transfer: " + answer.getDetails());
            }

            ImageTransferVO imageTransfer = new ImageTransferVO(
                    transferId,
                    backupId,
                    volume.getId(),
                    backup.getHostId(),
                    socket,
                    ImageTransferVO.Phase.transferring,
                    ImageTransfer.Direction.download,
                    backup.getAccountId(),
                    backup.getDomainId(),
                    backup.getZoneId()
            );
            imageTransfer.setTransferUrl(answer.getTransferUrl());
            imageTransfer.setSignedTicketId(answer.getImageTransferId());
            imageTransfer = imageTransferDao.persist(imageTransfer);
            return imageTransfer;

        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }
    }

    private HostVO getRandomHostFromStoragePool(StoragePoolVO storagePool) {
        List<HostVO> hosts;
        switch (storagePool.getScope()) {
            case CLUSTER:
                hosts = hostDao.findByClusterId(storagePool.getClusterId());
                Collections.shuffle(hosts);
                return hosts.get(0);
            case ZONE:
                hosts = hostDao.findByDataCenterId(storagePool.getDataCenterId());
                Collections.shuffle(hosts);
                return hosts.get(0);
            case HOST:
                List<StoragePoolHostVO> storagePoolHostVOs = storagePoolHostDao.listByPoolId(storagePool.getId());
                Collections.shuffle(storagePoolHostVOs);
                return hostDao.findById(storagePoolHostVOs.get(0).getHostId());
            default:
                throw new CloudRuntimeException("Unsupported storage pool scope: " + storagePool.getScope());
        }
    }

    private void startNBDServer(String transferId, String direction, Long hostId, String exportName, String volumePath, String checkpointId, byte[] passphrase) {
        StartNBDServerAnswer nbdServerAnswer;
        if (hostId == null) {
            throw new CloudRuntimeException("Host cannot be determined for starting NBD server");
        }
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new CloudRuntimeException("Host cannot be found for starting NBD server with ID: " + hostId);
        }
        StartNBDServerCommand nbdServerCmd = new StartNBDServerCommand(
                transferId,
                exportName,
                volumePath,
                transferId,
                direction,
                checkpointId,
                passphrase
        );
        try {
            nbdServerAnswer = (StartNBDServerAnswer) agentManager.send(hostId, nbdServerCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }
        if (!nbdServerAnswer.getResult()) {
            throw new CloudRuntimeException("Failed to start the NBD server");
        }
    }

    protected void startStorageSnapshotNBDServer(String transferId, String direction, Long hostId, StorageBackupVolume volume, String backupUuid) {
        StartNBDServerCommand cmd = new StartNBDServerCommand(transferId, volume.getVolumeUuid(), null, transferId, direction, null, null);
        cmd.setStorageBackupVolume(volume, backupUuid);
        try {
            Answer answer = agentManager.send(hostId, cmd);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException("Unable to start Storage snapshot raw NBD export");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Storage snapshot NBD startup is unresolved; retain session for cleanup", e);
        }
    }

    private String getVolumePathPrefix(StoragePoolVO storagePool) {
        if (ScopeType.HOST.equals(storagePool.getScope())) {
                return storagePool.getPath();
        }
        switch (storagePool.getPoolType()) {
            case NetworkFilesystem:
                return String.format("/mnt/%s", storagePool.getUuid());
            case SharedMountPoint:
                return storagePool.getPath();
            default:
                throw new CloudRuntimeException("Unsupported storage pool type for file based image transfer: " + storagePool.getPoolType());
        }
    }

    private String getVolumePathForFileBasedBackend(Volume volume) {
        StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
        String volumePathPrefix = getVolumePathPrefix(storagePool);
        return volumePathPrefix + "/" + volume.getPath();
    }

    private ImageTransferVO createUploadImageTransfer(VolumeVO volume, ImageTransfer.Backend backend) {
        final String direction = ImageTransfer.Direction.upload.toString();
        String transferId = UUID.randomUUID().toString();

        Long poolId = volume.getPoolId();
        StoragePoolVO storagePool = poolId == null ? null : primaryDataStoreDao.findById(poolId);
        if (storagePool == null) {
            throw new CloudRuntimeException("Storage pool cannot be determined for volume: " + volume.getUuid());
        }

        Host host = getRandomHostFromStoragePool(storagePool);
        String volumePath = getVolumePathForFileBasedBackend(volume);
        int idleTimeoutSec = ImageTransferIdleTimeoutSeconds.valueIn(host.getDataCenterId());

        ImageTransferVO imageTransfer;
        CreateImageTransferCommand transferCmd;
        if (backend.equals(ImageTransfer.Backend.file)) {
            imageTransfer = new ImageTransferVO(
                    transferId,
                    volume.getId(),
                    host.getId(),
                    volumePath,
                    ImageTransferVO.Phase.transferring,
                    ImageTransfer.Direction.upload,
                    volume.getAccountId(),
                    volume.getDomainId(),
                    volume.getDataCenterId());

            transferCmd = new CreateImageTransferCommand(
                    transferId,
                    direction,
                    transferId,
                    volumePath,
                    idleTimeoutSec);

        } else {
            VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
            startNBDServer(transferId, direction, host.getId(), volume.getUuid(), volumePath, null, volumeInfo.getPassphrase());
            imageTransfer = new ImageTransferVO(
                    transferId,
                    null,
                    volume.getId(),
                    host.getId(),
                    transferId,
                    ImageTransferVO.Phase.transferring,
                    ImageTransfer.Direction.upload,
                    volume.getAccountId(),
                    volume.getDomainId(),
                    volume.getDataCenterId());

            transferCmd = new CreateImageTransferCommand(
                    transferId,
                    direction,
                    volume.getUuid(),
                    transferId,
                    null,
                    idleTimeoutSec);
        }
        CreateImageTransferAnswer transferAnswer;
        try {
            transferAnswer = (CreateImageTransferAnswer) agentManager.send(imageTransfer.getHostId(), transferCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }

        if (!transferAnswer.getResult()) {
            if (!backend.equals(ImageTransfer.Backend.file)) {
                stopNBDServer(imageTransfer);
            }
            throw new CloudRuntimeException("Failed to create image transfer: " + transferAnswer.getDetails());
        }

        imageTransfer.setTransferUrl(transferAnswer.getTransferUrl());
        imageTransfer.setSignedTicketId(transferAnswer.getImageTransferId());
        imageTransfer = imageTransferDao.persist(imageTransfer);

        try {
            final StateMachine2<Volume.State, Volume.Event, Volume> stateMachine = Volume.State.getStateMachine();
            stateMachine.transitTo(volume, Volume.Event.RestoreRequested, null, volumeDao);
        } catch (NoTransitionException e) {
            throw new CloudRuntimeException("Failed to transition volume  " + volume.getUuid(), e);
        }

        return imageTransfer;

    }

    protected void validateStorageSnapshotRestoreTarget(VolumeVO volume, ImageTransfer.Format format) {
        requireStorageSnapshotEnabled(primaryDataStoreDao.findById(volume.getPoolId()));
        com.cloud.storage.VolumeDetailVO marker = volumeDetailsDao.findDetail(volume.getId(), RestoreTargetDetail);
        if (format != ImageTransfer.Format.raw || volume.getInstanceId() != null || volume.getAttached() != null || volume.getState() != Volume.State.Ready ||
                marker == null || !volume.getUuid().equals(marker.getValue())) {
            throw new CloudRuntimeException("Storage snapshot upload requires a new, unattached raw restore target");
        }
        storageSnapshotIdentity(volume);
    }

    protected ImageTransferVO createStorageSnapshotImageTransfer(VolumeVO volume, BackupVO backup, ImageTransfer.Direction direction,
                                                        ImageTransfer.Format format) {
        StorageBackupVolume identity;
        Host host;
        if (direction == ImageTransfer.Direction.upload) {
            validateStorageSnapshotRestoreTarget(volume, format);
            identity = storageSnapshotIdentity(volume);
            host = getStorageSnapshotRestoreHost(primaryDataStoreDao.findById(volume.getPoolId()));
        } else {
            identity = backup == null ? null : storageSnapshotBackupVolume(backup);
            if (identity == null || !volume.getUuid().equals(identity.getVolumeUuid()) ||
                    !backup.getVmId().equals(volume.getInstanceId()) || backup.getStatus() != Backup.Status.ReadyForImageTransfer) {
                throw new CloudRuntimeException("Disk does not belong to the Storage snapshot backup ready for transfer");
            }
            host = hostDao.findById(backup.getHostId());
        }
        if (host == null || host.getStatus() != com.cloud.host.Status.Up) {
            throw new CloudRuntimeException("Storage snapshot transfer requires an Up KVM host");
        }
        String id = UUID.randomUUID().toString();
        ImageTransferVO transfer = new ImageTransferVO(id, backup == null ? null : backup.getId(), volume.getId(), host.getId(),
                id, ImageTransfer.Phase.initializing, direction, volume.getAccountId(), volume.getDomainId(), volume.getDataCenterId());
        // Persist the export identity before any agent request, including requests whose answer may be lost.
        transfer = imageTransferDao.persist(transfer);
        try {
            if (direction == ImageTransfer.Direction.upload) {
                Volume.State.getStateMachine().transitTo(volume, Volume.Event.RestoreRequested, null, volumeDao);
            }
            startStorageSnapshotNBDServer(id, direction.toString(), host.getId(), identity, backup == null ? null : backup.getUuid());
            // From this point registration might complete even if its reply is lost.
            transfer.setPhase(ImageTransfer.Phase.transferring);
            imageTransferDao.update(transfer.getId(), transfer);
            CreateImageTransferCommand cmd = new CreateImageTransferCommand(id, direction.toString(), identity.getVolumeUuid(),
                    id, null, ImageTransferIdleTimeoutSeconds.valueIn(volume.getDataCenterId()));
            CreateImageTransferAnswer answer = (CreateImageTransferAnswer) agentManager.send(host.getId(), cmd);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException("Unable to register the Storage snapshot raw image transfer");
            }
            transfer.setTransferUrl(answer.getTransferUrl());
            transfer.setSignedTicketId(answer.getImageTransferId());
            transfer.setPhase(ImageTransfer.Phase.transferring);
            imageTransferDao.update(transfer.getId(), transfer);
            return transfer;
        } catch (AgentUnavailableException | OperationTimedoutException | NoTransitionException | RuntimeException e) {
            try {
                finishStorageSnapshotImageTransfer(transfer, true);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new CloudRuntimeException("Storage snapshot transfer failed; unresolved export identity is retained for cleanup", e);
        }
    }

    protected Host getStorageSnapshotRestoreHost(StoragePoolVO pool) {
        List<HostVO> candidates;
        switch (pool.getScope()) {
            case CLUSTER:
                candidates = hostDao.findByClusterId(pool.getClusterId());
                break;
            case ZONE:
                candidates = hostDao.findByDataCenterId(pool.getDataCenterId());
                break;
            case HOST:
                candidates = storagePoolHostDao.listByPoolId(pool.getId()).stream()
                        .map(association -> hostDao.findById(association.getHostId())).collect(Collectors.toList());
                break;
            default:
                throw new CloudRuntimeException("Unsupported Storage snapshot storage pool scope");
        }
        return candidates.stream().filter(host -> host != null && host.getStatus() == com.cloud.host.Status.Up &&
                host.getHypervisorType() == com.cloud.hypervisor.Hypervisor.HypervisorType.KVM).findFirst()
                .orElseThrow(() -> new CloudRuntimeException("No Up KVM host is available for Storage snapshot restore"));
    }

    protected void closeStorageSnapshotImageTransfer(ImageTransferVO transfer) {
        try {
            // Unregister drains in-flight requests before NBD or its block device can be released.
            if (transfer.getPhase() != ImageTransfer.Phase.initializing) {
                FinalizeImageTransferCommand cmd = new FinalizeImageTransferCommand(transfer.getUuid());
                cmd.setRequireSafeUnregister(true);
                Answer unregister = agentManager.send(transfer.getHostId(), cmd);
                if (unregister == null || !unregister.getResult()) {
                    throw new CloudRuntimeException("Storage snapshot image-server unregister is unresolved; export ownership retained");
                }
            }
            if (!stopNBDServer(transfer)) {
                throw new CloudRuntimeException("Storage snapshot transfer cleanup is pending; export ownership retained");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Storage snapshot transfer cleanup is pending; export ownership retained", e);
        }
    }

    protected boolean finishStorageSnapshotImageTransfer(ImageTransferVO transfer, boolean cancelled) {
        ImageTransferVO current = imageTransferDao.findById(transfer.getId());
        if (current == null) {
            ImageTransferVO previous = imageTransferDao.findByIdIncludingRemoved(transfer.getId());
            return previous != null && (cancelled || previous.getPhase() == ImageTransfer.Phase.finished);
        }
        closeStorageSnapshotImageTransfer(transfer);
        if (transfer.getDirection() == ImageTransfer.Direction.upload) {
            VolumeVO volume = volumeDao.findById(transfer.getVolumeId());
            if (volume.getState() == Volume.State.Restoring) {
                try {
                    Volume.State.getStateMachine().transitTo(volume,
                            cancelled ? Volume.Event.OperationFailed : Volume.Event.RestoreSucceeded, null, volumeDao);
                } catch (NoTransitionException e) {
                    throw new CloudRuntimeException("Unable to finish Storage snapshot restore target", e);
                }
            }
            volumeDetailsDao.removeDetail(volume.getId(), RestoreTargetDetail);
        } else if (cancelled) {
            BackupVO backup = backupDao.findById(transfer.getBackupId());
            updateBackupState(backup, Backup.Status.Error);
            releaseStorageSnapshot(backup, storageSnapshotBackupVolume(backup));
        }
        transfer.setPhase(cancelled ? ImageTransfer.Phase.failed : ImageTransfer.Phase.finished);
        imageTransferDao.update(transfer.getId(), transfer);
        imageTransferDao.remove(transfer.getId());
        return true;
    }

    protected boolean isStorageSnapshotTransfer(ImageTransferVO transfer) {
        if (transfer.getBackupId() != null) {
            BackupVO backup = backupDao.findByIdIncludingRemoved(transfer.getBackupId());
            return backup != null && storageSnapshotBackupVolume(backup) != null;
        }
        VolumeVO volume = volumeDao.findById(transfer.getVolumeId());
        return volume != null && isStorageSnapshot(volume);
    }

    private ImageTransfer.Backend getImageTransferBackend(ImageTransfer.Format format, ImageTransfer.Direction direction) {
        if (ImageTransfer.Format.cow.equals(format)) {
            if (ImageTransfer.Direction.download.equals(direction)) {
                logger.debug("Using NBD backend for download");
                return ImageTransfer.Backend.nbd;
            }
            return ImageTransfer.Backend.file;
        } else {
            return ImageTransfer.Backend.nbd;
        }
    }

    @Override
    public ImageTransferResponse createImageTransfer(CreateImageTransferCmd cmd) {
        ImageTransfer imageTransfer = createImageTransfer(cmd.getVolumeId(), cmd.getBackupId(), cmd.getDirection(), cmd.getFormat());
        if (imageTransfer instanceof ImageTransferVO) {
            ImageTransferVO imageTransferVO = (ImageTransferVO) imageTransfer;
            return toImageTransferResponse(imageTransferVO);
        }
        return toImageTransferResponse(imageTransferDao.findById(imageTransfer.getId()));
    }

    @Override
    public ImageTransfer createImageTransfer(long volumeId, Long backupId, ImageTransfer.Direction direction, ImageTransfer.Format format) {
        User callingUser = CallContext.current().getCallingUser();
        ImageTransfer imageTransfer;
        VolumeVO volume = volumeDao.findById(volumeId);
        accountService.checkAccess(callingUser, volume);

        if (volume == null) {
            throw new CloudRuntimeException("Volume not found with the specified Id");
        }

        verifyKVMBackupExportServiceSupported(volume.getDataCenterId());

        ImageTransferVO existingTransfer = imageTransferDao.findByVolume(volume.getId());
        if (existingTransfer != null) {
            throw new CloudRuntimeException("Image transfer already exists for volume: " + volume.getUuid());
        }

        if (isStorageSnapshot(volume)) {
            BackupVO backup = backupId == null ? null : backupDao.findById(backupId);
            String key = direction == ImageTransfer.Direction.download && backup != null ? "vm-" + backup.getVmId() : "volume-" + volumeId;
            return withStorageSnapshotLock(key, () -> {
                if (imageTransferDao.findByVolume(volumeId) != null) {
                    throw new CloudRuntimeException("A transfer already exists for this Storage snapshot volume");
                }
                return createStorageSnapshotImageTransfer(volumeDao.findById(volumeId),
                        backup == null ? null : backupDao.findById(backup.getId()), direction, format);
            });
        }
        ImageTransfer.Backend backend = getImageTransferBackend(format, direction);
        if (ImageTransfer.Direction.upload.equals(direction)) {
            imageTransfer = createUploadImageTransfer(volume, backend);
        } else if (ImageTransfer.Direction.download.equals(direction)) {
            imageTransfer = createDownloadImageTransfer(backupId, volume, backend);
        } else {
            throw new CloudRuntimeException("Invalid direction: " + direction);
        }

        return imageTransferDao.findById(imageTransfer.getId());
    }

    @Override
    public boolean cancelImageTransfer(long imageTransferId) {
        ImageTransferVO transfer = imageTransferDao.findById(imageTransferId);
        if (transfer == null) {
            ImageTransferVO previous = imageTransferDao.findByIdIncludingRemoved(imageTransferId);
            if (previous != null && isStorageSnapshotTransfer(previous)) {
                return true;
            }
        }
        if (transfer != null && isStorageSnapshotTransfer(transfer)) {
            return withStorageSnapshotLock(storageSnapshotTransferLock(transfer), () -> finishStorageSnapshotImageTransfer(transfer, true));
        }
        finalizeImageTransfer(imageTransferId);
        return true;
    }

    private void finalizeDownloadImageTransfer(ImageTransferVO imageTransfer) {

        String transferId = imageTransfer.getUuid();
        FinalizeImageTransferCommand finalizeCmd = new FinalizeImageTransferCommand(transferId);

        BackupVO backup = backupDao.findById(imageTransfer.getBackupId());

        Answer answer;
        try {
            answer = agentManager.send(backup.getHostId(), finalizeCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }

        if (!answer.getResult()) {
            throw new CloudRuntimeException("Failed to finalize image transfer: " + answer.getDetails());
        }

        VMInstanceVO vm = vmInstanceDao.findById(backup.getVmId());
        if (storageSnapshotBackupVolume(backup) != null || vm.getState() == State.Stopped) {
            boolean stopNbdServerResult = stopNBDServer(imageTransfer);
            if (!stopNbdServerResult) {
                throw new CloudRuntimeException("Failed to stop the nbd server");
            }
        }
    }

    private boolean stopNBDServer(ImageTransferVO imageTransfer) {
        String transferId = imageTransfer.getUuid();
        String direction = imageTransfer.getDirection().toString();
        StopNBDServerCommand stopNbdServerCommand = new StopNBDServerCommand(transferId, direction);
        VolumeVO volume = volumeDao.findById(imageTransfer.getVolumeId());
        if (imageTransfer.getBackupId() != null) {
            BackupVO backup = backupDao.findByIdIncludingRemoved(imageTransfer.getBackupId());
            if (backup != null) {
                stopNbdServerCommand.setStorageBackupVolume(storageSnapshotBackupVolume(backup));
            }
        } else if (volume != null && isStorageSnapshot(volume)) {
            stopNbdServerCommand.setStorageBackupVolume(storageSnapshotIdentity(volume));
        }
        Answer answer;
        try {
            answer = agentManager.send(imageTransfer.getHostId(), stopNbdServerCommand);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error("Failed to stop NBD server on image transfer finalization", e);
            return false;
        }
        return answer.getResult();
    }

    private String finalizeUploadImageTransferWithResult(ImageTransferVO imageTransfer) {
        String transferId = imageTransfer.getUuid();
        boolean stopNbdServerResult = stopNBDServer(imageTransfer);
        if (!stopNbdServerResult) {
            return "Failed to stop the nbd server";
        }

        FinalizeImageTransferCommand finalizeCmd = new FinalizeImageTransferCommand(transferId);
        Answer answer;
        try {
            answer = agentManager.send(imageTransfer.getHostId(), finalizeCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            return "Failed to communicate with agent";
        }

        if (!answer.getResult()) {
            return "Failed to finalize image transfer: " + answer.getDetails();
        }
        return null;
    }

    private void finalizeUploadImageTransfer(ImageTransferVO imageTransfer) {
        String failureReason = finalizeUploadImageTransferWithResult(imageTransfer);
        Volume.Event event = failureReason == null ? Volume.Event.RestoreSucceeded : Volume.Event.RestoreFailed;

        VolumeVO volume = volumeDao.findById(imageTransfer.getVolumeId());
        if (isStorageSnapshot(volume)) {
            volumeDetailsDao.removeDetail(volume.getId(), RestoreTargetDetail);
        }
        try {
            final StateMachine2<Volume.State, Volume.Event, Volume> stateMachine = Volume.State.getStateMachine();
            stateMachine.transitTo(volume, event, null, volumeDao);
        } catch (NoTransitionException e) {
            throw new CloudRuntimeException("Failed to transition state during upload finalize image transfer for volume " + volume.getUuid(), e);
        }

        if (failureReason != null) {
            throw new CloudRuntimeException(failureReason);
        }
    }

    @Override
    public boolean finalizeImageTransfer(FinalizeImageTransferCmd cmd) {
        return finalizeImageTransfer(cmd.getImageTransferId());
    }

    @Override
    public boolean finalizeImageTransfer(final long imageTransferId) {
        ImageTransferVO imageTransfer = imageTransferDao.findById(imageTransferId);
        if (imageTransfer == null) {
            ImageTransferVO previous = imageTransferDao.findByIdIncludingRemoved(imageTransferId);
            if (previous != null && isStorageSnapshotTransfer(previous)) {
                return previous.getPhase() == ImageTransfer.Phase.finished;
            }
            throw new CloudRuntimeException("Image transfer not found: " + imageTransferId);
        }

        if (isStorageSnapshotTransfer(imageTransfer)) {
            return withStorageSnapshotLock(storageSnapshotTransferLock(imageTransfer), () -> finishStorageSnapshotImageTransfer(imageTransfer, false));
        }
        if (imageTransfer.getDirection().equals(ImageTransfer.Direction.download)) {
            finalizeDownloadImageTransfer(imageTransfer);
        } else {
            finalizeUploadImageTransfer(imageTransfer);
        }
        imageTransfer.setPhase(ImageTransferVO.Phase.finished);
        imageTransferDao.update(imageTransfer.getId(), imageTransfer);
        imageTransferDao.remove(imageTransfer.getId());
        return true;
    }

    @Override
    public List<ImageTransferResponse> listImageTransfers(ListImageTransfersCmd cmd) {
        Long id = cmd.getId();
        Long backupId = cmd.getBackupId();

        List<ImageTransferVO> transfers;
        if (id != null) {
            transfers = List.of(imageTransferDao.findById(id));
        } else if (backupId != null) {
            transfers = imageTransferDao.listByBackupId(backupId);
        } else {
            transfers = imageTransferDao.listAll();
        }

        return transfers.stream().map(this::toImageTransferResponse).collect(Collectors.toList());
    }

    private CheckpointResponse createCheckpointResponse(String checkpointId, String createTime, boolean isActive) {
        CheckpointResponse response = new CheckpointResponse();
        response.setObjectName("checkpoint");
        response.setId(checkpointId);
        Long createTimeSeconds = createTime != null ? NumbersUtil.parseLong(createTime, 0L) : 0L;
        response.setCreated(Date.from(Instant.ofEpochSecond(createTimeSeconds)));
        response.setIsActive(isActive);
        return response;
    }

    @Override
    public List<CheckpointResponse> listVmCheckpoints(ListVmCheckpointsCmd cmd) {
        Long vmId = cmd.getVmId();
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + vmId);
        }
        List<CheckpointResponse> responses = new ArrayList<>();
        if (volumeDao.findByInstance(vmId).stream().anyMatch(this::isStorageSnapshot)) {
            return responses;
        }

        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String activeCheckpointId = details.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);
        if (activeCheckpointId != null) {
            responses.add(createCheckpointResponse(activeCheckpointId, details.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME), true));
        }
        String lastCheckpointId = details.get(VmDetailConstants.LAST_CHECKPOINT_ID);
        if (lastCheckpointId != null) {
            responses.add(createCheckpointResponse(lastCheckpointId, details.get(VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME), false));
        }
        return responses;
    }

    private void sendDeleteCheckpointCommand(VMInstanceVO vm, String checkpointId) {
        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();

        Map<String, String> diskPathUuidMap = new HashMap<>();
        if (vm.getState() == State.Stopped) {
            List<VolumeVO> volumes = volumeDao.findByInstance(vm.getId());
            for (Volume vol : volumes) {
                diskPathUuidMap.put(getVolumePathForFileBasedBackend(vol), vol.getUuid());
            }
        }

        DeleteVmCheckpointCommand deleteCmd = new DeleteVmCheckpointCommand(
                vm.getInstanceName(),
                checkpointId,
                diskPathUuidMap,
                vm.getState() == State.Stopped);

        Answer answer;
        try {
            answer = agentManager.send(hostId, deleteCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error("Failed to communicate with agent to delete checkpoint for VM {}", vm.getId(), e);
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }

        if (answer == null || !answer.getResult()) {
            String err = answer != null ? answer.getDetails() : "null answer";
            throw new CloudRuntimeException("Failed to delete checkpoint: " + err);
        }
    }

    @Override
    public boolean deleteVmCheckpoint(DeleteVmCheckpointCmd cmd) {
        VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + cmd.getVmId());
        }
        if (volumeDao.findByInstance(vm.getId()).stream().anyMatch(this::isStorageSnapshot)) {
            throw new CloudRuntimeException("Storage snapshot full backups do not have QCOW2 checkpoints");
        }

        verifyKVMBackupExportServiceSupported(vm.getDataCenterId());

        if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
            throw new CloudRuntimeException("VM must be running or stopped to delete checkpoint");
        }

        long vmId = cmd.getVmId();
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String activeCheckpointId = details.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);
        if (activeCheckpointId == null || !activeCheckpointId.equals(cmd.getCheckpointId())) {
            logger.error("Checkpoint ID {} to delete does not match active checkpoint ID for VM {}", cmd.getCheckpointId(), vmId);
            return true;
        }

        sendDeleteCheckpointCommand(vm, activeCheckpointId);
        revertVmCheckpointDetailsAfterActiveDelete(vmId, details);

        return true;
    }

    @Override
    public List<Long> listCompatibleDataCenterIds() {
        return dataCenterDao.listAllIds().stream()
                .filter(this::isZoneCompatible)
                .collect(Collectors.toList());
    }

    private void revertVmCheckpointDetailsAfterActiveDelete(long vmId, Map<String, String> detailsBeforeDelete) {
        String lastId = detailsBeforeDelete.get(VmDetailConstants.LAST_CHECKPOINT_ID);
        String lastTime = detailsBeforeDelete.get(VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME);
        if (lastId != null) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID, lastId, false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, lastTime, false);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_ID);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME);
        } else {
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME);
        }
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        if (ExposeKVMBackupExportServiceApis.value()) {
            cmdList.add(StartBackupCmd.class);
            cmdList.add(FinalizeBackupCmd.class);
            cmdList.add(CreateImageTransferCmd.class);
            cmdList.add(FinalizeImageTransferCmd.class);
            cmdList.add(ListImageTransfersCmd.class);
            cmdList.add(ListVmCheckpointsCmd.class);
            cmdList.add(DeleteVmCheckpointCmd.class);
        }
        return cmdList;
    }

    private ImageTransferResponse toImageTransferResponse(ImageTransferVO imageTransferVO) {
        ImageTransferResponse response = new ImageTransferResponse();
        response.setId(imageTransferVO.getUuid());
        Long backupId = imageTransferVO.getBackupId();
        if (backupId != null) {
            Backup backup = backupDao.findByIdIncludingRemoved(backupId);
            response.setBackupId(backup.getUuid());
        }
        Long volumeId = imageTransferVO.getVolumeId();
        Volume volume = volumeDao.findByIdIncludingRemoved(volumeId);
        response.setDiskId(volume.getUuid());
        response.setTransferUrl(imageTransferVO.getTransferUrl());
        response.setPhase(imageTransferVO.getPhase().toString());
        response.setDirection(imageTransferVO.getDirection().toString());
        response.setCreated(imageTransferVO.getCreated());
        return response;
    }

    @Override
    public boolean start() {
        storageSnapshotCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "storage-backup-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        storageSnapshotCleanupExecutor.scheduleWithFixedDelay(this::reconcileStorageSnapshotSessions, 60, 60, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (storageSnapshotCleanupExecutor != null) {
            storageSnapshotCleanupExecutor.shutdownNow();
        }
        return true;
    }

    protected boolean storageSnapshotSessionExpired(Date created, StoragePool pool) {
        BackupStorageCapabilities capabilities = getStorageCapabilities(pool);
        if (!capabilities.supportsNativeSnapshots()) {
            throw new CloudRuntimeException("Storage provider is unavailable for session recovery");
        }
        long seconds = capabilities.getSessionTimeoutSeconds();
        return created != null && System.currentTimeMillis() - created.getTime() > Math.max(60L, seconds) * 1000L;
    }

    protected void reconcileStorageSnapshotSessions() {
        try {
            // Only metadata belonging to this full-export implementation is eligible for recovery.
            for (BackupVO backup : backupDao.listAll()) {
                StorageBackupVolume volume = storageSnapshotBackupVolume(backup);
                if (volume == null || (backup.getStatus() != Backup.Status.Error && !storageSnapshotSessionExpired(backup.getDate(), primaryDataStoreDao.findByUuid(volume.getPoolUuid())))) {
                    continue;
                }
                try {
                    withStorageSnapshotLock("vm-" + backup.getVmId(), () -> {
                        BackupVO current = backupDao.findById(backup.getId());
                        if (current == null || current.getStatus() == Backup.Status.BackedUp ||
                                (current.getStatus() != Backup.Status.Error && !storageSnapshotSessionExpired(current.getDate(), primaryDataStoreDao.findByUuid(volume.getPoolUuid())))) {
                            return true;
                        }
                        for (ImageTransferVO transfer : imageTransferDao.listByBackupId(backup.getId())) {
                            finishStorageSnapshotImageTransfer(transfer, true);
                        }
                        releaseStorageSnapshot(backup, volume);
                        updateBackupState(backup, Backup.Status.Error);
                        backupDao.remove(backup.getId());
                        return true;
                    });
                } catch (RuntimeException e) {
                    logger.warn("Storage snapshot backup cleanup pending for {}; ownership metadata retained", backup.getUuid(), e);
                }
            }
            for (ImageTransferVO transfer : imageTransferDao.listAll()) {
                if (transfer.getDirection() == ImageTransfer.Direction.upload && isStorageSnapshotTransfer(transfer) &&
                        storageSnapshotSessionExpired(transfer.getCreated(), primaryDataStoreDao.findById(volumeDao.findById(transfer.getVolumeId()).getPoolId()))) {
                    try {
                        withStorageSnapshotLock(storageSnapshotTransferLock(transfer), () -> finishStorageSnapshotImageTransfer(transfer, true));
                    } catch (RuntimeException e) {
                        logger.warn("Storage snapshot restore cleanup pending for {}; ownership metadata retained", transfer.getUuid(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Unable to reconcile Storage snapshot export sessions", e);
        }
    }

    @ReflectionUse
    public Pair<JobInfo.Status, String> orchestrateWaitForBackupFinalize(VmWorkWaitForBackupFinalize work) {
        return waitForBackupTerminalState(work.getBackupId());
    }

    @Override
    public Pair<JobInfo.Status, String> handleVmWorkJob(VmWork work) throws Exception {
        return jobHandlerProxy.handleVmWorkJob(work);
    }

    protected Pair<JobInfo.Status, String> waitForBackupTerminalState(final long backupId) {
        while (true) {
            final BackupVO backup = backupDao.findByIdIncludingRemoved(backupId);
            if (backup == null) {
                RuntimeException ex = new CloudRuntimeException(String.format("Backup %d not found while waiting for finalize", backupId));
                return new Pair<>(JobInfo.Status.FAILED, asyncJobManager.marshallResultObject(ex));
            }

            if (backup.getStatus() == Backup.Status.BackedUp) {
                return new Pair<>(JobInfo.Status.SUCCEEDED, asyncJobManager.marshallResultObject(backup.getId()));
            }

            if (backup.getStatus() == Backup.Status.Failed || backup.getStatus() == Backup.Status.Error) {
                RuntimeException ex = new CloudRuntimeException(String.format("Backup %d reached terminal failure state: %s", backupId, backup.getStatus()));
                return new Pair<>(JobInfo.Status.FAILED, asyncJobManager.marshallResultObject(ex));
            }
            logger.debug("{} is not in a terminal state, current state: {}, waiting {}ms to check again",
                    backup, backup.getStatus(), BACKUP_FINALIZE_WAIT_CHECK_INTERVAL);
            try {
                Thread.sleep(BACKUP_FINALIZE_WAIT_CHECK_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                    RuntimeException ex = new CloudRuntimeException(String.format("Interrupted while waiting for backup %d finalize", backupId), e);
                    return new Pair<>(JobInfo.Status.FAILED, asyncJobManager.marshallResultObject(ex));
                }
            }
        }

    @Override
    public String getConfigComponentName() {
        return KVMBackupExportService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                ImageTransferIdleTimeoutSeconds,
                ExposeKVMBackupExportServiceApis
        };
    }
}
