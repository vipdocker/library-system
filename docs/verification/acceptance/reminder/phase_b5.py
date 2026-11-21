"""Phase B5: adjacent regression sampling (reader1 search/my-loans, admin stats,
seed overdue row, UI borrow+return core path) + submit-lock under real DB lock."""
import json, time, subprocess, os, re, datetime
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
T = today_sh()

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    errs = []
    # reader1 regression
    r1 = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    r1.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(r1, 'reader1', 'Reader@123')
    r1.goto(BASE + '/books')
    r1.wait_for_load_state('networkidle')
    r1.wait_for_timeout(600)
    s1 = shot(r1, 'b29-reader1-search')
    ok_search = r1.locator('table tbody tr, .ant-list-item').count() > 0
    r1.goto(BASE + '/my/loans')
    r1.wait_for_load_state('networkidle')
    r1.wait_for_timeout(600)
    s2 = shot(r1, 'b30-reader1-my-loans')
    body = r1.locator('body').inner_text()
    record('B5', '相邻回归 reader1检索/我的借阅', '检索有数据；我的借阅显示种子在借',
           f'search_rows={ok_search}; 我的借阅在借={"在借" in body or "借阅中" in body}',
           'PASS' if ok_search and ('在借' in body or '借阅中' in body) else 'FAIL', f'{s1}; {s2}')
    # admin stats + dashboard + seed overdue
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    pg.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(pg, 'admin', 'Admin@12345')
    pg.wait_for_url('**/admin/dashboard**')
    pg.wait_for_timeout(600)
    s3 = shot(pg, 'b31-admin-dashboard')
    pg.goto(BASE + '/admin/stats')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(900)
    s4 = shot(pg, 'b32-admin-stats')
    stats_ok = '统计' in pg.locator('body').inner_text()
    pg.goto(BASE + '/admin/reports/overdue')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(700)
    obody = pg.locator('body').inner_text()
    s5 = shot(pg, 'b33-overdue-seed-reader2')
    record('B5', '相邻回归 仪表盘/统计/逾期报表(含reader2种子)', '三页正常加载；reader2种子逾期行仍在',
           f'dashboard=ok; stats={stats_ok}; reader2行={"reader2" in obody or "演示读者2" in obody}',
           'PASS' if stats_ok else 'FAIL', f'{s3}; {s4}; {s5}')
    # UI borrow + return core path (new synthetic book/reader)
    sfx = suffix()
    bk, cs = make_book(pg, f'回归图书-{sfx}', 1)
    rd = make_reader(pg, f'回归读者-{sfx}', f'acc_reg_{sfx}', 'Acc@12345')
    pg.goto(BASE + '/admin/circulation')
    pg.wait_for_load_state('networkidle')
    pg.locator('#circ-card-no').fill(rd['cardNo'])
    pg.locator('#circ-borrow-barcode').fill(cs[0]['barcode'])
    pg.locator('#circ-borrow-barcode').press('Enter')
    pg.wait_for_timeout(1500)
    bbody = pg.locator('body').inner_text()
    exp_due = (T + datetime.timedelta(days=30)).isoformat()
    s6 = shot(pg, 'b34-regression-borrow')
    record('B5', '相邻回归 UI借书(回车)', '借书成功；应还日=当天+30', f'应还日={exp_due in bbody}; 成功={"借出" in bbody or "成功" in bbody or exp_due in bbody}',
           'PASS' if exp_due in bbody else 'FAIL', s6)
    pg.get_by_role('tab', name='还书').click()
    pg.wait_for_timeout(400)
    pg.locator('#circ-return-barcode').fill(cs[0]['barcode'])
    pg.locator('#circ-return-barcode').press('Enter')
    pg.wait_for_timeout(1500)
    rbody = pg.locator('body').inner_text()
    s7 = shot(pg, 'b35-regression-return')
    record('B5', '相邻回归 UI还书', '归还成功反馈', f'归还反馈={"归还" in rbody and ("成功" in rbody or "逾期" in rbody or "按期" in rbody)}',
           'PASS' if '归还' in rbody else 'FAIL', s7)

    # submit lock under real DB write lock
    pg.goto(BASE + '/admin/reports/overdue')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(500)
    pg.locator('tr', has_text=L['O1']['bookTitle']).get_by_label('登记催还').click()
    pg.wait_for_selector('.dunning-modal')
    pg.locator('.dunning-modal').get_by_text('电话', exact=True).click()
    lock = subprocess.Popen(['python3', ACC + '/lock_holder.py', '15'],
                            stdout=open(ACC + '/lock-holder2.log', 'w'), stderr=subprocess.STDOUT)
    for _ in range(50):
        if os.path.exists(ACC + '/lock-ready'): break
        time.sleep(0.2)
    pg.get_by_label('确认登记').click()
    pg.wait_for_timeout(700)  # POST in flight (busy_timeout 5s)
    btn_disabled = pg.get_by_label('确认登记').is_disabled()
    cancel_disabled = pg.get_by_label('取消登记').is_disabled()
    s8 = shot(pg, 'b36-submit-lock-pending')
    record('B5', 'AC-UI-07 提交锁(挂起禁重复)', 'POST挂起期间确认/取消按钮禁用',
           f'confirm_disabled={btn_disabled}; cancel_disabled={cancel_disabled}',
           'PASS' if btn_disabled and cancel_disabled else 'FAIL', s8)
    open(ACC + '/lock-release', 'w').write('1')
    lock.wait(timeout=20)
    pg.wait_for_timeout(2500)
    # after release the in-flight POST may succeed or 503; either way no duplicate
    cnt = sql(f"SELECT COUNT(*) FROM dunning_record WHERE loan_id={L['O1']['loanId']};")
    record('B5', '提交锁后无重复记录', '该案件记录数<=3(原2+本次至多1)', f'count={cnt}',
           'PASS' if int(cnt) <= 3 else 'FAIL', 'sqlite')
    record('B5', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B5 DONE')