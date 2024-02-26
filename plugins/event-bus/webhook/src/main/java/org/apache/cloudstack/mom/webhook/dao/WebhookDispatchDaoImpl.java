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

package org.apache.cloudstack.mom.webhook.dao;

import java.util.List;

import org.apache.cloudstack.mom.webhook.vo.WebhookDispatchVO;

import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class WebhookDispatchDaoImpl extends GenericDaoBase<WebhookDispatchVO, Long> implements WebhookDispatchDao {
    @Override
    public int deleteByIdWebhookRuleManagementServer(Long id, Long webhookRuleId, Long managementServerId) {
        SearchBuilder<WebhookDispatchVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("webhookRuleId", sb.entity().getWebhookRuleId(), SearchCriteria.Op.EQ);
        sb.and("managementServerId", sb.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getPayload(), SearchCriteria.Op.LIKE);
        SearchCriteria<WebhookDispatchVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (webhookRuleId != null) {
            sc.setParameters("webhookRuleId", webhookRuleId);
        }
        if (managementServerId != null) {
            sc.setParameters("managementServerId", managementServerId);
        }
        return remove(sc);
    }

    @Override
    public void removeOlderDispatches(long webhookId, long limit) {
        Filter searchFilter = new Filter(WebhookDispatchVO.class, "id", false, 0L, limit);
        SearchBuilder<WebhookDispatchVO> sb = createSearchBuilder();
        sb.and("webhookRuleId", sb.entity().getWebhookRuleId(), SearchCriteria.Op.EQ);
        SearchCriteria<WebhookDispatchVO> sc = sb.create();
        sc.setParameters("webhookRuleId", webhookId);
        List<WebhookDispatchVO> keep = listBy(sc, searchFilter);
        SearchBuilder<WebhookDispatchVO> sbDelete = createSearchBuilder();
        sbDelete.and("id", sbDelete.entity().getId(), SearchCriteria.Op.NOTIN);
        SearchCriteria<WebhookDispatchVO> scDelete = sbDelete.create();
        scDelete.setParameters("id", keep.stream().map(WebhookDispatchVO::getId).toArray());
        remove(scDelete);
    }
}
