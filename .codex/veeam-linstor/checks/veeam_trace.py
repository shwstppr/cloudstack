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
"""Extract sanitized Veeam control request metadata; never save raw logs."""
import argparse
import datetime
import json
import pathlib
import re
import shlex
import subprocess
import tempfile
import urllib.parse
import uuid


def extract(lines, since):
    aliases = {}
    events = []
    for line in lines:
        if 'Handled request - remote address:' not in line or line[:19] < since:
            continue
        request = re.search(r'method: ([A-Z]+), uri: ([^\s,]+)', line)
        statuses = re.findall(r', status: ([0-9]{3})\b', line)
        if request is None or not statuses:
            continue
        uri = urllib.parse.urlsplit(request.group(2))
        # Keep correlation without retaining capability-bearing transfer UUIDs.
        def alias(match):
            key = match.group(0)
            if key not in aliases:
                aliases[key] = '<id-{}>'.format(len(aliases) + 1)
            return aliases[key]
        path = re.sub(r'(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', alias, uri.path)
        path = re.sub(r'(/images/)[^/]+', r'\1<transfer>', path)
        events.append({'time_utc': line[:23], 'method': request.group(1),
                       'path': path, 'status': int(statuses[-1])})
    return events


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=pathlib.Path, default=pathlib.Path(__file__).with_name('qa6.json'))
    parser.add_argument('--since', required=True, help='UTC timestamp, e.g. 2026-10-30T14:00:00Z')
    parser.add_argument('--lines', type=int, default=20000, help='maximum recent log lines to inspect')
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    try:
        since = datetime.datetime.strptime(args.since, '%Y-%m-%dT%H:%M:%SZ').strftime('%Y-%m-%d %H:%M:%S')
    except ValueError:
        parser.error('--since must be a UTC timestamp: YYYY-MM-DDTHH:MM:SSZ')
    if not 1 <= args.lines <= 100000:
        parser.error('--lines must be between 1 and 100000')
    target = json.loads(args.config.read_text())['management_ssh']
    if target.startswith('-'):
        parser.error('Invalid SSH destination')
    command = 'tail -n {} -- {}'.format(args.lines, shlex.quote('/var/log/cloudstack/management/management-server.log'))
    output = args.output or pathlib.Path(tempfile.gettempdir()) / ('veeam-trace-' + uuid.uuid4().hex[:12] + '.json')
    try:
        result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', target, command],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=45)
        if result.returncode:
            raise RuntimeError('Cannot read management log: ' + result.stderr[-500:])
        events = extract(result.stdout.splitlines(), since)
        report = {'since_utc': args.since, 'log_tail_limit': args.lines, 'events': events,
                  'scope': 'Control request metadata only; no bodies, headers, query values or VBR job-result proof'}
        with output.open('x') as handle:
            json.dump(report, handle, indent=2)
            handle.write('\n')
        print('{} control requests; report: {}'.format(len(events), output))
        if not events:
            print('NO_EVIDENCE: no matching request metadata in the current log tail; check log level/window/rotation.')
            return 2
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print('FAIL ' + str(error))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
