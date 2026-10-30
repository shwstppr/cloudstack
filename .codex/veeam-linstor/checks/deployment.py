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
"""SSH-key-only lab checks; standard Python library, no SDK dependencies."""
import argparse
import datetime
import ipaddress
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

API = r'''
import json,sys,urllib.request,urllib.parse,urllib.error
p=json.load(sys.stdin); command=p['command']
try:
    try: r=urllib.request.urlopen('http://127.0.0.1:8096/client/api?'+urllib.parse.urlencode(p),timeout=60)
    except urllib.error.HTTPError as e: r=e
    d=json.load(r)[command.lower()+'response']
    if 'errorcode' in d: raise RuntimeError(d.get('errortext','API error'))
    print(json.dumps({'ok':True,'data':d}))
except Exception as e: print(json.dumps({'ok':False,'error':str(e)}))
'''
NODES = r'''
import json,subprocess
r=subprocess.run(['linstor','--machine-readable','node','list'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True,timeout=30)
if r.returncode: raise RuntimeError(r.stderr)
d=json.loads(r.stdout)
while d and isinstance(d[0],list): d=d[0]
print(json.dumps({x['name']:x['connection_status'] for x in d}))
'''
DISK = r'''
import json,sys,subprocess,xml.etree.ElementTree as E
p=json.load(sys.stdin)
for service in ['cloudstack-agent','linstor-satellite']:
    subprocess.run(['systemctl','is-active','--quiet',service],check=True)
root=E.fromstring(subprocess.check_output(['virsh','dumpxml',p['instance']],universal_newlines=True)); matches=[]
for d in root.findall('./devices/disk'):
    target=d.find('target'); source=d.find('source')
    if target is not None and target.get('dev')=='vda' and source is not None: matches.append(source.get('dev'))
if matches != [p['device']]: raise RuntimeError('Unexpected root device: '+str(matches))
state=subprocess.check_output(['virsh','domstate',p['instance']],universal_newlines=True).strip()
if state!='running': raise RuntimeError('Libvirt domain is '+state)
print(json.dumps({'state':state,'root_device':matches[0]}))
'''
GUEST = r'''
import json,sys,subprocess,shlex,ipaddress
p=json.load(sys.stdin); ip=str(ipaddress.ip_address(p['ip'])); router=str(ipaddress.ip_address(p['router']))
probe="import socket,subprocess; subprocess.run(['ping','-c','2','-W','2',%r],check=True,stdout=subprocess.DEVNULL); s=socket.create_connection((%r,22),3); s.settimeout(3); b=s.recv(256); s.close(); assert b.startswith(b'SSH-'),repr(b); print('GUEST_READY')" % (ip,ip)
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=5','-o','StrictHostKeyChecking=accept-new','-i',p['key'],'-p','3922','root@'+router,'python3 -c '+shlex.quote(probe)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True,timeout=20)
if r.returncode: raise RuntimeError(r.stderr[-1500:])
if 'GUEST_READY' not in r.stdout: raise RuntimeError('Missing guest probe result')
print(json.dumps({'ip':ip,'ping':True,'ssh_banner':True}))
'''


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class Check:
    def __init__(self, config, args):
        self.config, self.args = config, args
        self.run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6]
        self.directory = pathlib.Path(args.results_dir) / self.run_id
        self.directory.mkdir(parents=True, mode=0o700)
        self.report = {'run_id': self.run_id, 'mode': args.mode, 'checks': [], 'jobs': []}
        self.created_id = None
        self.created_name = 'linstor-check-' + self.run_id.lower()
        self.pending = None
        self.save()

    def save(self):
        (self.directory / 'report.json').write_text(json.dumps(self.report, indent=2) + '\n')

    def passed(self, name, details):
        self.report['checks'].append({'name': name, 'status': 'PASS', 'details': details})
        self.save()
        print('PASS ' + name, flush=True)

    def remote(self, target, code, payload=None):
        require(not target.startswith('-'), 'Invalid SSH destination')
        r = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                            '-o', 'StrictHostKeyChecking=accept-new', target,
                            'python3 -c ' + shlex.quote(code)],
                           input=json.dumps(payload or {}), stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, universal_newlines=True, timeout=80)
        require(r.returncode == 0, 'SSH/remote check on {}: {}'.format(target, r.stderr[-2000:]))
        try:
            return json.loads(r.stdout)
        except ValueError:
            raise RuntimeError('Non-JSON response from ' + target)

    def api(self, command, **params):
        params.update(command=command, response='json', account=self.config['account'], domainid=self.config['domain_id'])
        r = self.remote(self.config['management_ssh'], API, params)
        require(r['ok'], '{}: {}'.format(command, r.get('error')))
        return r['data']

    def vm(self, vm_id):
        rows = self.api('listVirtualMachines', id=vm_id, listall='true').get('virtualmachine', [])
        require(len(rows) == 1, 'Expected exactly one VM ' + vm_id)
        return rows[0]

    def job(self, command, **params):
        # A lost response may still mean a mutation was accepted. Persist before sending.
        self.pending = {'command': command, 'jobid': None}
        self.report['pending_job'] = self.pending
        self.save()
        result = self.api(command, **params)
        if command == 'deployVirtualMachine':
            self.created_id = result.get('id')
            self.report['created_vm_id'] = self.created_id
        require(result.get('jobid'), 'Missing async job ID for ' + command)
        self.pending['jobid'] = result['jobid']
        self.save()
        deadline = time.monotonic() + self.args.timeout
        while time.monotonic() < deadline:
            job = self.api('queryAsyncJobResult', jobid=result['jobid'])
            if job['jobstatus']:
                self.report['jobs'].append(dict(self.pending, status=job['jobstatus']))
                self.pending = None
                self.report.pop('pending_job', None)
                self.save()
                require(job['jobstatus'] == 1, '{} failed: {}'.format(command, job.get('jobresult')))
                return job.get('jobresult', {})
            time.sleep(3)
        raise RuntimeError(command + ' timed out; operation may still be running')

    def infrastructure(self):
        pools = self.api('listStoragePools', id=self.config['pool_id']).get('storagepool', [])
        require(len(pools) == 1 and pools[0].get('state') == 'Up' and pools[0].get('type') == 'Linstor',
                'Configured LINSTOR primary pool is missing or not Up')
        hosts = self.api('listHosts', clusterid=self.config['cluster_id'], type='Routing').get('host', [])
        by_id = {h['id']: h for h in hosts}
        for host_id in self.config['hosts']:
            require(host_id in by_id and by_id[host_id]['state'] == 'Up', 'KVM host not Up: ' + host_id)
        self.passed('CloudStack pool and hosts', {'pool': pools[0]['name'], 'hosts': [by_id[x]['name'] for x in self.config['hosts']]})
        nodes = self.remote(self.config['appliance_ssh'], NODES)
        for name in [self.config['appliance_node']] + [by_id[x]['name'] for x in self.config['hosts']]:
            require(nodes.get(name) == 'ONLINE', 'LINSTOR node not ONLINE: ' + name)
        self.passed('LINSTOR nodes online', nodes)

    def verify_vm(self, vm_id, label):
        vm = self.vm(vm_id)
        require(vm['state'] == 'Running', 'VM is ' + vm['state'])
        require(vm.get('hostid') in self.config['hosts'], 'VM is on an unconfigured host')
        roots = [v for v in self.api('listVolumes', virtualmachineid=vm_id).get('volume', []) if v['type'] == 'ROOT']
        require(len(roots) == 1, 'Expected exactly one root volume')
        root = roots[0]
        require(root['state'] == 'Ready' and root.get('storageid') == self.config['pool_id'],
                'Root disk is not Ready on the configured LINSTOR pool')
        path = root.get('path')
        require(path and '/' not in path, 'Unexpected CloudStack volume path')
        device = '/dev/drbd/by-res/cs-' + path + '/0'
        disk = self.remote(self.config['hosts'][vm['hostid']], DISK, {'instance': vm['instancename'], 'device': device})
        self.passed(label + ' root placement', {'vm_id': vm_id, 'host': vm['hostname'], 'volume_id': root['id'], **disk})
        nics = [n for n in vm.get('nic', []) if n['networkid'] == self.config['network_id']]
        require(len(nics) == 1, 'Validation network NIC missing')
        routers = self.api('listRouters', networkid=self.config['network_id'], listall='true').get('router', [])
        routers = [r for r in routers if r['state'] == 'Running' and r.get('hostid') in self.config['hosts']]
        require(len(routers) == 1, 'Expected one running router on configured hosts')
        router = routers[0]
        deadline = time.monotonic() + self.args.timeout
        last_error = None
        while time.monotonic() < deadline:
            try:
                details = self.remote(self.config['hosts'][router['hostid']], GUEST,
                                      {'ip': str(ipaddress.ip_address(nics[0]['ipaddress'])),
                                       'router': router['linklocalip'], 'key': self.config['router_key']})
                self.passed(label + ' guest ping and SSH banner', details)
                return vm, root
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                last_error = str(error)
                time.sleep(3)
        raise RuntimeError('Guest readiness timed out: ' + str(last_error))

    def deploy(self):
        offering = self.api('listServiceOfferings', id=self.config['offering_id']).get('serviceoffering', [])
        require(len(offering) == 1 and self.config['storage_tag'] in offering[0].get('storagetags', '').split(','),
                'Service offering must constrain root placement with the configured storage tag')
        self.report['created_vm_name'] = self.created_name
        self.save()
        self.job('deployVirtualMachine', name=self.created_name, displayname=self.created_name,
                 zoneid=self.config['zone_id'], templateid=self.config['template_id'],
                 serviceofferingid=self.config['offering_id'], networkids=self.config['network_id'])
        require(self.created_id, 'Deployment returned no VM ID')
        vm, root = self.verify_vm(self.created_id, 'Fresh deployment')
        if self.args.cycle:
            others = [h for h in self.config['hosts'] if h != vm['hostid']]
            require(others, 'Cross-host cycle requires another host')
            self.job('stopVirtualMachine', id=self.created_id)
            self.job('startVirtualMachine', id=self.created_id, hostid=others[0])
            after, after_root = self.verify_vm(self.created_id, 'Cross-host restart')
            require(after['hostid'] == others[0] and after_root['id'] == root['id'] and after_root['path'] == root['path'],
                    'Restart changed host/disk identity unexpectedly')
            self.passed('Same root disk across hosts', {'volume_id': root['id']})

    def cleanup(self):
        if self.args.mode != 'deploy' or self.args.keep_vm:
            return
        require(self.pending is None, 'Pending/ambiguous operation: VM retained; see pending_job and created_vm_name in report')
        if not self.created_id:
            return
        vm = self.vm(self.created_id)
        require(vm['name'] == self.created_name and vm['account'] == self.config['account'],
                'Cleanup ownership mismatch; refusing to destroy VM')
        self.job('destroyVirtualMachine', id=self.created_id, expunge='true')
        require(not self.api('listVirtualMachines', id=self.created_id, listall='true').get('virtualmachine', []),
                'Test VM still present after expunge')
        self.passed('Fresh test VM expunged', {'vm_id': self.created_id})

    def run(self):
        failed = False
        try:
            self.infrastructure()
            if self.args.mode == 'deploy':
                self.deploy()
            else:
                self.verify_vm(self.config['validation_vm_id'], 'Retained VM')
        except (Exception, KeyboardInterrupt) as error:
            failed = True
            self.report['error'] = str(error) or 'Interrupted'
            print('FAIL ' + self.report['error'], flush=True)
        finally:
            try:
                self.cleanup()
            except (Exception, KeyboardInterrupt) as error:
                failed = True
                self.report['cleanup_error'] = str(error) or 'Cleanup interrupted'
                print('FAIL cleanup: ' + self.report['cleanup_error'], flush=True)
            self.report['status'] = 'FAIL' if failed else 'PASS'
            self.save()
        print('{} — report: {}'.format(self.report['status'], self.directory / 'report.json'))
        return int(failed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=pathlib.Path, default=pathlib.Path(__file__).with_name('qa6.json'))
    parser.add_argument('--mode', choices=['health', 'deploy'], default='health', help='health is read-only; deploy creates a fresh VM')
    parser.add_argument('--cycle', action='store_true', help='stop/start the fresh VM on the other host')
    parser.add_argument('--keep-vm', action='store_true', help='retain the fresh VM for inspection')
    parser.add_argument('--timeout', type=int, default=240, help='seconds per async job or guest readiness phase')
    parser.add_argument('--results-dir', default=str(pathlib.Path(tempfile.gettempdir()) / 'cloudstack-lab-checks'))
    args = parser.parse_args()
    if args.timeout <= 0 or ((args.cycle or args.keep_vm) and args.mode != 'deploy'):
        parser.error('positive timeout required; --cycle/--keep-vm require --mode deploy')
    try:
        return Check(json.loads(args.config.read_text()), args).run()
    except Exception as error:
        print('FAIL configuration/report setup: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
