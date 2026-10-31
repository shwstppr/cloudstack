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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.apache.cloudstack.backup.StopNBDServerCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = StopNBDServerCommand.class)
public class LibvirtStopNBDServerCommandWrapper extends CommandWrapper<StopNBDServerCommand, Answer, LibvirtComputingResource> {
    protected Logger logger = LogManager.getLogger(getClass());

    private void resetService(String unitName) {
        Script resetScript = new Script("/bin/bash", logger);
        resetScript.add("-c");
        resetScript.add(String.format("systemctl reset-failed %s || true", unitName));
        resetScript.execute();
    }

    @Override
    public Answer execute(StopNBDServerCommand cmd, LibvirtComputingResource resource) {
        try {
            String unitName = cmd.getStorageBackupVolume() == null ? "qemu-nbd-" + cmd.getTransferId().hashCode() :
                    StorageBackupCommandSupport.unitName(cmd.getTransferId());

            // Check if the service is running
            Script checkScript = new Script("/bin/bash", logger);
            checkScript.add("-c");
            checkScript.add(String.format("systemctl is-active --quiet %s", unitName));
            String checkResult = checkScript.execute();
            if (checkResult != null) {
                if (cmd.getStorageBackupVolume() != null && checkScript.getExitValue() != 3 && checkScript.getExitValue() != 4) {
                    return new Answer(cmd, false, "Unable to establish that the Storage snapshot NBD export has stopped");
                }
                // Service is not running, but still reset-failed to clear any stale state
                logger.info(String.format("qemu-nbd service %s is not running, resetting failed state", unitName));
                resetService(unitName);
                disconnectStorageSnapshotTarget(cmd, resource);
                cleanupCowExport(cmd);
                return new Answer(cmd, true, "Image transfer finalized");
            }

            // Stop the systemd service
            Script stopScript = new Script("/bin/bash", logger);
            stopScript.add("-c");
            stopScript.add(String.format("systemctl stop %s", unitName));
            String stopResult = stopScript.execute();
            if (cmd.getStorageBackupVolume() != null && stopResult != null) {
                return new Answer(cmd, false, "Unable to stop the Storage snapshot NBD export");
            }
            resetService(unitName);
            disconnectStorageSnapshotTarget(cmd, resource);
            cleanupCowExport(cmd);

            return new Answer(cmd, true, "Image transfer finalized");

        } catch (Exception e) {
            logger.error("Error finalizing image transfer for upload", e);
            return new Answer(cmd, false, "Error finalizing image transfer: " + e.getMessage());
        }
    }

    private void cleanupCowExport(StopNBDServerCommand cmd) throws IOException {
        if (cmd.isCowExport()) {
            UUID.fromString(cmd.getTransferId());
            Files.deleteIfExists(Path.of("/tmp/imagetransfer", "cs-vbr-export-" + cmd.getTransferId() + ".qcow2"));
        }
    }

    private void disconnectStorageSnapshotTarget(StopNBDServerCommand cmd, LibvirtComputingResource resource) {
        if (cmd.getStorageBackupVolume() != null && "upload".equals(cmd.getDirection())) {
            if (!StorageBackupCommandSupport.pool(cmd.getStorageBackupVolume(), resource)
                    .disconnectPhysicalDisk(cmd.getStorageBackupVolume().getVolumePath())) {
                throw new IllegalStateException("Unable to disconnect Storage snapshot restore target after stopping NBD");
            }
        }
    }
}
