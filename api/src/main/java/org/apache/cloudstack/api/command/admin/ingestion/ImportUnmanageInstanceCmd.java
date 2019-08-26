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
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.ingestion.VmIngestionService;
import org.apache.log4j.Logger;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.user.Account;

@APICommand(name = ImportUnmanageInstanceCmd.API_NAME,
        description = "Import unmanaged virtual machine from a given cluster/host.",
        responseObject = UserVmResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true)
public class ImportUnmanageInstanceCmd extends BaseAsyncCmd {
    public static final Logger s_logger = Logger.getLogger(ImportUnmanageInstanceCmd.class.getName());
    public static final String API_NAME = "importUnmanagedInstance";

    @Inject
    public VmIngestionService vmIngestionService;

    @Parameter(name = ApiConstants.CLUSTER_ID,
            type = CommandType.UUID,
            entityType = ClusterResponse.class,
            required = true,
            description = "the cluster ID")
    private Long clusterId;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            required = true,
            description = "the hypervisor name of the instance")
    private String name;

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
    @Parameter(name = ApiConstants.TEMPLATE_ID,
            type = CommandType.UUID,
            entityType = TemplateResponse.class,
            required = true,
            description = "the ID of the template for the virtual machine")
    private Long templateId;

    @ACL
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID,
            type = CommandType.UUID,
            entityType = ServiceOfferingResponse.class,
            required = true,
            description = "the ID of the service offering for the virtual machine")
    private Long serviceOfferingId;

    @ACL
    @Parameter(name = ApiConstants.DISK_OFFERING_ID,
            type = CommandType.UUID,
            entityType = DiskOfferingResponse.class,
            required = true,
            description = "the ID of the root disk offering for the virtual machine")
    private Long diskOfferingId;

    @Parameter(name = "nicNetworkList",
            type = CommandType.MAP,
            description = "VM nic to network id mapping")
    private Map nicNetworkList;

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

    public String getName() {
        return name;
    }

    public Long getDomainId() {
        return domainId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Long getDiskOfferingId() {
        return diskOfferingId;
    }

    public Map getNicNetworkList() {
        return nicNetworkList;
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
        return EventTypes.EVENT_VM_INGEST;
    }

    @Override
    public String getEventDescription() {
        return "Importing unmanaged VM";
    }

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {
        validateInput();
        UserVmResponse response = vmIngestionService.importUnmanagedInstance(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return API_NAME.toLowerCase() + BaseAsyncCmd.RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        Account account = CallContext.current().getCallingAccount();
        if (account != null) {
            return account.getId();
        }
        return Account.ACCOUNT_ID_SYSTEM;
    }

    private void validateInput() {
        if (_entityMgr.findById(Cluster.class, clusterId) == null) {
            throw new InvalidParameterValueException(String.format("Unable to find cluster with ID: %d", clusterId));
        }
        if (_entityMgr.findById(ServiceOffering.class, serviceOfferingId) == null) {
            throw new InvalidParameterValueException(String.format("Unable to find service offering with ID: %d", serviceOfferingId));
        }
    }
}