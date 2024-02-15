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

-- VIEW `cloud`.`webhook_dispatch_view`;

DROP VIEW IF EXISTS `cloud`.`webhook_dispatch_view`;
CREATE VIEW `cloud`.`webhook_dispatch_view` AS
    SELECT
        webhook_dispatch.id,
        webhook_dispatch.uuid,
        webhook_dispatch.payload,
        webhook_dispatch.success,
        webhook_dispatch.response,
        webhook_dispatch.start_time,
        webhook_dispatch.end_time,
        event.id event_id,
        event.uuid event_uuid,
        event.type event_type,
        webhook.id webhook_id,
        webhook.uuid webhook_uuid,
        webhook.name webhook_name,
        mshost.id mshost_id,
        mshost.uuid mshost_uuid,
        mshost.msid mshost_msid,
        mshost.name mshost_name
    FROM
        `cloud`.`webhook_dispatch`
            INNER JOIN
        `cloud`.`event` ON webhook_dispatch.event_id = event.id
            INNER JOIN
        `cloud`.`webhook` ON webhook_dispatch.webhook_id = webhook.id
            LEFT JOIN
        `cloud`.`mshost` ON mshost.msid = webhook_dispatch.mshost_msid;