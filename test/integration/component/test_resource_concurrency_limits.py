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
""" BVT tests for checking resource limits with concurrent creation in CloudStack
"""
# Import Local Modules
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (updateResourceLimit)
from marvin.lib.base import (Account,
                             Domain,
                             Project,
                             NetworkOffering,
                             Network,
                             VpcOffering,
                             VPC,
                             PublicIPAddress,
                             DiskOffering,
                             Volume,
                             Snapshot,
                             Template,
                             ServiceOffering,
                             VirtualMachine)
from marvin.lib.common import (get_domain,
                               get_zone,
                               get_template)
from marvin.lib.utils import (random_gen)
from marvin.cloudstackException import CloudstackAPIException
from nose.plugins.attrib import attr
# Import System modules
import threading
import time


_multiprocess_shared_ = True
# Resource types requested (skip primary/secondary storage for now)
VM_CPU = 1
VM_MEMORY = 128
VOLUME_SIZE = 2

def _looks_like_limit_error(e):
    msg = str(e).lower()
    tokens = [
        "resource limit",
        "would exceed",
        "exceed",
        "limit",
        "max.",
    ]
    return any(t in msg for t in tokens)


class _Result(object):
    def __init__(self, ok, obj=None, err=None):
        self.ok = ok
        self.obj = obj
        self.err = err

class TestResourceConcurrencyLimits(cloudstackTestCase):

    @classmethod
    def setUpClass(cls):
        testClient = super(TestResourceConcurrencyLimits, cls).getClsTestClient()
        cls.apiclient = testClient.getApiClient()
        cls.services = testClient.getParsedTestDataConfig()

        # Get Zone, Domain and templates
        cls.root_domain = get_domain(cls.apiclient)
        cls.zone = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.hypervisor = cls.testClient.getHypervisorInfo()

        cls.template = get_template(
            cls.apiclient,
            cls.zone.id,
            cls.services["ostype"]
        )

        cls._cleanup = []

        # Offerings used by resource creators
        cls.network_offering = NetworkOffering.create(
            cls.apiclient,
            cls.services["network_offering"]
        )
        cls._cleanup.append(cls.network_offering)
        cls.network_offering.update(cls.apiclient, state='Enabled')
        cls.vpc_offering = VpcOffering.create(
            cls.apiclient,
            cls.services["vpc_offering"]
        )
        cls._cleanup.append(cls.vpc_offering)
        cls.vpc_offering.update(cls.apiclient, state='Enabled')

        so_data = dict(cls.services["service_offering"])
        so_data["cpunumber"] = VM_CPU
        so_data["memory"] = VM_MEMORY
        cls.small_service_offering = ServiceOffering.create(cls.apiclient, so_data)
        cls._cleanup.append(cls.small_service_offering)

        cls.services["disk_offering"]["disksize"] = VOLUME_SIZE
        cls.disk_offering = DiskOffering.create(
            cls.apiclient, cls.services["disk_offering"]
        )
        cls._cleanup.append(cls.disk_offering)

    @classmethod
    def tearDownClass(cls):
        super(TestResourceConcurrencyLimits, cls).tearDownClass()

    def setUp(self):
        self.cleanup = []
        # Create isolated domain + account inside it
        self.test_domain = Domain.create(
            self.apiclient,
            self.services["domain"],
            parentdomainid=self.root_domain.id
        )
        self.cleanup.append(self.test_domain)
        self.account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=False,
            domainid=self.test_domain.id
        )
        self.cleanup.append(self.account)
        self.userapiclient = self.testClient.getUserApiClient(
            UserName=self.account.name,
            DomainName=self.account.domain
        )
        self.limit = 2
        self.burst = 3
        self.attempts = self.limit + self.burst
        self.volumes = {}
        self.detach_vm_volumes = {}

        self.resource_types = {
            "user_vm": {
                "resourcetype": 0,
                "fn": lambda i: self.create_vm(i)
            },
            "public_ip": {
                "resourcetype": 1,
                "fn": lambda i: self.create_public_ip(i)
            },
            "volume": {
                "resourcetype": 2,
                "fn": lambda i: self.create_volume(i)
            },
            "snapshot": {
                "resourcetype": 3,
                "fn": lambda i: self.create_snapshot(i)
            },
            "template": {
                "resourcetype": 4,
                "fn": lambda i: self.create_template(i)
            },
            "project": {
                "resourcetype": 5,
                "fn": lambda i: self.create_project(i)
            },
            "network": {
                "resourcetype": 6,
                "fn": lambda i: self.create_network(i)
            },
            "vpc": {
                "resourcetype": 7,
                "fn": lambda i: self.create_vpc(i)
            },
            "cpu": {
                "resourcetype": 8,
                "fn": lambda i: self.create_vm(i)
            },
            "memory": {
                "resourcetype": 9,
                "fn": lambda i: self.create_vm(i)
            },
            "primary_storage": {
                "resourcetype": 10,
                "fn": lambda i: self.create_volume(i)
            }
        }

    def tearDown(self):
        for vm, vol in self.detach_vm_volumes.items():
            try:
                vm.detach_volume(self.userapiclient, vol)
            except CloudstackAPIException as e:
                print("Warning: failed to detach volume %s from VM %s: %s"
                      % (vol.id, vm.id, str(e)))
        super(TestResourceConcurrencyLimits, self).tearDown()

    # ----------------------------
    # Helpers: limits + concurrency
    # ----------------------------

    def set_account_limit(self, resource_id, max_value):
        cmd = updateResourceLimit.updateResourceLimitCmd()
        cmd.account = self.account.name
        cmd.domainid = self.account.domainid
        cmd.resourcetype = resource_id
        cmd.max = max_value
        self.apiclient.updateResourceLimit(cmd)

    def run_concurrent(self, total_attempts, fn):
        """
        Run fn(i) concurrently with a barrier start.
        Returns list of _Result in order.
        """
        barrier = threading.Barrier(total_attempts)
        results = [None] * total_attempts
        threads = []

        def worker(i):
            try:
                barrier.wait()
                obj = fn(i)
                results[i] = _Result(ok=True, obj=obj)
            except Exception as e:
                results[i] = _Result(ok=False, err=e)

        for i in range(total_attempts):
            t = threading.Thread(target=worker, args=(i,))
            t.daemon = True
            threads.append(t)

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        return results

    def assert_limit_enforced(self, results, expected_successes):
        successes = []
        failures = []
        for i, r in enumerate(results):
            if r.ok:
                successes.append(r)
            else:
                failures.append(r)
            id = None
            if r and r.obj:
                id = r.obj.__class__.__name__ + '-' + (getattr(r.obj, 'id', None) or getattr(getattr(r.obj, 'ipaddress', None), 'id', None))
            print("%d:: result ok=%s obj=%s err=%s" % (
                i,
                r.ok if r else "None",
                id,
                str(r.err) if r and r.err else "None"
            ))

        self.assertEqual(
            len(successes),
            expected_successes,
            "Expected %d successes, got %d; failures=%d"
            % (expected_successes, len(successes), len(failures))
        )
        self.assertGreaterEqual(
            len(failures), 1,
            "Expected at least one failure due to limit exceeded"
        )

        non_limit = [f for f in failures if not _looks_like_limit_error(f.err)]
        self.assertEqual(
            len(non_limit),
            0,
            "Some failures did not look like limit failures: %s"
            % [str(x.err) for x in non_limit]
        )

    # ----------------------------
    # Resource creators (per type)
    # ----------------------------

    def create_vm(self, idx, offering=None):
        networkids = None
        if hasattr(self, "network"):
            networkids = [self.network.id]
        return VirtualMachine.create(
            self.userapiclient,
            self.services["virtual_machine"],
            templateid=self.template.id,
            zoneid=self.zone.id,
            serviceofferingid=(offering or self.small_service_offering).id,
            accountid=self.account.name,
            domainid=self.account.domainid,
            networkids=networkids
        )

    def create_public_ip(self, idx):
        ip = PublicIPAddress.create(
            self.userapiclient,
            accountid=self.account.name,
            zoneid=self.zone.id,
            networkid=self.network.id,
            domainid=self.account.domainid
        )
        return ip

    def create_volume(self, idx):
        vol = Volume.create(
            self.userapiclient,
            self.services["volume"],
            diskofferingid=self.disk_offering.id,
            zoneid=self.zone.id,
            account=self.account.name,
            domainid=self.account.domainid
        )
        return vol

    def create_snapshot_volume(self, idx):
        snap_vm = self.create_vm(idx)
        self.cleanup.append(snap_vm)
        vol = self.create_volume(idx)
        self.cleanup.append(vol)
        self.volumes[idx] = vol
        snap_vm.attach_volume(self.userapiclient, vol)
        self.detach_vm_volumes[snap_vm] = vol
        return vol

    def create_snapshot(self, idx):
        """
        Snapshot requires a data volume attached to a running VM.
        """
        if hasattr(self, "volumes") and idx < len(self.volumes):
            vol = self.volumes[idx]
        else:
            vol = self.create_snapshot_volume(idx)

        # Snapshot
        snap = Snapshot.create(
            self.userapiclient,
            volume_id=vol.id
        )
        return snap

    def create_template(self, idx):
        """
        Template creation depends on snapshot, which depends on attached data volume.
        """

        hypervisor = self.hypervisor.lower()
        template_service = self.services["test_templates"][
            hypervisor if hypervisor != 'simulator' else 'xenserver'].copy()

        tmpl = Template.register(
            self.userapiclient,
            template_service,
            zoneid=self.zone.id,
            hypervisor=hypervisor
        )
        return tmpl

    def create_project(self, idx):
        prj = Project.create(
            self.userapiclient,
            self.services["project"],
            account=self.account.name,
            domainid=self.account.domainid
        )
        return prj

    def create_network(self, idx):
        net = Network.create(
            self.userapiclient,
            self.services["network"],
            accountid=self.account.name,
            domainid=self.account.domainid,
            networkofferingid=self.network_offering.id,
            zoneid=self.zone.id
        )
        return net

    def create_vpc(self, idx):
        vpc = VPC.create(
            self.userapiclient,
            self.services["vpc"],
            vpcofferingid=self.vpc_offering.id,
            zoneid=self.zone.id,
            account=self.account.name,
            domainid=self.account.domainid
        )
        return vpc

    # -------------------------------------
    # Helper to create resources and verify
    # -------------------------------------

    def create_resources_and_verify(self, resource_type, max_limit=None):
        if resource_type in ("user_vm", "cpu", "memory", "public_ip", "snapshot"):
            self.network = self.create_network(0)
            self.cleanup.append(self.network)
        # To make network Ready
        if resource_type in ("public_ip"):
            self.vm = self.create_vm(0)
            self.cleanup.append(self.vm)

        if resource_type == "snapshot":
            # Pre-create `self.attempts` VMs each with an attached data volume.
            for i in range(self.attempts):
                self.run_concurrent(self.attempts, lambda i: self.create_snapshot_volume(i))

        if max_limit is None:
            max_limit = self.limit

        self.set_account_limit(self.resource_types[resource_type]["resourcetype"], max_limit)
        time.sleep(1)
        self.fn = self.resource_types[resource_type]["fn"]
        # Run concurrent attempts
        results = self.run_concurrent(self.attempts, self.fn)

        # Track created objects for cleanup (only successes have obj)
        for r in results:
            if r and r.ok and r.obj is not None:
                self.cleanup.append(r.obj)

        # Assert expected successes = 2
        self.assert_limit_enforced(results, expected_successes=2)

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_01_concurrentcreate_vm_respect_account_limits(self):
        self.create_resources_and_verify("user_vm")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_02_concurrentcreate_public_ip_respect_account_limits(self):
        self.create_resources_and_verify("public_ip", self.limit + 1)

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_03_concurrentcreate_volume_respect_account_limits(self):
        self.create_resources_and_verify("volume")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_04_concurrentcreate_snapshot_respect_account_limits(self):
        self.create_resources_and_verify("snapshot")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_05_concurrentcreate_template_respect_account_limits(self):
        self.create_resources_and_verify("template")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_06_concurrent_create_project_respect_account_limits(self):
        self.create_resources_and_verify("project")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_07_concurrentcreate_network_respect_account_limits(self):
        self.create_resources_and_verify("network")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_08_concurrentcreate_vpc_respect_account_limits(self):
        self.create_resources_and_verify("vpc")

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_09_concurrentcreate_vm_cpu_respect_account_limits(self):
        self.create_resources_and_verify("cpu", self.limit * VM_CPU)

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_10_concurrentcreate_vm_memory_respect_account_limits(self):
        self.create_resources_and_verify("memory", self.limit * VM_MEMORY)

    @attr(tags=["devcloud", "advanced", "advancedns", "smoke", "basic", "sg"], required_hardware="false")
    def test_10_concurrentcreate_volume_primary_storage_respect_account_limits(self):
        self.create_resources_and_verify("primary_storage", self.limit * VOLUME_SIZE)
