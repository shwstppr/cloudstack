-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade from 4.22.0.0 to 4.22.1.0
--;

-- Add vm_id column to usage_event table for volume usage events
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.usage_event','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with volume usage events"');
CALL `cloud_usage`.`IDEMPOTENT_ADD_COLUMN`('cloud_usage.usage_event','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with volume usage events"');

-- Add vm_id column to cloud_usage.usage_volume table
CALL `cloud_usage`.`IDEMPOTENT_ADD_COLUMN`('cloud_usage.usage_volume','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with the volume usage"');

ALTER TABLE `cloud`.`template_store_ref` MODIFY COLUMN `download_url` varchar(2048);

UPDATE `cloud`.`alert` SET type = 33 WHERE name = 'ALERT.VR.PUBLIC.IFACE.MTU';
UPDATE `cloud`.`alert` SET type = 34 WHERE name = 'ALERT.VR.PRIVATE.IFACE.MTU';

-- Update configuration 'kvm.ssh.to.agent' description and is_dynamic fields
UPDATE `cloud`.`configuration` SET description = 'True if the management server will restart the agent service via SSH into the KVM hosts after or during maintenance operations', is_dynamic = 1 WHERE name = 'kvm.ssh.to.agent';

-- Sanitize legacy network-level addressing fields for Public networks
UPDATE `cloud`.`networks`
SET `broadcast_uri` = NULL,
	`gateway` = NULL,
	`cidr` = NULL,
	`ip6_gateway` = NULL,
	`ip6_cidr` = NULL
WHERE `traffic_type` = 'Public';

UPDATE `cloud`.`vm_template` SET guest_os_id = 99 WHERE name = 'kvm-default-vm-import-dummy-template';

-- Update existing vm_template records with NULL type to "USER"
UPDATE `cloud`.`vm_template` SET `type` = 'USER' WHERE `type` IS NULL;

-- remove unused config item
DELETE FROM `cloud`.`configuration` WHERE name = 'consoleproxy.cmd.port';

-- Drops the unused "backup_interval_type" column of the "cloud.backups" table
ALTER TABLE `cloud`.`backups` DROP COLUMN `backup_interval_type`;

-- Update `user.password.reset.mail.template` configuration value to match new logic
UPDATE `cloud`.`configuration`
SET value = CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', '{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team')
WHERE name = 'user.password.reset.mail.template'
  AND value IN (CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', 'http://{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team'), CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', '{{{domainUrl}}}{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team'));

-- Add management_server_details table to allow ManagementServer scope configs
CREATE TABLE IF NOT EXISTS `management_server_details` (
                                                           `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
                                                           `management_server_id` bigint unsigned NOT NULL COMMENT 'management server the detail is related to',
                                                           `name` varchar(255) NOT NULL COMMENT 'name of the detail',
    `value` varchar(255) NOT NULL,
    `display` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'True if the detail can be displayed to the end user',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_management_server_details__management_server_id` FOREIGN KEY `fk_management_server_details__management_server_id`(`management_server_id`) REFERENCES `mshost`(`id`) ON DELETE CASCADE,
    KEY `i_management_server_details__name__value` (`name`(128),`value`(128))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8;


-- Add checkpoint tracking fields to backups table for incremental backup support
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'from_checkpoint_id', 'VARCHAR(255) DEFAULT NULL COMMENT "Previous active checkpoint id for incremental backups"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'to_checkpoint_id', 'VARCHAR(255) DEFAULT NULL COMMENT "New checkpoint id created for the next incremental backup"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'checkpoint_create_time', 'BIGINT DEFAULT NULL COMMENT "Checkpoint creation timestamp from libvirt"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'host_id', 'BIGINT UNSIGNED DEFAULT NULL COMMENT "Host where backup is running"');

-- Create image_transfer table for per-disk image transfers
CREATE TABLE IF NOT EXISTS `cloud`.`image_transfer`(
    `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
    `uuid` varchar(40) NOT NULL COMMENT 'uuid',
    `account_id` bigint unsigned NOT NULL COMMENT 'Account ID',
    `domain_id` bigint unsigned NOT NULL COMMENT 'Domain ID',
    `data_center_id` bigint unsigned NOT NULL COMMENT 'Data Center ID',
    `backup_id` bigint unsigned COMMENT 'Backup ID',
    `volume_id` bigint unsigned NOT NULL COMMENT 'Disk/Volume ID',
    `host_id` bigint unsigned NOT NULL COMMENT 'Host ID',
    `transfer_url` varchar(255) COMMENT 'ImageIO transfer URL',
    `file` varchar(255) COMMENT 'File for the file backend',
    `phase` varchar(20) NOT NULL COMMENT 'Transfer phase: initializing, transferring, finished, failed',
    `socket` varchar(255) COMMENT 'Unix socket for nbd backend',
    `direction` varchar(20) NOT NULL COMMENT 'Direction: upload, download',
    `backend` varchar(20) NOT NULL COMMENT 'Backend: nbd, file',
    `progress` int COMMENT 'Transfer progress percentage (0-100)',
    `signed_ticket_id` varchar(255) COMMENT 'Signed ticket ID from ImageIO',
    `created` datetime NOT NULL COMMENT 'date created',
    `updated` datetime COMMENT 'date updated if not null',
    `removed` datetime COMMENT 'date removed if not null',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uuid` (`uuid`),
    CONSTRAINT `fk_image_transfer__backup_id` FOREIGN KEY (`backup_id`) REFERENCES `backups`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_image_transfer__volume_id` FOREIGN KEY (`volume_id`) REFERENCES `volumes`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_image_transfer__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
    INDEX `i_image_transfer__backup_id`(`backup_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8;
