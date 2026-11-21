"""B1 tail: anonymous POST with VALID csrf (expect 401 per contract priority),
390px inbox via direct URL (Sider collapses at lg breakpoint)."""
import re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
R = DATA['readers']
record('B1', '更正说明：匿名POST首测403归因', '契约规定缺/错CSRF优先403',
       '首测匿名POST带错误token=x→403 CSRF_INVALID符合契约优先级；应带有效CSRF无会话测401；重测',
       'NOT_APPLICABLE', 'reminder-increment.md §3错误约定; 原行保留')

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    anon = b.new_context().new_page()
    tok = csrf(anon)  # valid token, no session
    r = anon.request.post(BASE + '/api/notifications/me/read-all', headers={'X-XSRF-TOKEN': tok})
    r2 = anon.request.get(BASE + '/api/notifications/me')
    record('B1', 'AC-NTF-02 匿名401(有效CSRF重测)', 'GET=401 AUTH_REQUIRED；POST(有效CSRF无会话)=401',
           f'post={r.status}:{r.json().get("code")}; get={r2.status}:{r2.json().get("code")}',
           'PASS' if r.status == 401 and r2.status == 401 and r.json()['code'] == 'AUTH_REQUIRED' else 'FAIL', 'API')
    m = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    errs = []
    m.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(m, R['b']['username'], 'Acc@12345')
    m.goto(BASE + '/my/notifications')
    m.wait_for_load_state('networkidle')
    m.wait_for_timeout(800)
    w = m.evaluate('document.documentElement.scrollWidth')
    s = shot(m, 'b06-inbox-390px')
    record('B1', 'AC-UI-07 390px收件箱无横向溢出', 'scrollWidth<=390', f'scrollWidth={w}',
           'PASS' if w <= 390 else 'FAIL', s)
    record('B1', 'pageerrors(B1全部读者上下文)', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('B1 TAIL DONE')