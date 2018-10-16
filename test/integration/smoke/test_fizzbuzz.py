# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from marvin.cloudstackAPI import *
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.utils import cleanup_resources
from nose.plugins.attrib import attr


class TestData(object):
    """Test data object that is required to create resources
    """
    def __init__(self):
        self.testdata = [ 3, 5, 15, 50, '', 4 ]


class TestFizzBuzz(cloudstackTestCase):
    """Tests FizzBuzz API in CloudStack
    """

    def setUp(self):
        self.apiclient = self.testClient.getApiClient()
        self.testdata = TestData().testdata
        self.cleanup = []

    def tearDown(self):
        try:
           cleanup_resources(self.apiclient, self.cleanup)
        except Exception as e:
            self.debug("Warning! Exception in tearDown: %s" % e)

    def verifyResponse(self, number, response):
        is_correct_response = True
        if number%(3*5) == 0 :
            if response != "fizzbuzz" :
                is_correct_response = False
        elif number%3 == 0 :
            if response != "fizz" :
                is_correct_response = False
        elif number%5 == 0 :
            if response != "buzz" :
                is_correct_response = False
        else :
            if response.isdigit() == False :
                is_correct_response = False
        return is_correct_response

    @attr(tags=['advanced'], required_hardware=False)
    def test_fizzbuzz(self):
        """
            Test to check fizzBuzz API and validate response
        """
        testdata = self.testdata
        for number in testdata:
            cmd = fizzBuzz.fizzBuzzCmd()
            try:
                number = int(number)
                cmd.number = number
            except ValueError:
                self.debug("Check for no FizzBuzz input")
            fizzbuzz_response = self.apiclient.fizzBuzz(cmd)

            try:
                result = str(fizzbuzz_response.answer)
                result = result.lower()
            except Exception as e:
                self.fail("Invalid API response")

            is_valid_response = False

            try:
                number = int(number)
                is_valid_response = self.verifyResponse(number, result)
            except ValueError:
                # Check fo guest VM Count
                list_vm_response = self.apiclient.listVirtualMachines(listVirtualMachines.listVirtualMachinesCmd())
                vm_count = len(list_vm_response)
                is_valid_response = self.verifyResponse(vm_count, result)

            if is_valid_response != True:
                self.fail("Wrong FizzBuzz Response! Number: "+str(number)+", Response: "+result)
        return

