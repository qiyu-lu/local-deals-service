#!/usr/bin/env python3
"""Benchmark fixtures for an isolated stack (see scripts/stack.sh).

  fixture.py users  N      create N users (ids 10_000_001..) and write their login tokens to
                           Redis and to benchmark/v2/run/tokens.csv
  fixture.py voucher STOCK create one ACTIVE seckill voucher (MySQL + Redis) and print its id
                           SECKILL_BUCKETS=K writes the K bucket shares of a build with buckets
                           (M5 and later); unset or 0 writes the single pre-M5 keys
  fixture.py orders  ID    print persisted order count for a voucher (table: ORDERS_TABLE,
                           default trade_order)

Only the stdlib plus the `mysql` and `redis-cli` binaries are used. Connection settings come
from the variables printed by `scripts/stack.sh env`. BENCH_REDIS_CLUSTER (a comma-separated
node list) switches every write to a Cluster: commands are grouped by the slot of their key and
each group is piped to the node that owns it, because a pipe cannot follow a redirect.
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


def schema_of(url):
    return url.split('//', 1)[1].split('?', 1)[0].split('/', 1)[1]


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


CRC16_TABLE = []
for _byte in range(256):
    _crc = _byte << 8
    for _ in range(8):
        _crc = ((_crc << 1) ^ 0x1021) & 0xFFFF if _crc & 0x8000 else (_crc << 1) & 0xFFFF
    CRC16_TABLE.append(_crc)


def key_slot(key):
    """The Cluster slot of a key, hash tag included."""
    start = key.find('{')
    if start != -1:
        end = key.find('}', start + 1)
        if end > start + 1:
            key = key[start + 1:end]
    crc = 0
    for byte in key.encode():
        crc = ((crc << 8) & 0xFFFF) ^ CRC16_TABLE[((crc >> 8) ^ byte) & 0xFF]
    return crc % 16384


def cluster_nodes():
    return [node.strip() for node in os.environ.get('BENCH_REDIS_CLUSTER', '').split(',')
            if node.strip()]


def redis_cli(host, port, *args, stdin=None):
    return subprocess.run(
        ['redis-cli', '-h', host, '-p', str(port), '-a', env('LOCAL_DEALS_REDIS_PASSWORD'),
         '--no-auth-warning', *args],
        input=stdin, capture_output=True, text=True)


def slot_owners():
    """slot range -> (host, port) of the master that serves it."""
    host, port = cluster_nodes()[0].split(':')
    out = redis_cli(host, port, 'CLUSTER', 'SLOTS')
    if out.returncode != 0:
        sys.exit(out.stderr)
    # redis-cli prints the nested reply one value per line, indented; parse it positionally.
    fields = [line.strip() for line in out.stdout.splitlines() if line.strip()]
    owners, index = [], 0
    while index + 3 < len(fields):
        start, end, owner_host, owner_port = fields[index:index + 4]
        if not (start.isdigit() and end.isdigit() and owner_port.isdigit()):
            index += 1
            continue
        owners.append((int(start), int(end), owner_host, int(owner_port)))
        # skip this master's id and every replica entry until the next slot range
        index += 4
        while index + 1 < len(fields) and not (
                fields[index].isdigit() and fields[index + 1].isdigit()
                and int(fields[index]) <= 16383 and int(fields[index + 1]) <= 16383
                and int(fields[index]) <= int(fields[index + 1])):
            index += 1
    if not owners:
        sys.exit('could not read CLUSTER SLOTS')
    return owners


def resp(args):
    parts = [f"*{len(args)}\r\n"]
    for arg in args:
        data = str(arg)
        parts.append(f"${len(data.encode())}\r\n{data}\r\n")
    return ''.join(parts)


def pipe_to(host, port, commands):
    out = redis_cli(host, port, '--pipe', stdin=''.join(resp(c) for c in commands))
    if out.returncode != 0 or 'errors: 0' not in out.stdout:
        sys.exit(out.stdout + out.stderr)


def redis_pipe(commands):
    """Feeds RESP-encoded commands to redis-cli --pipe, one pipe per owning node."""
    nodes = cluster_nodes()
    if not nodes:
        pipe_to(env('LOCAL_DEALS_REDIS_HOST'), env('LOCAL_DEALS_REDIS_PORT'), commands)
        return
    owners = slot_owners()
    grouped = {}
    for command in commands:
        slot = key_slot(str(command[1]))
        owner = next(((h, p) for start, end, h, p in owners if start <= slot <= end), None)
        if owner is None:
            sys.exit(f"no cluster node owns slot {slot}")
        grouped.setdefault(owner, []).append(command)
    for (host, port), group in grouped.items():
        pipe_to(host, port, group)


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
    buckets = int(os.environ.get('SECKILL_BUCKETS', '0'))
    commands = []
    if buckets > 0:
        # Same split as SeckillBucketRouter: the shares add up to the total.
        for bucket in range(buckets):
            share = stock // buckets + (1 if bucket < stock % buckets else 0)
            prefix = f"sk:{{sk:b{bucket}}}:"
            commands.append(['SET', f"{prefix}stock:{voucher_id}", share])
            commands.append(['HSET', f"{prefix}meta:{voucher_id}",
                             'status', 'ACTIVE', 'beginAt', begin, 'endAt', end])
    else:
        commands.append(['SET', f"seckill:stock:{voucher_id}", stock])
        commands.append(['HSET', f"seckill:meta:{voucher_id}",
                         'status', 'ACTIVE', 'beginAt', begin, 'endAt', end])
    redis_pipe(commands)
    print(voucher_id)


def orders(voucher_id):
    # ORDERS_TABLE=tb_voucher_order measures a build from before M2 (e.g. the v2.0-m1 jar).
    table = os.environ.get('ORDERS_TABLE', 'trade_order')
    voucher_id = int(voucher_id)
    schema = schema_of(env('LOCAL_DEALS_DATASOURCE_URL'))
    # From M6 the orders live in <table>_0..3 in this build's schema and the same in <schema>_1,
    # and this counts them with a plain mysql client that knows nothing about the routing layer.
    # A build from before M6 still has the one logical table. Ask the server which it is instead
    # of configuring it per build, so the same command measures both sides of an A/B.
    names = [line for line in mysql(
        "SELECT CONCAT('`', table_schema, '`.`', table_name, '`') FROM information_schema.tables "
        f"WHERE table_schema IN ('{schema}', '{schema}_1') "
        f"AND (table_name = '{table}' OR table_name REGEXP '^{table}_[0-9]+$');").split()
        if line]
    if not names:
        sys.exit(f"no table named {table} (or {table}_N) in {schema} or {schema}_1")
    union = " UNION ALL ".join(
        f"SELECT COUNT(*) AS c FROM {name} WHERE voucher_id = {voucher_id}" for name in names)
    # COALESCE: SUM over no matching rows is NULL, and the caller wants a number.
    print(mysql(f"SELECT COALESCE(SUM(c), 0) FROM ({union}) counted;").strip())


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] not in ('users', 'voucher', 'orders'):
        sys.exit(__doc__)
    {'users': users, 'voucher': voucher, 'orders': orders}[sys.argv[1]](int(sys.argv[2]))
