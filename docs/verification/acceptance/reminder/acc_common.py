"""REM-ACC independent acceptance shared helpers (acceptance-only, not candidate)."""
import json, time, datetime, subprocess, os, random, string, re

ROOT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10'
ACC = ROOT + '/docs/verification/acceptance/reminder'
SHOTS = ROOT + '/frontend/test-results/acceptance-reminder'
BASE = 'http://localhost:15199'
API = 'http://localhost:18099'
TMPD = open(ACC + '/tmpdir.txt').read().strip()
DB = TMPD + '/library.db'
SQLITE = '/opt/local/bin/sqlite3'
os.makedirs(SHOTS, exist_ok=True)

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def log_cmd(msg):
    with open(ACC + '/commands.log', 'a') as f:
        f.write(f'[{now()}] {msg}\n')

def record(phase, item, expected, actual, result, evidence, extra=None):
    row = {'phase': phase, 'item': item, 'expected': expected, 'actual': actual,
           'result': result, 'evidence': evidence, 'at': now(),
           'candidateId': 'REM-INT-v1.5-r3'}
    if extra: row.update(extra)
    with open(ACC + '/observations.jsonl', 'a') as f:
        f.write(json.dumps(row, ensure_ascii=False) + '\n')
    print(f'[{result}] {item}: {str(actual)[:160]}')
    return row

def shot(page, name):
    p = f'{SHOTS}/{name}.png'
    page.screenshot(path=p, full_page=True)
    return p

def csrf(page):
    r = page.request.get(BASE + '/api/auth/csrf')
    assert r.status == 204, f'csrf {r.status}'
    for c in page.context.cookies():
        if c['name'] == 'XSRF-TOKEN':
            return c['value']
    raise RuntimeError('no XSRF-TOKEN')

def api_post(page, path, data=None, token='auto', headers=None):
    h = dict(headers or {})
    if token == 'auto':
        h['X-XSRF-TOKEN'] = csrf(page)
    elif token is not None:
        h['X-XSRF-TOKEN'] = token
    return page.request.post(BASE + path, data=data, headers=h)

def api_get(page, path):
    return page.request.get(BASE + path)

def jget(page, path):
    r = api_get(page, path)
    assert r.ok, f'GET {path} -> {r.status} {r.text()}'
    return r.json()

def jpost(page, path, data=None):
    r = api_post(page, path, data)
    assert r.ok, f'POST {path} -> {r.status} {r.text()}'
    return r.json() if r.status != 204 else None

def ui_login(page, username, password):
    page.goto(BASE + '/login')
    page.wait_for_load_state('networkidle')
    for attempt in range(2):
        page.get_by_label('用户名').fill(username)
        page.get_by_label('密码').fill(password)
        page.get_by_role('button', name=re.compile(r'登\s*录')).click()
        try:
            page.wait_for_url(lambda u: '/login' not in u, timeout=10000)
            break
        except Exception:
            if attempt == 1:
                raise RuntimeError(f'UI login failed: {page.locator(".ant-message, .ant-form-item-explain").all_inner_texts()}')
            page.wait_for_timeout(800)
    page.wait_for_load_state('networkidle')

def api_login(page, username, password):
    return jpost(page, '/api/auth/login', {'username': username, 'password': password})

def isbn13():
    body = '978' + ''.join(random.choice(string.digits) for _ in range(9))
    s = sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(body))
    return body + str((10 - s % 10) % 10)

def suffix():
    return ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))

def sql(query, timeout_ms=8000):
    """Run sqlite3 against OWN temp DB only."""
    p = subprocess.run([SQLITE, DB], input=f'.timeout {timeout_ms}\n{query}\n',
                       capture_output=True, text=True, timeout=60)
    if p.returncode != 0:
        raise RuntimeError(f'sqlite3 failed: {p.stderr}')
    return p.stdout.strip()

def today_sh():
    # host TZ is +08:00 (verified in startup log); business tz Asia/Shanghai
    return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()

def make_book(page, title, copies=1):
    b = jpost(page, '/api/books', {'isbn': isbn13(), 'title': title,
                                   'author': '验收作者', 'initialCopies': copies})
    cs = jget(page, f"/api/books/{b['id']}/copies")
    return b, cs

def make_reader(page, name, username, password):
    return jpost(page, '/api/readers', {'name': name, 'username': username,
                                        'initialPassword': password})

def make_loan(page, card, barcode):
    return jpost(page, '/api/loans', {'readerCardNo': card, 'copyBarcode': barcode})

def set_loan_due(loan_id, due):
    borrow = (due - datetime.timedelta(days=30)).isoformat()
    sql(f"UPDATE loan SET due_date='{due.isoformat()}', borrow_date='{borrow}' WHERE id={loan_id};")
def api_put(page, path, data=None):
    h = {'X-XSRF-TOKEN': csrf(page)}
    return page.request.put(BASE + path, data=data, headers=h)
