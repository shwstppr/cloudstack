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

import com.cloud.storage.Storage.ImageFormat;

/**
 * Provider-owned storage capabilities, evaluated for the requested pool/zone.
 * Native export describes support independently of enablement so recovery remains
 * possible after new backup sessions have been disabled.
 */
public final class BackupStorageCapabilities {
    public static final BackupStorageCapabilities UNSUPPORTED = new BackupStorageCapabilities(false, false, false, false, null, 0, 0);
    private final boolean nativeSnapshots;
    private final boolean discoverable;
    private final boolean fullBackupEnabled;
    private final boolean incrementalBackupEnabled;
    private final ImageFormat volumeFormat;
    private final int maximumDisks;
    private final int sessionTimeoutSeconds;

    public BackupStorageCapabilities(boolean nativeSnapshots, boolean discoverable, boolean fullBackupEnabled,
                                     ImageFormat volumeFormat, int maximumDisks, int sessionTimeoutSeconds) {
        this(nativeSnapshots, discoverable, fullBackupEnabled, false, volumeFormat, maximumDisks, sessionTimeoutSeconds);
    }

    public BackupStorageCapabilities(boolean nativeSnapshots, boolean discoverable, boolean fullBackupEnabled,
                                     boolean incrementalBackupEnabled, ImageFormat volumeFormat, int maximumDisks, int sessionTimeoutSeconds) {
        this.nativeSnapshots = nativeSnapshots;
        this.discoverable = discoverable;
        this.fullBackupEnabled = fullBackupEnabled;
        this.incrementalBackupEnabled = incrementalBackupEnabled && fullBackupEnabled;
        this.volumeFormat = volumeFormat;
        this.maximumDisks = maximumDisks;
        this.sessionTimeoutSeconds = sessionTimeoutSeconds;
    }

    public boolean supportsNativeSnapshots() { return nativeSnapshots; }
    public boolean isDiscoverable() { return discoverable; }
    public boolean isFullBackupEnabled() { return fullBackupEnabled; }
    public boolean isIncrementalBackupEnabled() { return incrementalBackupEnabled; }
    public ImageFormat getVolumeFormat() { return volumeFormat; }
    public int getMaximumDisks() { return maximumDisks; }
    public int getSessionTimeoutSeconds() { return sessionTimeoutSeconds; }
}
