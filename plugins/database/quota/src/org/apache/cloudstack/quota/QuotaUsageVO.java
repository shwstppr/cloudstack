//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//with the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.quota;

import java.math.BigDecimal;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

@Entity
@Table(name = "quota_usage")
public class QuotaUsageVO implements InternalIdentity {

    private static final long serialVersionUID = -7117933845287204781L;

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "usage_item_id")
    private Long usageItemId;

    @Column(name = "usage_type")
    private String usageType;

    @Column(name = "quota_used")
    private BigDecimal quotaUsed;

    @Column(name = "start_date")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date startDate = null;

    @Column(name = "end_date")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date endDate = null;

    public QuotaUsageVO() {
    }

    public QuotaUsageVO(Long usageItemId, String usageType,
            BigDecimal quotaUsed, Date startDate, Date endDate) {
        super();
        this.usageItemId = usageItemId;
        this.usageType = usageType;
        this.quotaUsed = quotaUsed;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    @Override
    public long getId() {
        // TODO Auto-generated method stub
        return id;
    }

    public Long getUsageItemId() {
        return usageItemId;
    }

    public void setUsageItemId(Long usageItemId) {
        this.usageItemId = usageItemId;
    }

    public String getUsageType() {
        return usageType;
    }

    public void setUsageType(String usageType) {
        this.usageType = usageType;
    }

    public BigDecimal getQuotaUsed() {
        return quotaUsed;
    }

    public void setQuotaUsed(BigDecimal quotaUsed) {
        this.quotaUsed = quotaUsed;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public void setId(Long id) {
        this.id = id;
    }

}