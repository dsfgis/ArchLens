#!/usr/bin/env python3
"""Reproducible P0 regression against an isolated, disposable local PostgreSQL cluster."""
import datetime, hashlib, json, os, pathlib, shutil, socket, subprocess, tempfile, uuid
import xml.etree.ElementTree as ET

service = pathlib.Path(__file__).resolve().parents[1]
pg_bin = pathlib.Path(os.environ.get('ARCHLENS_TEST_PG_BIN', '/usr/lib/postgresql/16/bin'))
for command in ['initdb', 'pg_ctl', 'psql']:
    if not (pg_bin / command).is_file():
        raise SystemExit('Missing PostgreSQL test binary: ' + command)
for command in ['java', 'mvn', 'node', 'npm']:
    if not shutil.which(command):
        raise SystemExit('Activate the project toolchain first; missing ' + command)
root = service / 'target' / 'verification'
root.mkdir(parents=True, exist_ok=True)
work = pathlib.Path(tempfile.mkdtemp(prefix='p0-', dir=root))
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
# This is a synthetic credential confined to the disposable loopback test cluster.
password = 'p0-disposable-test-only'
pw = work / 'password'; pw.write_text(password); pw.chmod(0o600)
env = dict(os.environ, PGPASSWORD=password)
# Never inherit opt-ins that could contact an existing MySQL/Neo4j installation.
for key in list(env):
    if key.startswith('ARCHLENS_') and key.endswith('_IT'): env[key] = 'false'
env['ARCHLENS_JAVA_COMMAND'] = shutil.which('java')
started = False

def run(args, name, cwd=service, extra=None):
    with (work / (name + '.log')).open('w') as log:
        result = subprocess.run([str(x) for x in args], cwd=cwd, env=extra or env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((work / (name + '.log')).read_text()[-14000:])
        raise RuntimeError(name + ' failed; evidence: ' + str(work))

def sql(statement):
    subprocess.run([str(pg_bin / 'psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', str(port), '-U', 'archlens_test_admin', '-d', 'archlens_migration_it', '-c', statement], env=env, check=True, stdout=subprocess.DEVNULL)

try:
    run([pg_bin / 'initdb', '-D', work / 'data', '-U', 'archlens_test_admin', '--pwfile', pw, '--auth=scram-sha-256', '--encoding=UTF8', '--no-locale'], 'initdb')
    run([pg_bin / 'pg_ctl', '-D', work / 'data', '-l', work / 'postgres.log', '-o', f'-h 127.0.0.1 -p {port} -k {work}', '-w', 'start'], 'start')
    started = True
    subprocess.run([str(pg_bin / 'psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', str(port), '-U', 'archlens_test_admin', '-d', 'postgres', '-c', 'CREATE DATABASE archlens_migration_it'], env=env, check=True, stdout=subprocess.DEVNULL)
    # Install the byte-identical V001-V004 baseline, with one dispatched logical call.
    # V005 must migrate this existing row without resetting its budget or elapsed time.
    for version, name in [(1,'V001__investigation_storage.sql'),(2,'V002__migration_runtime.sql'),(3,'V003__migration_submission.sql'),(4,'V004__migration_tool_result.sql')]:
        path = service / 'src/main/resources/db' / name
        sql(path.read_text())
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        sql(f"INSERT INTO archlens.schema_version(version,checksum) VALUES({version},'{checksum}')")
    case_id, old_run, task_id, invocation_id = [str(uuid.uuid4()) for _ in range(4)]
    request = json.loads((service / 'examples/migration/request-code-only.json').read_text())
    request_json = json.dumps(request, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    request_hash = hashlib.sha256(request_json.encode()).hexdigest()
    sql(f"INSERT INTO archlens.migration_case(case_id,latest_revision) VALUES('{case_id}',1); "
        f"INSERT INTO archlens.migration_run(run_id,case_id,revision,request_hash,request_json,orchestration_type,execution_state,epoch,worker_id,lease_until) VALUES('{old_run}','{case_id}',1,'{request_hash}','{request_json.replace(chr(39),chr(39)*2)}'::jsonb,'LANGGRAPH','RUNNING',1,'old-worker',clock_timestamp()-interval '1 second'); "
        f"INSERT INTO archlens.migration_task(task_id,run_id,revision,plan_version,task_version,status,attempts) VALUES('{task_id}','{old_run}',1,1,1,'RUNNING',1); "
        f"INSERT INTO archlens.tool_invocation(invocation_id,run_id,revision,task_id,task_version,action_key,tool_id,tool_version,input_hash,snapshot_hash,status) VALUES('{invocation_id}','{old_run}',1,'{task_id}',1,'upgrade-fixture','list_rules','historical-fixture','{'0'*64}','{'1'*64}','DISPATCHED');")
    env.update(ARCHLENS_PG_URL=f'jdbc:postgresql://127.0.0.1:{port}/archlens_migration_it', ARCHLENS_PG_USER='archlens_test_admin', ARCHLENS_PG_PASSWORD=password,
        ARCHLENS_NEO4J_URI='bolt://127.0.0.1:1', ARCHLENS_NEO4J_USER='unused', ARCHLENS_NEO4J_PASSWORD='unused', ARCHLENS_NEO4J_DATABASE='neo4j',
        ARCHLENS_CHECKPOINT_PG_URL=f'postgresql://127.0.0.1:{port}/archlens_migration_it', ARCHLENS_CHECKPOINT_PG_USER='archlens_test_admin', ARCHLENS_CHECKPOINT_PG_PASSWORD=password,
        ARCHLENS_MIGRATION_SCHEMA_IT='true', ARCHLENS_P0_IT='true', ARCHLENS_LLM_DISABLED='true',
        ARCHLENS_CLI_JAR=str(service / 'target/archlens-0.1.0-SNAPSHOT-cli.jar'))
    run(['mvn', '--batch-mode', '--no-transfer-progress', 'verify'], 'java-verify')
    check = subprocess.check_output([str(pg_bin / 'psql'), '-X', '-At', '-h', '127.0.0.1', '-p', str(port), '-U', 'archlens_test_admin', '-d', 'archlens_migration_it', '-c',
        f"SELECT status || ':' || (started_at IS NOT NULL)::text || ':' || (runtime_profile IS NULL)::text FROM archlens.migration_budget_reservation b JOIN archlens.migration_run r ON r.run_id=b.run_id WHERE b.run_id='{old_run}'"], env=env, text=True).strip()
    if check != 'SETTLED:true:true': raise RuntimeError('V004 to V005 preservation failed')
    runtime = service / 'agent-runtime'
    run(['npm', 'run', 'build'], 'typescript-build', runtime)
    run(['node', 'dist/src/worker-cli.js', 'initialize'], 'runtime-initialize', runtime)
    sql("CREATE ROLE archlens_checkpoint_worker LOGIN PASSWORD 'p0-disposable-test-only'; GRANT USAGE ON SCHEMA archlens_checkpoint TO archlens_checkpoint_worker; GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA archlens_checkpoint TO archlens_checkpoint_worker;")
    env['ARCHLENS_CHECKPOINT_PG_USER'] = 'archlens_checkpoint_worker'
    run(['npm', 'test'], 'runtime-tests', runtime)
    run(['npm', 'run', 'check'], 'frontend-check', service.parent / 'ArchLensClient')
    run(['npm', 'test'], 'frontend-tests', service.parent / 'ArchLensClient')
    # Smoke the final packaged CLI and retain its report, including deterministic quality assertions.
    smoke = work / 'column-rename.json'
    run(['java', '-jar', env['ARCHLENS_CLI_JAR'], 'analyze', 'examples/column-rename/request.json', smoke], 'legacy-cli-smoke')
    report = json.loads(smoke.read_text())
    assert report['analysis']['status'] == 'PARTIAL' and len(report['graph']['nodes']) == 3 and len(report['graph']['edges']) == 2
    method = next(n for n in report['graph']['nodes'] if n['type'] == 'METHOD')
    impact = next(i for i in report['analysis']['impacts'] if i['nodeId'] == method['nodeId'])
    assert impact['changeRequired'] == 'YES' and impact['risk']['min'] == 32 and impact['risk']['max'] == 92
    run(['java', '-jar', env['ARCHLENS_CLI_JAR'], 'migration-request-check', 'examples/migration/request-p0-rules.json'], 'p0-request-smoke')
    tests = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in (service / 'target/surefire-reports').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in tests: tests[key] += int(suite.get(key, '0'))
    summary = {'status': 'PASS', 'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'baseCommit': subprocess.check_output(['git','rev-parse','HEAD'],cwd=service,text=True).strip(),
        'database': 'disposable PostgreSQL 16', 'upgrade': 'V001-V004 unchanged; dispatched reservation and elapsed time preserved',
        'runtime': 'native PostgresSaver and Java ledger', 'java': tests,
        'nodeVersion': subprocess.check_output(['node','--version'],text=True).strip(),
        'lockfileSha256': hashlib.sha256((runtime / 'package-lock.json').read_bytes()).hexdigest(),
        'cliSha256': hashlib.sha256(pathlib.Path(env['ARCHLENS_CLI_JAR']).read_bytes()).hexdigest(),
        'evidenceSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in work.glob('*.log')}}
    (work / 'summary.json').write_text(json.dumps(summary, indent=2))
    print('P0 verification passed:', work)
finally:
    if started:
        subprocess.run([str(pg_bin / 'pg_ctl'), '-D', str(work / 'data'), '-m', 'immediate', '-w', 'stop'], stdout=subprocess.DEVNULL, check=True)
    pw.unlink(missing_ok=True)
    # Only this process's mkdtemp-owned cluster is removed. Evidence logs are retained.
    if (work / 'data').exists(): shutil.rmtree(work / 'data')
