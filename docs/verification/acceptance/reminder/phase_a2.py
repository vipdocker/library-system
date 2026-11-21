"""Phase A continuation: reconstruct data.json from own temp DB, disable reader d,
finish default-interval observation / overdue UI / logout guard."""
import json, re
from playwright.sync_api import sync_playwright
from acc_common import *

T = today_sh()
labels = {11: ('a', 'D4', 4), 12: ('a', 'D3', 3), 13: ('a', 'D2', 2), 14: ('a', 'D0', 0),
          15: ('a', 'RENEW', 3), 16: ('b', 'O1', -1), 17: ('b', 'O8', -8), 18: ('b', 'O15', -15),
          19: ('b', 'O10', -10), 20: ('c', 'RETID', -2), 21: ('c', 'RETBAR', -3), 22: ('d', 'DIS', -5)}
sfx = 'qwnwy4'
rows = sql("SELECT l.id,l.reader_id,r.name,r.card_no,c.barcode,b.id,b.title FROM loan l "
           "JOIN reader r ON r.id=l.reader_id JOIN book_copy c ON c.id=l.copy_id "
           "JOIN book b ON b.id=c.book_id WHERE l.id BETWEEN 11 AND 22 ORDER BY l.id;")
loans = {}
readers = {}
for line in rows.split('\n'):
    lid, rid, rname, card, barcode, bid, btitle = line.split('|')
    lid = int(lid)
    rk, label, off = labels[lid]
    readers[rk] = {'id': int(rid), 'cardNo': card, 'name': rname, 'username': f'acc_{rk}_{sfx}'}
    loans[label] = {'loanId': lid, 'readerKey': rk, 'readerId': int(rid), 'cardNo': card,
                    'username': f'acc_{rk}_{sfx}', 'bookTitle': btitle, 'bookId': int(bid),
                    'barcode': barcode, 'targetDue': (T + __import__('datetime').timedelta(days=off)).isoformat(),
                    'offset': off}
json.dump({'suffix': sfx, 'today': T.isoformat(), 'readers': readers, 'loans': loans},
          open(ACC + '/data.json', 'w'), ensure_ascii=False, indent=2)

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    page = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    ui_login(page, 'admin', 'Admin@12345')
    page.wait_for_url('**/admin/dashboard**', timeout=15000)

    rd = readers['d']
    r = api_put(page, f"/api/readers/{rd['id']}", {'name': rd['name'], 'status': 'DISABLED'})
    record('A', '停用读者d(真实API)', '200且status=DISABLED', f'{r.status} {r.text()[:120]}',
           'PASS' if r.ok and r.json()['status'] == 'DISABLED' else 'FAIL', 'PUT /api/readers/8')

    ntf = sql("SELECT reader_id,type,due_date,seq FROM notification ORDER BY id;")
    seed2 = sql("SELECT id,returned_at,due_date FROM loan WHERE id=2;")
    ok = ntf == '2|OVERDUE|2026-11-10|2'
    record('A', '默认PT1H期间通知状态/种子保护', '仅reader2启动补扫OVERDUE seq2一条；loan id2未还',
           f'notifications=[{ntf}]; seed_loan2=[{seed2}]', 'PASS' if ok and seed2.split('|')[1] == '' else 'FAIL',
           'sqlite3 SELECT own temp DB')

    page.goto(BASE + '/admin/reports/overdue')
    page.wait_for_load_state('networkidle')
    page.wait_for_timeout(800)
    s5 = shot(page, 'a02-overdue-report-synthetic')
    body = page.locator('body').inner_text()
    shown = [l['bookTitle'] for l in loans.values() if l['offset'] < 0 and l['bookTitle'] in body]
    record('A', '逾期报表UI含合成逾期+登记入口', '7条合成逾期可见且有登记催还按钮',
           f'rows_visible={len(shown)}/7; 登记催还={"登记催还" in body}',
           'PASS' if '登记催还' in body and len(shown) == 7 else 'FAIL', s5)

    logtxt = open(ACC + '/dev-default.log').read()
    scans = re.findall(r'reminder\.scan date=\S+ candidates=\d+ inserted=\d+', logtxt)
    record('A', '生产默认扫描间隔观察(有界)', '默认启动(无PT1S)观察窗内仅启动补扫1次',
           f'scan_lines={len(scans)}; {scans}; 窗口=2026-11-20T11:29:57+08至{now()}(约5min)',
           'PASS' if len(scans) == 1 else 'FAIL', ACC + '/dev-default.log',
           {'note': '源码默认PT1H: ReminderProperties=Duration.ofHours(1); @Scheduled(fixedDelayString默认PT1H)'})

    jpost(page, '/api/auth/logout')
    page.goto(BASE + '/admin/dunning')
    page.wait_for_load_state('networkidle')
    s7 = shot(page, 'a03-logout-guard')
    record('A', '登出守卫', '登出后访问/admin/dunning跳登录页', f'url={page.url}',
           'PASS' if '/login' in page.url else 'FAIL', s7)
    record('A', 'pageerrors', '0', str(errors), 'PASS' if not errors else 'FAIL', 'console')
    b.close()
print('PHASE A2 DONE')