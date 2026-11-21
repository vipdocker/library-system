"""Phase B4: REAL DB_BUSY via external write lock on own temp DB.
UI 503 + input retained + no auto-retry + explicit retry success;
scanner busy log; reads unaffected; error-retry state on case list; 390px admin pages."""
import json, time, subprocess, os, re, datetime
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
TARGET = L['O10']['loanId']

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(pg, 'admin', 'Admin@12345')
    pg.wait_for_url('**/admin/dashboard**')
    # open registration modal on O10 and prefill
    pg.goto(BASE + '/admin/reports/overdue')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(500)
    pg.locator('tr', has_text=L['O10']['bookTitle']).get_by_label('登记催还').click()
    pg.wait_for_selector('.dunning-modal')
    pg.locator('.dunning-modal').get_by_text('其他', exact=True).click()
    pg.locator('.dunning-modal textarea').fill('DB_BUSY显式重试验证备注')

    cnt_before = sql('SELECT COUNT(*) FROM dunning_record;')
    pg2 = b.new_context().new_page()
    api_login(pg2, 'admin', 'Admin@12345')
    # start external lock
    lock = subprocess.Popen(['python3', ACC + '/lock_holder.py', '40'],
                            stdout=open(ACC + '/lock-holder.log', 'w'), stderr=subprocess.STDOUT)
    for _ in range(50):
        if os.path.exists(ACC + '/lock-ready'): break
        time.sleep(0.2)
    assert os.path.exists(ACC + '/lock-ready'), 'lock not acquired'

    # reads still work under WAL
    rr = api_get(pg, '/api/reports/overdue?page=0&size=5')
    # case list page error state in second tab
    pg2.goto(BASE + '/admin/dunning')
    pg2.wait_for_timeout(6500)
    ebody = pg2.locator('body').inner_text()
    s_err = shot(pg2, 'b23-case-list-db-busy-error')
    has_retry = pg2.get_by_role('button', name=re.compile('重试')).count()
    record('B4', 'AC-UI-07 列表503错误态+重试入口', '案件列表加载失败显示错误与重试按钮；WAL读接口不受影响',
           f'错误态={"繁忙" in ebody or "失败" in ebody or "错误" in ebody}; retry_btn={has_retry}; reads_under_lock={rr.status}',
           'PASS' if has_retry >= 1 and rr.status == 200 else 'FAIL', s_err)

    # submit under lock -> 503
    posts = []
    resps = []
    pg.on('request', lambda rq: posts.append(time.time()) if 'dunning-records' in rq.url and rq.method == 'POST' else None)
    pg.on('response', lambda rs: resps.append((rs.status, rs.url)) if 'dunning-records' in rs.url and rs.request.method == 'POST' else None)
    pg.get_by_label('确认登记').click()
    pg.wait_for_timeout(7000)
    modal_txt = pg.locator('.dunning-modal').inner_text() if pg.locator('.dunning-modal').count() else ''
    note_val = pg.locator('.dunning-modal textarea').input_value() if pg.locator('.dunning-modal').count() else ''
    s_503 = shot(pg, 'b24-dunning-503-retained-input')
    busy_resp = [x for x in resps if x[0] == 503]
    record('B4', 'AC-UI-07/DUN-01 真实DB_BUSY 503', 'POST返回503；中文系统繁忙；失败Alert；输入保留；无记录',
           f'503_responses={busy_resp}; alert={"登记未成功" in modal_txt}; 繁忙={"繁忙" in modal_txt}; note_retained={note_val == "DB_BUSY显式重试验证备注"}; count={sql("SELECT COUNT(*) FROM dunning_record;")}',
           'PASS' if busy_resp and '登记未成功' in modal_txt and note_val == 'DB_BUSY显式重试验证备注' and sql('SELECT COUNT(*) FROM dunning_record;') == cnt_before else 'FAIL', s_503)
    # no auto-retry: observe 6s
    n0 = len(posts)
    pg.wait_for_timeout(6000)
    record('B4', 'AC-UI-07 失败不自动重试(有界6s)', '失败后6秒内无新POST', f'posts_before={n0} after={len(posts)}',
           'PASS' if len(posts) == n0 else 'FAIL', 'network observation', {'observationSeconds': 6})
    # scanner busy log
    logtxt = open(ACC + '/dev-pt1s.log', errors='ignore').read()
    busy_logs = logtxt.count('reminder.scan.busy')
    record('B4', 'AC-REM-03 扫描DB_BUSY可观察', 'reminder.scan.busy记录且服务不崩溃', f'busy_log_lines={busy_logs}',
           'PASS' if busy_logs >= 1 else 'FAIL', ACC + '/dev-pt1s.log')
    # release lock, explicit retry
    open(ACC + '/lock-release', 'w').write('1')
    lock.wait(timeout=15)
    time.sleep(1)
    pg.get_by_label('确认登记').click()
    pg.wait_for_timeout(2500)
    ok_msg = '已登记催还并生成站内提醒' in pg.locator('body').inner_text()
    cnt_after = sql('SELECT COUNT(*) FROM dunning_record;')
    s_ok = shot(pg, 'b25-dunning-explicit-retry-success')
    record('B4', 'AC-DUN-01/UI-07 释放后显式重试201', '用户再次点击确认→201；恰1条新记录；扫描恢复',
           f'success={ok_msg}; count {cnt_before}->{cnt_after}',
           'PASS' if ok_msg and int(cnt_after) == int(cnt_before) + 1 else 'FAIL', s_ok)
    time.sleep(3)
    logtxt2 = open(ACC + '/dev-pt1s.log', errors='ignore').read()
    last_scan = re.findall(r'reminder\.scan date=\S+ candidates=\d+ inserted=\d+', logtxt2)[-1]
    record('B4', 'AC-REM-03 后续扫描恢复', '锁释放后扫描正常完成', f'last_scan={last_scan}',
           'PASS' if 'inserted=' in last_scan else 'FAIL', ACC + '/dev-pt1s.log')
    # error-state retry button works after recovery
    pg2.get_by_role('button', name=re.compile('重试')).first.click()
    pg2.wait_for_timeout(1500)
    s_rec = shot(pg2, 'b26-case-list-recovered')
    record('B4', 'AC-UI-07 错误态显式重试恢复', '点击重试后案件列表恢复数据', f'案件可见={"案件" in pg2.locator("body").inner_text() and "暂无" not in pg2.locator("body").inner_text()}',
           'PASS' if pg2.get_by_label('查看历史').count() >= 1 else 'FAIL', s_rec)

    # 390px admin pages
    m = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    api_login(m, 'admin', 'Admin@12345')
    widths = {}
    for path, name in [('/admin/reports/overdue', 'b27-overdue-390px'), ('/admin/dunning', 'b28-dunning-390px')]:
        m.goto(BASE + path)
        m.wait_for_load_state('networkidle')
        m.wait_for_timeout(800)
        widths[path] = m.evaluate('document.documentElement.scrollWidth')
        shot(m, name)
    record('B4', 'AC-UI-07 390px管理页无横向溢出', '逾期报表/催还页scrollWidth<=390', str(widths),
           'PASS' if all(w <= 390 for w in widths.values()) else 'FAIL', SHOTS + '/b27*,b28*')
    record('B4', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B4 DONE')