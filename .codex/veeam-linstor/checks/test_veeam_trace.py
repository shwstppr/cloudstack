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
import json
import sys
import unittest

sys.dont_write_bytecode = True
from veeam_trace import extract


class TraceTests(unittest.TestCase):
    def test_credentials_and_ticket_values_are_not_retained(self):
        line = ('2026-10-30 14:00:01,123 DEBUG [VeeamControlServer] Handled request - '
                'remote address: 1.2.3.4, method: POST, uri: /ovirt-engine/sso/oauth/token?password=secret, '
                'headers: [Authorization=Bearer secret; Cookie=secret; ], status: 200')
        result = extract([line], '2026-10-30 14:00:00')
        self.assertEqual(result[0]['path'], '/ovirt-engine/sso/oauth/token')
        self.assertNotIn('secret', json.dumps(result))
        self.assertNotIn('Authorization', json.dumps(result))

    def test_ids_are_aliased_consistently(self):
        prefix = '2026-10-30 14:00:01,123 DEBUG Handled request - remote address: x, method: GET, uri: '
        path = '/api/imagetransfers/12345678-abcd-1234-abcd-123456789012'
        result = extract([prefix + path + ', status: 200', prefix + path + '/finalize, status: 200'], '')
        self.assertEqual(result[0]['path'], '/api/imagetransfers/<id-1>')
        self.assertEqual(result[1]['path'], '/api/imagetransfers/<id-1>/finalize')

    def test_old_or_unstructured_lines_are_ignored(self):
        self.assertEqual([], extract(['Authorization: secret',
            '2026-10-29 14:00:00,000 Handled request - remote address: x, method: GET, uri: /api, status: 200'],
            '2026-10-30 14:00:00'))


if __name__ == '__main__':
    unittest.main()
