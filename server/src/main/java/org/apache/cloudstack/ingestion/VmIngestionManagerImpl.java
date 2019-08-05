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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.admin.ingestion.ImportUnmanageInstanceCmd;
import org.apache.cloudstack.api.command.admin.ingestion.ListUnmanagedInstancesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetUnmanagedInstancesAnswer;
import com.cloud.agent.api.GetUnmanagedInstancesCommand;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;

public class VmIngestionManagerImpl implements VmIngestionService {
    private static final Logger LOGGER = Logger.getLogger(VmIngestionManagerImpl.class);

    @Inject
    private AgentManager agentManager;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private ResourceManager resourceManager;

    @Override
    public ListResponse<UnmanagedInstanceResponse> listUnmanagedInstances(ListUnmanagedInstancesCmd cmd) {
        final Long clusterId = cmd.getClusterId();
        if (clusterId == null) {
            throw  new InvalidParameterValueException(String.format("Cluster ID cannot be null!"));
        }
        final Cluster cluster = clusterDao.findById(clusterId);
        if (cluster == null) {
            throw  new InvalidParameterValueException(String.format("Cluster ID: %d cannot be found!", clusterId));
        }

        List<HostVO> hosts = resourceManager.listHostsInClusterByStatus(clusterId, Status.Up);

        HashMap<String, UnmanagedInstance> unmanagedInstances = new HashMap<>();
        for (HostVO host : hosts) {

            GetUnmanagedInstancesCommand command = new GetUnmanagedInstancesCommand();

            Answer answer = agentManager.easySend(host.getId(), command);

            if (answer instanceof GetUnmanagedInstancesAnswer){
                GetUnmanagedInstancesAnswer unmanagedInstancesAnswer = (GetUnmanagedInstancesAnswer)answer;
                unmanagedInstances.putAll(unmanagedInstancesAnswer.getUnmanagedInstances());
            }
        }

        Set<String> keys = unmanagedInstances.keySet();
        List<UnmanagedInstanceResponse> responses = new ArrayList<>();
        for (String key : keys) {
            UnmanagedInstanceResponse response = new UnmanagedInstanceResponse();
            UnmanagedInstance instance = unmanagedInstances.get(key);
            response.setName(instance.getName());
            responses.add(response);
        }
        ListResponse<UnmanagedInstanceResponse> listResponses = new ListResponse<>();
        listResponses.setResponses(responses);
        return listResponses;
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(ListUnmanagedInstancesCmd.class);
        cmdList.add(ImportUnmanageInstanceCmd.class);
        return cmdList;
    }
}
