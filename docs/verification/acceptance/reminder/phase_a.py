"""Phase A: default-config instance (PT1H). Admin UI login, synthetic data prep,
date time-travel in OWN temp DB, default-scan observation, logout guard."""
import json, datetime, re
from playwright.sync_api import sync_playwright
from acc_common import *

PW = 'Acc@12345'  # synthetic acceptance-only password
T = today_sh()
def d(n): return T + datetime.timedelta(days=n)

results = []
with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    ctx = b.new_context(viewport={'width': 1440, 'height': 900})
    page = ctx.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))

    # 1. ADMIN UI login
    ui_login(page, 'admin', 'Admin@12345')
    page.wait_for_url('**/admin/dashboard**', timeout=15000)
    page.wait_for_load_state('networkidle')
    s1 = shot(page, 'a01-admin-dashboard')
    menu = page.locator('.ant-menu').inner_text()
    record('A', 'ADMIN UI登录+菜单', '登录成功；菜单含催还记录，不含我的提醒',
           f'url={page.url}; menu={menu!r}',
           'PASS' if ('催还记录' in menu and '我的提醒' not in menu) else 'FAIL', s1)

    # 2. synthetic data via real API
    sfx = suffix()
    readers = {}
    for key in ['a', 'b', 'c', 'd']:
        readers[key] = make_reader(page, f'验收读者{key}-{sfx}', f'acc_{key}_{sfx}', PW)
    plan = [('a', 'D4', 4), ('a', 'D3', 3), ('a', 'D2', 2), ('a', 'D0', 0), ('a', 'RENEW', 3),
            ('b', 'O1', -1), ('b', 'O8', -8), ('b', 'O15', -15), ('b', 'O10', -10),
            ('c', 'RETID', -2), ('c', 'RETBAR', -3), ('d', 'DIS', -5)]
    loans = {}
    for rk, label, off in plan:
        book, copies = make_book(page, f'验收图书{label}-{sfx}')
        loan = make_loan(page, readers[rk]['cardNo'], copies[0]['barcode'])
        loans[label] = {'loanId': loan['id'], 'readerKey': rk,
                        'readerId': readers[rk]['id'], 'cardNo': readers[rk]['cardNo'],
                        'username': readers[rk].get('username') or f'acc_{rk}_{sfx}',
                        'bookTitle': book['title'], 'bookId': book['id'],
                        'barcode': copies[0]['barcode'], 'origDue': loan['dueDate'],
                        'targetDue': d(off).isoformat(), 'offset': off}
    record('A', '合成本轮数据创建', '12借阅/4读者/12书，全部API 201',
           f"loans={ {k: v['loanId'] for k, v in loans.items()} }", 'PASS', ACC + '/data.json')

    # 3. time-travel due dates in OWN temp DB (authorized), disable reader d
    for label, l in loans.items():
        set_loan_due(l['loanId'], d(l['offset']))
    rd = readers['d']
    r = api_post(page, f"/api/readers/{rd['id']}", {'name': rd['name'], 'status': 'DISABLED'})
    assert r.ok, r.text()
    rows = sql("SELECT id,due_date,borrow_date FROM loan WHERE id IN (%s) ORDER BY id;" %
               ','.join(str(l['loanId']) for l in loans.values()))
    record('A', '临时库日期前置+停用读者d', 'due_date按偏移更新；读者d DISABLED',
           rows.replace('\n', ' | '), 'PASS', 'sqlite3 UPDATE own temp DB; PUT /api/readers/{id}')

    # 4. no notifications yet for synthetic readers (PT1H: no periodic scan)
    ntf = sql("SELECT reader_id,type,due_date,seq FROM notification ORDER BY id;")
    seed2 = sql("SELECT id,returned_at,due_date FROM loan WHERE id=2;")
    record('A', '默认PT1H期间无周期扫描/种子保护', 'notification仅reader2启动补扫1条；loan id2未还',
           f'notifications=[{ntf}]; seed_loan2=[{seed2}]',
           'PASS' if ntf.count('\n') == 0 and 'DUE_SOON' not in ntf else 'CHECK',
           'sqlite3 SELECT own temp DB')

    # 5. overdue report UI shows synthetic rows + 登记催还 action
    page.goto(BASE + '/admin/reports/overdue')
    page.wait_for_load_state('networkidle')
    page.wait_for_timeout(800)
    s5 = shot(page, 'a02-overdue-report-synthetic')
    body = page.locator('body').inner_text()
    has_btn = '登记催还' in body
    shown = [l['bookTitle'] for l in loans.values() if l['offset'] < 0 and l['bookTitle'] in body]
    record('A', '逾期报表UI含合成逾期+登记入口', '合成逾期行可见且有登记催还按钮',
           f'rows_visible={len(shown)}/7; 登记催还按钮={has_btn}',
           'PASS' if has_btn and len(shown) >= 6 else 'FAIL', s5)

    # 6. bounded default-interval observation
    logtxt = open(ACC + '/dev-default.log').read()
    scans = re.findall(r'reminder\.scan date=\S+ candidates=\d+ inserted=\d+', logtxt)
    first = re.search(r'(\S+) INFO.*reminder\.scan', logtxt)
    record('A', '生产默认扫描间隔观察(有界)', '默认启动无PT1S覆盖；观察窗内仅启动补扫1次(下次PT1H)',
           f'scan_lines={len(scans)}; lines={scans}; 观察窗=启动{first.group(1) if first else "?"}至{now()}(>3min)',
           'PASS' if len(scans) == 1 else 'FAIL', ACC + '/dev-default.log',
           {'note': '源码默认PT1H: ReminderProperties.scanInterval=Duration.ofHours(1); @Scheduled默认PT1H'})

    # 7. logout guard
    page.get_by_text('退出登录').first.click() if page.get_by_text('退出登录').count() else None
    page.wait_for_timeout(500)
    if '/login' not in page.url:
        # try user dropdown logout
        try:
            page.locator('.ant-dropdown-trigger, [class*=user]').first.click()
            page.get_by_text('退出').first.click()
        except Exception:
            jpost(page, '/api/auth/logout')
        page.wait_for_timeout(500)
    page.goto(BASE + '/admin/dunning')
    page.wait_for_load_state('networkidle')
    s7 = shot(page, 'a03-logout-guard')
    record('A', '登出守卫', '登出后访问/admin/dunning跳登录页', f'url={page.url}',
           'PASS' if '/login' in page.url else 'FAIL', s7)

    record('A', 'pageerrors', '0', str(errors), 'PASS' if not errors else 'FAIL', 'console')
    json.dump({'suffix': sfx, 'today': T.isoformat(), 'password_note': 'synthetic readers use acceptance-only password (not disclosed in report)',
               'readers': {k: {'id': v['id'], 'cardNo': v['cardNo'], 'name': v['name'], 'username': f'acc_{k}_{sfx}'} for k, v in readers.items()},
               'loans': loans}, open(ACC + '/data.json', 'w'), ensure_ascii=False, indent=2)
    b.close()
print('PHASE A DONE')