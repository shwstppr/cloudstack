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

package org.apache.cloudstack.api.command.user.fizzbuzz;

import java.util.Random;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.FizzBuzzResponse;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = FizzBuzzCmd.APINAME, description = "FizzBuzz example in CloudStack", responseObject = FizzBuzzResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.11", authorized = {RoleType.User})
public class FizzBuzzCmd extends BaseCmd {
    public static final String APINAME = "fizzBuzz";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = "number", type = CommandType.INTEGER, description = "FizzBuzz input number")
    private Integer number = 0;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Integer getNumber() {
        return number;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + BaseCmd.RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public void execute() {
        FizzBuzzResponse response = new FizzBuzzResponse(getCommandName());
        String result = "";
        if (getNumber()  > 0) {
            if(getNumber()%(3*5) == 0)
               result = "FizzBuzz";
            else if(getNumber()%3 == 0)
               result = "Fizz";
            else if(getNumber()%5 == 0)
               result = "Buzz";
        }
        if (result.length() == 0)
            result = String.valueOf((new Random()).nextInt(100-1)+1);
        response.setAnswer(result);
        setResponseObject(response);
    }
}
