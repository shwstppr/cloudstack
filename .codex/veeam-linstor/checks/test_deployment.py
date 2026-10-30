#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Deletion ownership and timeout regression checks; no live lab mutations."""
import contextlib
import io
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
import deployment


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        args = types.SimpleNamespace(mode='deploy', keep_vm=False, cycle=False,
                                     results_dir=self.temp.name, timeout=1)
        self.check = deployment.Check({'account': 'admin', 'validation_vm_id': 'retained',
                                       'hosts': {'host': 'root@host'}, 'pool_id': 'linstor'}, args)
        self.check.api = Mock()
        self.check.job = Mock()

    def test_cleanup_refuses_vm_not_owned_by_run(self):
        self.check.created_id = 'retained'
        self.check.vm = Mock(return_value={'name': 'linstor-validation', 'account': 'admin'})
        with self.assertRaisesRegex(RuntimeError, 'ownership mismatch'):
            self.check.cleanup()
        self.check.job.assert_not_called()

    def test_cleanup_refuses_pending_operation(self):
        self.check.created_id = 'new'
        self.check.pending = {'command': 'startVirtualMachine', 'jobid': 'pending'}
        with self.assertRaisesRegex(RuntimeError, 'Pending/ambiguous'):
            self.check.cleanup()
        self.check.api.assert_not_called()
        self.check.job.assert_not_called()

    def test_timeout_keeps_job_and_created_vm_identity(self):
        # Invoke the real job implementation rather than the cleanup spy.
        self.check.api.return_value = {'id': 'new', 'jobid': 'slow-job'}
        with patch.object(deployment.time, 'monotonic', side_effect=[0, 2]):
            with self.assertRaisesRegex(RuntimeError, 'timed out'):
                deployment.Check.job(self.check, 'deployVirtualMachine')
        self.assertEqual(self.check.created_id, 'new')
        self.assertEqual(self.check.pending['jobid'], 'slow-job')
        with self.assertRaisesRegex(RuntimeError, 'Pending/ambiguous'):
            self.check.cleanup()

    def test_health_failure_returns_nonzero_without_mutations(self):
        self.check.args.mode = 'health'
        self.check.infrastructure = Mock()
        self.check.verify_vm = Mock(side_effect=RuntimeError('Guest unavailable'))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.check.run(), 1)
        self.check.job.assert_not_called()
        self.assertEqual(self.check.report['status'], 'FAIL')

    def test_wrong_primary_pool_fails_before_guest_probe(self):
        self.check.vm = Mock(return_value={'state': 'Running', 'hostid': 'host'})
        self.check.api.return_value = {'volume': [{'type': 'ROOT', 'state': 'Ready', 'storageid': 'nfs'}]}
        self.check.remote = Mock()
        with self.assertRaisesRegex(RuntimeError, 'configured LINSTOR pool'):
            self.check.verify_vm('vm', 'test')
        self.check.remote.assert_not_called()


if __name__ == '__main__':
    unittest.main()
