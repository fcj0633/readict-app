"""Non-destructive startup rejection checks against the built jar (Windows/local M00)."""
import json
import os
from pathlib import Path
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'backend/target/readict-backend-0.0.1-SNAPSHOT.jar'
ARTIFACTS = ROOT / '.artifacts'

def main():
    config = json.loads((ROOT / '.local/database.json').read_text(encoding='utf-8'))
    temp = ROOT / '.local/tmp'
    temp.mkdir(exist_ok=True)
    ARTIFACTS.mkdir(exist_ok=True)
    base = {k:v for k,v in os.environ.items() if not k.startswith(('DB_', 'SPRING_', 'SERVER_', 'MANAGEMENT_')) and k != 'JAVA_TOOL_OPTIONS'}
    base.update(DB_APP_USER='readict_dev_app', DB_APP_PASSWORD=config['readict_dev_app'], DB_MIGRATION_USER='readict_dev_migrate', DB_MIGRATION_PASSWORD=config['readict_dev_migrate'])
    java = ['java', '-Duser.timezone=UTC', '-Dfile.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir=' + str(temp), '-jar', str(JAR)]
    def reject(name, env, args, expected):
        result = subprocess.run(java + args, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=50)
        log = result.stdout + result.stderr
        (ARTIFACTS / ('startup-' + name + '.log')).write_text(log, encoding='utf-8')
        if result.returncode == 0 or 'Started ReadictApplication' in log or expected not in log:
            raise AssertionError(name + ': expected failure missing; inspect ignored diagnostic log')
        for secret in config.values():
            if secret in log:
                raise AssertionError(name + ': secret leaked to startup log')
        if 'Using generated security password' in log or 'jdbc:mysql://' in log:
            raise AssertionError(name + ': default security password or JDBC URL appeared in log')
        print(name + ': rejected as expected, process exited, no generated secrets in log')
    missing = base.copy()
    missing.pop('DB_APP_PASSWORD'); missing.pop('DB_MIGRATION_PASSWORD')
    reject('missing-secrets', missing, ['--spring.profiles.active=dev'], 'Application run failed')
    reject('missing-profile', base, [], '数据库配置不符合本机隔离约定')
    wrong = base.copy(); wrong['DB_MIGRATION_PASSWORD'] = 'deliberately-invalid-local-credential'
    reject('invalid-credential', wrong, ['--spring.profiles.active=dev'], 'Access denied')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',8080)); listener.listen()
        reject('port-conflict', base, ['--spring.profiles.active=dev'], '8080')
    # Vite must fail rather than silently choose 5174.
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',5173)); listener.listen()
        result = subprocess.run(['node', 'node_modules/vite/bin/vite.js'], cwd=ROOT/'frontend', capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=20)
        log = result.stdout + result.stderr
        (ARTIFACTS/'startup-vite-port-conflict.log').write_text(log,encoding='utf-8')
        if result.returncode == 0 or '5173' not in log or 'already in use' not in log:
            raise AssertionError('Vite did not reject occupied port')
    print('vite-port-conflict: rejected, no port drift')

if __name__ == '__main__':
    main()
