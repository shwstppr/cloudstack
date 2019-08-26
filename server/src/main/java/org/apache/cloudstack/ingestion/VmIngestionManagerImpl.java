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

package org.apache.cloudstack.ingestion;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListDomainResourcesCmd;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.ingestion.ImportUnmanageInstanceCmd;
import org.apache.cloudstack.api.command.admin.ingestion.ListUnmanagedInstancesCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;
import org.apache.cloudstack.api.command.admin.vm.ListVMsCmdByAdmin;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NicResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceDiskResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.query.QueryService;
import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetUnmanagedInstancesAnswer;
import com.cloud.agent.api.GetUnmanagedInstancesCommand;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementService;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VirtualMachine;
import com.google.common.base.Strings;

public class VmIngestionManagerImpl implements VmIngestionService {
    private static final Logger LOGGER = Logger.getLogger(VmIngestionManagerImpl.class);

    @Inject
    private AgentManager agentManager;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private AccountService accountService;
    @Inject
    private UserDao userDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    public ManagementService managementService;
    @Inject
    public QueryService queryService;
    @Inject
    private ResourceManager resourceManager;
    @Inject
    private UserVmService userVmService;
    @Inject
    public ResponseGenerator responseGenerator;

    @Override
    public ListResponse<UnmanagedInstanceResponse> listUnmanagedInstances(ListUnmanagedInstancesCmd cmd) {
        final Long clusterId = cmd.getClusterId();
        if (clusterId == null) {
            throw new InvalidParameterValueException(String.format("Cluster ID cannot be null!"));
        }
        final Cluster cluster = clusterDao.findById(clusterId);
        if (cluster == null) {
            throw new InvalidParameterValueException(String.format("Cluster ID: %d cannot be found!", clusterId));
        }

        List<HostVO> hosts = resourceManager.listHostsInClusterByStatus(clusterId, Status.Up);

        List<UnmanagedInstanceResponse> responses = new ArrayList<>();
        for (HostVO host : hosts) {
            List<String> managedVms = new ArrayList<>();
            try {
                ListVMsCmdByAdmin vmsCmd = new ListVMsCmdByAdmin();
                vmsCmd = ComponentContext.inject(vmsCmd);
                Field hostField = vmsCmd.getClass().getDeclaredField("hostId");
                hostField.setAccessible(true);
                hostField.set(vmsCmd, host.getId());
                Field listAllField = BaseListDomainResourcesCmd.class.getDeclaredField("listAll");
                listAllField.setAccessible(true);
                listAllField.set(vmsCmd, true);
                ListResponse<UserVmResponse> vmsResponse = queryService.searchForUserVMs(vmsCmd);
                for (UserVmResponse vmResponse : vmsResponse.getResponses()) {
                    managedVms.add(vmResponse.getInstanceName());
                }
            } catch (Exception e) {
                LOGGER.warn(String.format("Unable to retrieve user vms for host ID: %s", host.getUuid()));
            }
            try {
                ListSystemVMsCmd vmsCmd = new ListSystemVMsCmd();
                vmsCmd = ComponentContext.inject(vmsCmd);
                Field hostField = vmsCmd.getClass().getDeclaredField("hostId");
                hostField.setAccessible(true);
                hostField.set(vmsCmd, host.getId());
                Pair<List<? extends VirtualMachine>, Integer> systemVMs = managementService.searchForSystemVm(vmsCmd);
                for (VirtualMachine systemVM : systemVMs.first()) {
                    managedVms.add(systemVM.getInstanceName());
                }
            } catch (Exception e) {
                LOGGER.warn(String.format("Unable to retrieve system vms for host ID: %s", host.getUuid()));
            }
            try {
                ListRoutersCmd vmsCmd = new ListRoutersCmd();
                vmsCmd = ComponentContext.inject(vmsCmd);
                Field hostField = vmsCmd.getClass().getDeclaredField("hostId");
                hostField.setAccessible(true);
                hostField.set(vmsCmd, host.getId());
                Field listAllField = BaseListDomainResourcesCmd.class.getDeclaredField("listAll");
                listAllField.setAccessible(true);
                listAllField.set(vmsCmd, true);
                ListResponse<DomainRouterResponse> routersResponse = queryService.searchForRouters(vmsCmd);
                for (DomainRouterResponse routerResponse : routersResponse.getResponses()) {
                    managedVms.add(routerResponse.getName());
                }
            } catch (Exception e) {
                LOGGER.warn(String.format("Unable to retrieve virtual router vms for host ID: %s", host.getUuid()));
            }
            GetUnmanagedInstancesCommand command = new GetUnmanagedInstancesCommand();
            command.setManagedInstancesNames(managedVms);
            Answer answer = agentManager.easySend(host.getId(), command);
            if (answer instanceof GetUnmanagedInstancesAnswer) {
                GetUnmanagedInstancesAnswer unmanagedInstancesAnswer = (GetUnmanagedInstancesAnswer) answer;
                HashMap<String, UnmanagedInstance> unmanagedInstances = new HashMap<>();
                unmanagedInstances.putAll(unmanagedInstancesAnswer.getUnmanagedInstances());
                Set<String> keys = unmanagedInstances.keySet();
                for (String key : keys) {
                    responses.add(createUnmanagedInstanceResponse(unmanagedInstances.get(key), cluster, host));
                }
            }
        }
        ListResponse<UnmanagedInstanceResponse> listResponses = new ListResponse<>();
        listResponses.setResponses(responses, responses.size());
        return listResponses;
    }

    @Override
    public UserVmResponse importUnmanagedInstance(ImportUnmanageInstanceCmd cmd) {
        final Long clusterId = cmd.getClusterId();
        if (clusterId == null) {
            throw new InvalidParameterValueException(String.format("Cluster ID cannot be null!"));
        }
        final Cluster cluster = clusterDao.findById(clusterId);
        if (cluster == null) {
            throw new InvalidParameterValueException(String.format("Cluster ID: %d cannot be found!", clusterId));
        }
        final DataCenter zone = dataCenterDao.findById(cluster.getDataCenterId());
        final String instanceName = cmd.getName();
        if (Strings.isNullOrEmpty(instanceName)) {
            throw new InvalidParameterValueException(String.format("Instance name cannot be empty!"));
        }
        final Account caller = CallContext.current().getCallingAccount();
        final Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());

        Long userId = null;
        List<UserVO> userVOs = userDao.listByAccount(owner.getAccountId());
        if (!userVOs.isEmpty()) {
            userId =  userVOs.get(0).getId();
        }
        final Long templateId = cmd.getTemplateId();
        if (templateId == null) {
            throw new InvalidParameterValueException(String.format("Template ID cannot be null!"));
        }
        final VirtualMachineTemplate template = templateDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException(String.format("Template ID: %d cannot be found!", templateId));
        }
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        if (serviceOfferingId == null) {
            throw new InvalidParameterValueException(String.format("Service offering ID cannot be null!"));
        }
        final ServiceOffering serviceOffering = serviceOfferingDao.findById(templateId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException(String.format("Service offering ID: %d cannot be found!", serviceOfferingId));
        }
        final Long diskOfferingId = cmd.getDiskOfferingId();
        if (diskOfferingId == null) {
            throw new InvalidParameterValueException(String.format("Service offering ID cannot be null!"));
        }
        final DiskOffering diskOffering = diskOfferingDao.findById(diskOfferingId);
        if (diskOffering == null) {
            throw new InvalidParameterValueException(String.format("Disk offering ID: %d cannot be found!", diskOfferingId));
        }

        // TODO
        final Map networkNicProfileMap = cmd.getNicNetworkList();
        final Map dataDiskOfferingMap = cmd.getDataDiskToDiskOfferingList();

        List<HostVO> hosts = resourceManager.listHostsInClusterByStatus(clusterId, Status.Up);

        UserVm userVm = null;

        for (HostVO host : hosts) {
            GetUnmanagedInstancesCommand command = new GetUnmanagedInstancesCommand();
            command.setInstanceName(instanceName);
            Answer answer = agentManager.easySend(host.getId(), command);
            if (answer instanceof GetUnmanagedInstancesAnswer) {
                GetUnmanagedInstancesAnswer unmanagedInstancesAnswer = (GetUnmanagedInstancesAnswer) answer;
                HashMap<String, UnmanagedInstance> unmanagedInstances = unmanagedInstancesAnswer.getUnmanagedInstances();
                if (unmanagedInstances != null && !unmanagedInstances.isEmpty()) {
                    Set<String> names = unmanagedInstances.keySet();
                    for (String name : names) {
                        if (name.equals(instanceName)) {
                            UnmanagedInstance unmanagedInstance = unmanagedInstances.get(name);
                            if (unmanagedInstance.getDisks() == null || unmanagedInstance.getDisks().isEmpty()) {
                                throw new InvalidParameterValueException(String.format("No attached disks found for the unmanaged VM: %s", name));
                            }
                            final UnmanagedInstance.Disk rootDisk = unmanagedInstance.getDisks().get(0);
                            final long rootDiskSize = diskOffering.isCustomized() ? rootDisk.getCapacity() : (diskOffering.getDiskSize()/(1024*1024));
                            try {
                                userVm = userVmService.ingestVm(zone, template, instanceName, instanceName, owner, diskOfferingId, rootDiskSize,
                                        null, caller, true, null, owner.getAccountId(), userId, serviceOffering, null, null, instanceName, cluster.getHypervisorType(),
                                        new HashMap<>(), new HashMap<>());
                            } catch (InsufficientCapacityException ice) {
                                throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ice.getMessage());
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }
        if (userVm == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to ingest vm name: %s", instanceName));
        }
        UserVmResponse response = responseGenerator.createUserVmResponse(ResponseObject.ResponseView.Full, "virtualmachine", userVm).get(0);
        return response;
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(ListUnmanagedInstancesCmd.class);
        cmdList.add(ImportUnmanageInstanceCmd.class);
        return cmdList;
    }

    private UnmanagedInstanceResponse createUnmanagedInstanceResponse(UnmanagedInstance instance, Cluster cluster, Host host) {
        UnmanagedInstanceResponse response = new UnmanagedInstanceResponse();
        response.setName(instance.getName());
        if (cluster != null) {
            response.setClusterId(cluster.getUuid());
        }
        if (host != null) {
            response.setHostId(host.getUuid());
        }
        response.setPowerState(instance.getPowerState());
        response.setCpuCores(instance.getCpuCores());
        response.setCpuSpeed(instance.getCpuSpeed());
        response.setCpuCoresPerSocket(instance.getCpuCoresPerSocket());
        response.setMemory(instance.getMemory());
        response.setOperatingSystem(instance.getOperatingSystem());
        response.setObjectName(UnmanagedInstance.class.getSimpleName().toLowerCase());

        if (instance.getDisks() != null) {
            for (UnmanagedInstance.Disk disk : instance.getDisks()) {
                UnmanagedInstanceDiskResponse diskResponse = new UnmanagedInstanceDiskResponse();
                diskResponse.setDiskId(disk.getDiskId());
                diskResponse.setCapacity(disk.getCapacity());
                diskResponse.setController(disk.getController());
                diskResponse.setControllerUnit(disk.getControllerUnit());
                diskResponse.setPosition(disk.getPosition());
                diskResponse.setImagePath(disk.getImagePath());
                response.addDisk(diskResponse);
            }
        }

        if (instance.getNics() != null) {
            for (UnmanagedInstance.Nic nic : instance.getNics()) {
                NicResponse nicResponse = new NicResponse();
                nicResponse.setId(nic.getNicId());
                nicResponse.setNetworkName(nic.getNetwork());
                nicResponse.setMacAddress(nic.getMacAddress());
                //nicResponse.setIpaddress(nic.getIpAddress());
                nicResponse.setVlanId(nic.getVlan());
                response.addNic(nicResponse);
            }
        }
        return response;
    }
}