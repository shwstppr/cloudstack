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

import org.apache.cloudstack.mom.webhook.WebhookRule;
import org.apache.cloudstack.mom.webhook.vo.WebhookRuleVO;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class WebhookRuleDaoImpl extends GenericDaoBase<WebhookRuleVO, Long> implements WebhookRuleDao {
    @Override
    public List<WebhookRuleVO> listByEnabledRulesForDispatch(Long accountId, List<Long> domainIds) {
        SearchBuilder<WebhookRuleVO> sb = createSearchBuilder();
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and().op("scopeGlobal", sb.entity().getScope(), SearchCriteria.Op.EQ);
        if (accountId != null) {
            sb.or().op("scopeLocal", sb.entity().getScope(), SearchCriteria.Op.EQ);
            sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
            sb.cp();
        }
        if (CollectionUtils.isNotEmpty(domainIds)) {
            sb.or().op("scopeDomain", sb.entity().getScope(), SearchCriteria.Op.EQ);
            sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.IN);
            sb.cp();
        }
        sb.cp();
        SearchCriteria<WebhookRuleVO> sc = sb.create();
        sc.setParameters("state", WebhookRule.State.Enabled.name());
        sc.setParameters("scopeGlobal", WebhookRule.Scope.Global.name());
        if (accountId != null) {
            sc.setParameters("scopeLocal", WebhookRule.Scope.Local.name());
            sc.setParameters("accountId", accountId);
        }
        if (CollectionUtils.isNotEmpty(domainIds)) {
            sc.setParameters("scopeDomain", WebhookRule.Scope.Domain.name());
            sc.setParameters("domainId", domainIds.toArray());
        }
        return listBy(sc);
    }
}
