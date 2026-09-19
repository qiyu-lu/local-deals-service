#!/usr/bin/env python3
"""Benchmark fixtures for an isolated stack (see scripts/stack.sh).

  fixture.py users  N      create N users (ids 10_000_001..) and write their login tokens to
                           Redis and to benchmark/v2/run/tokens.csv
  fixture.py voucher STOCK create one ACTIVE seckill voucher (MySQL + Redis) and print its id
  fixture.py orders  ID    print persisted order count for a voucher

Only the stdlib plus the `mysql` and `redis-cli` binaries are used. Connection settings come
from the variables printed by `scripts/stack.sh env`.
"""
import os
import subprocess
import sys
import time

BASE_USER_ID = 10_000_000
TOKEN_TTL_SECONDS = 24 * 3600
PROJECT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
TOKENS_FILE = os.path.join(PROJECT_DIR, 'benchmark', 'v2', 'run', 'tokens.csv')


def env(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"{name} is not set; run: eval \"$(scripts/stack.sh env)\"")
    return value


def mysql(sql):
    url = env('LOCAL_DEALS_DATASOURCE_URL')  # jdbc:mysql://127.0.0.1:23306/local_deals?...
    host_port, schema = url.split('//', 1)[1].split('?', 1)[0].split('/', 1)
    host, port = host_port.split(':')
    out = subprocess.run(
        ['mysql', '-h', host, '-P', port, '-u', env('LOCAL_DEALS_DATASOURCE_USERNAME'),
         '-p' + env('LOCAL_DEALS_DATASOURCE_PASSWORD'), '-N', '-B', schema],
        input=sql, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(out.stderr)
    return out.stdout


def redis_pipe(commands):
    """Feeds RESP-encoded commands to redis-cli --pipe."""
    def resp(args):
        parts = [f"*{len(args)}\r\n"]
        for arg in args:
            data = str(arg)
            parts.append(f"${len(data.encode())}\r\n{data}\r\n")
        return ''.join(parts)
    payload = ''.join(resp(c) for c in commands)
    out = subprocess.run(
        ['redis-cli', '-h', env('LOCAL_DEALS_REDIS_HOST'), '-p', env('LOCAL_DEALS_REDIS_PORT'),
         '-a', env('LOCAL_DEALS_REDIS_PASSWORD'), '--no-auth-warning', '--pipe'],
        input=payload, capture_output=True, text=True)
    if out.returncode != 0 or 'errors: 0' not in out.stdout:
        sys.exit(out.stdout + out.stderr)


def users(count):
    batch = 5000
    for start in range(0, count, batch):
        rows = []
        for i in range(start, min(count, start + batch)):
            uid = BASE_USER_ID + 1 + i
            rows.append(f"({uid},'199{uid % 100_000_000:08d}','bench_{uid}')")
        mysql("INSERT IGNORE INTO tb_user (id, phone, nick_name) VALUES " + ",".join(rows) + ";")
    commands = []
    os.makedirs(os.path.dirname(TOKENS_FILE), exist_ok=True)
    with open(TOKENS_FILE, 'w') as tokens:
        for i in range(count):
            uid = BASE_USER_ID + 1 + i
            token = f"bench-{uid}"
            key = f"login:token:{token}"
            commands.append(['HSET', key, 'id', uid, 'nickName', f"bench_{uid}", 'icon', ''])
            commands.append(['EXPIRE', key, TOKEN_TTL_SECONDS])
            tokens.write(f"{token},{uid}\n")
    redis_pipe(commands)
    print(f"users={count} tokens={TOKENS_FILE}")


def voucher(stock):
    now = int(time.time())
    begin, end = now - 3600, now + 86400
    voucher_id = int(mysql(
        "INSERT INTO tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status) "
        "VALUES (1, '[BENCH] seckill', 'bench', 'bench', 100, 1000, 1, 1);"
        "SELECT LAST_INSERT_ID();").strip())
    mysql(f"INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
          f"VALUES ({voucher_id}, {stock}, FROM_UNIXTIME({begin}), FROM_UNIXTIME({end}));")
    redis_pipe([
        ['SET', f"seckill:stock:{voucher_id}", stock],
        ['HSET', f"seckill:meta:{voucher_id}", 'status', 'ACTIVE', 'beginAt', begin, 'endAt', end],
    ])
    print(voucher_id)


def orders(voucher_id):
    print(mysql(f"SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = {int(voucher_id)};").strip())


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] not in ('users', 'voucher', 'orders'):
        sys.exit(__doc__)
    {'users': users, 'voucher': voucher, 'orders': orders}[sys.argv[1]](int(sys.argv[2]))
