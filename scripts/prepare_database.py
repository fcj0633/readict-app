"""Local MySQL preparation. Secrets only via hidden input / child environment; never logged."""
import getpass
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'
NAMES = ['readict_dev_app', 'readict_dev_migrate', 'readict_test_app', 'readict_test_migrate']

def mysql(user, password, sql, database=None, check=True):
    args = ['mysql', '--host=127.0.0.1', '--port=3306', '--protocol=TCP', '--user=' + user, '--batch', '--skip-column-names']
    if database:
        args.append(database)
    child_env = os.environ.copy()
    child_env.pop('READICT_ADMIN_PASSWORD', None)
    child_env['MYSQL_PWD'] = password
    result = subprocess.run(args, input=sql, text=True, encoding='utf-8', capture_output=True, env=child_env)
    if check and result.returncode:
        raise RuntimeError('MySQL operation failed; check account, connectivity and existing objects. SQL and secrets suppressed.')
    return result

def main():
    admin = os.environ.pop('READICT_ADMIN_PASSWORD', None) or getpass.getpass('MySQL root password: ')
    facts = mysql('root', admin, "SELECT VERSION(),@@port,@@character_set_server,@@collation_server;").stdout.strip()
    print('Server:', facts)
    if facts.split('\t')[1] != '3306':
        raise RuntimeError('Unexpected server port')
    existing = mysql('root', admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('readict_dev','readict_test'); SELECT User,Host FROM mysql.user WHERE User IN ('readict_dev_app','readict_dev_migrate','readict_test_app','readict_test_migrate');").stdout.strip()
    path = LOCAL / 'database.json'
    if existing and not path.exists():
        raise RuntimeError('Existing target schemas/accounts found without local credentials. Inspect ownership before proceeding; no changes made.')
    if path.exists():
        config = json.loads(path.read_text(encoding='utf-8'))
        if not existing:
            raise RuntimeError('Local credentials exist but server objects are missing; inspect before proceeding.')
        print('Existing configuration retained; no passwords or grants changed.')
    else:
        LOCAL.mkdir(exist_ok=True)
        identity = subprocess.run(['whoami'], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(['icacls', str(LOCAL), '/inheritance:r', '/grant:r', identity + ':(OI)(CI)F'], capture_output=True, check=True)
        config = {name: secrets.token_hex(24) for name in NAMES}
        # Save before provisioning so a partially failed run does not lose generated secrets.
        path.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
        statements = []
        for profile in ['dev', 'test']:
            schema = 'readict_' + profile
            statements.append(f'CREATE DATABASE {schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;')
            for role in ['app', 'migrate']:
                name = schema + '_' + role
                statements.append(f"CREATE USER '{name}'@'127.0.0.1' IDENTIFIED BY '{config[name]}';")
                privileges = 'SELECT,INSERT,UPDATE,DELETE' + (',CREATE,ALTER,DROP,INDEX,REFERENCES' if role == 'migrate' else '')
                statements.append(f"GRANT {privileges} ON {schema}.* TO '{name}'@'127.0.0.1';")
        mysql('root', admin, '\n'.join(statements))
        print('Created two schemas and four isolated accounts; secrets saved in ignored .local/database.json.')
    for name in NAMES:
        profile = 'dev' if '_dev_' in name else 'test'
        schema = 'readict_' + profile
        result = mysql(name, config[name], 'SELECT DATABASE(),CURRENT_USER(),@@version; SHOW GRANTS;', schema)
        print(result.stdout.strip())
        other = 'readict_test' if profile == 'dev' else 'readict_dev'
        denied = mysql(name, config[name], 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();', other, check=False)
        if denied.returncode == 0:
            raise RuntimeError('Cross-schema access was unexpectedly permitted')
        print(name + ': cross-schema access denied')
    print('Database preparation and four-account verification complete.')

if __name__ == '__main__':
    main()
