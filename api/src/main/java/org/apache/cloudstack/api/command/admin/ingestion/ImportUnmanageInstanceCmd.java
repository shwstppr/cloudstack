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

package org.apache.cloudstack.api.command.admin.ingestion;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ImportUnmanagedInstanceResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.ingestion.VmIngestionService;
import org.apache.log4j.Logger;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;

@APICommand(name = "importUnmanagedInstances",
        description = "Import unmanaged virtual machine from a given cluster/host.",
        responseObject = ImportUnmanagedInstanceResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true)
public class ImportUnmanageInstanceCmd extends BaseAsyncCmd implements AdminCmd {
    public static final Logger s_logger = Logger.getLogger(ImportUnmanageInstanceCmd.class.getName());

    @Inject
    public VmIngestionService vmIngestionService;

    @Parameter(name = ApiConstants.CLUSTER_ID,
            type = CommandType.UUID,
            entityType = ClusterResponse.class,
            description = "the cluster ID")
    private Long clusterId;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.UUID,
            description = "the hypervisor name of the instance")
    private Long name;

    @Parameter(name = ApiConstants.DOMAIN_ID,
            type = CommandType.UUID,
            entityType = DomainResponse.class,
            description = "import instance to the domain specified")
    private Long domainId;

    @Parameter(name = ApiConstants.PROJECT_ID,
            type = CommandType.UUID,
            entityType = ProjectResponse.class,
            description = "import instance for the project")
    private Long projectId;

    @ACL
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID,
            type = CommandType.UUID,
            entityType = ServiceOfferingResponse.class,
            required = true,
            description = "the ID of the service offering for the virtual machine")
    private Long serviceOfferingId;

    @Parameter(name = ApiConstants.NETWORK_ID,
            type = CommandType.UUID,
            entityType = NetworkResponse.class,
            description = "network id used by virtual machine")
    private List<Long> networkIds;

    @Parameter(name = ApiConstants.DATADISK_OFFERING_LIST,
            type = CommandType.MAP,
            description = "datadisk template to disk-offering mapping")
    private Map dataDiskToDiskOfferingList;

    @Parameter(name = ApiConstants.DETAILS,
            type = CommandType.MAP,
            description = "used to specify the custom parameters.")
    private Map details;

    public Long getClusterId() {
        return clusterId;
    }

    public Long getName() {
        return name;
    }

    public Long getDomainId() {
        return domainId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public List<Long> getNetworkIds() {
        return networkIds;
    }

    public Map getDataDiskToDiskOfferingList() {
        return dataDiskToDiskOfferingList;
    }

    public Map getDetails() {
        Map<String, String> customParameterMap = new HashMap<String, String>();
        if (details != null && details.size() != 0) {
            Collection parameterCollection = details.values();
            Iterator iter = parameterCollection.iterator();
            while (iter.hasNext()) {
                HashMap<String, String> value = (HashMap<String, String>)iter.next();
                for (Map.Entry<String,String> entry: value.entrySet()) {
                    customParameterMap.put(entry.getKey(),entry.getValue());
                }
            }
        }
        return customParameterMap;
    }

    @Override
    public String getEventType() {
        return null;
    }

    @Override
    public String getEventDescription() {
        return null;
    }

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {

    }

    @Override
    public String getCommandName() {
        return null;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public ResponseObject.ResponseView getResponseView() {
        return null;
    }
}
