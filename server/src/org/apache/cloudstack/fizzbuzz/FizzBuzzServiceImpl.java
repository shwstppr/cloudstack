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
package org.apache.cloudstack.fizzbuzz;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.user.fizzbuzz.FizzBuzzCmd;

import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.utils.Pair;

public class FizzBuzzServiceImpl extends ManagerBase implements FizzBuzzService, PluggableService {

    @Inject
    private UserVmJoinDao _userVmJoinDao;

    @Override
    public String fizzBuzz(Integer number) {
        String result = "";
        if (number!=0) {
            if (number%(3*5)==0)
                result = "fizzbuzz";
            else if (number%3==0)
                result = "fizz";
            else if (number%5==0)
                result = "buzz";
        }
        if (result.length()==0)
            result = String.valueOf((new Random()).nextInt(100-1)+1);
        return result;
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(FizzBuzzCmd.class);
        return cmdList;
    }

    @Override
    public Integer getVMCount() {
        Filter searchFilter = new Filter(UserVmJoinVO.class, "id", true, 0L, null);
        SearchBuilder<UserVmJoinVO> sb = _userVmJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct ids
        SearchCriteria<UserVmJoinVO> sc = sb.create();
        Pair<List<UserVmJoinVO>, Integer> uniqueVmPair = _userVmJoinDao.searchAndDistinctCount(sc, searchFilter);
        return uniqueVmPair.second();
    }
}
