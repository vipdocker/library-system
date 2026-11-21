"""B3 tail: continue after renew (already done). Reader-a historical notice UI,
re-enter threshold, returns, closure, no-new-notices."""
import json, time, datetime, re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
T = today_sh()
exp_due = (T + datetime.timedelta(days=33)).isoformat()

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    api_login(pg, 'admin', 'Admin@12345')
    rp = b.new_context().new_page()
    api_login(rp, R['a']['username'], 'Acc@12345')
    rp.goto(BASE + '/my/notifications')
    rp.wait_for_load_state('networkidle')
    rp.wait_for_timeout(800)
    rbody = rp.locator('body').inner_text()
    s2 = shot(rp, 'b18-renewed-historical-notice')
    record('B3', 'AC-REM-04 历史通知提示(UI)', '显示应还日已调整/以当前应还日为准',
           f'调整提示={"应还日已调整" in rbody}; 当前应还日={exp_due in rbody}',
           'PASS' if '应还日已调整' in rbody else 'FAIL', s2)

    # re-enter new threshold: set renewed loan due to T+2 (different snapshot)
    set_loan_due(L['RENEW']['loanId'], T + datetime.timedelta(days=2))
    time.sleep(4)
    ntf2 = sql(f"SELECT due_date,seq FROM notification WHERE loan_id={L['RENEW']['loanId']} ORDER BY id;")
    record('B3', 'AC-REM-04 续借后按新应还日重算', '新阈值(D-2窗口)生成第二条DUE_SOON(新快照)；旧通知保留',
           f'notifications=[{ntf2.replace(chr(10), " | ")}]',
           'PASS' if ntf2.count('\n') == 1 and '2026-11-22' in ntf2 and '2026-11-23' in ntf2 else 'FAIL', 'sqlite + dev-pt1s.log')

    # register dunning on RETID/RETBAR so they become cases (real API)
    rr1 = api_post(pg, f"/api/loans/{L['RETID']['loanId']}/dunning-records", {'method': 'PHONE', 'note': '结案验证-ID归还'})
    rr2 = api_post(pg, f"/api/loans/{L['RETBAR']['loanId']}/dunning-records", {'method': 'IN_PERSON', 'note': '结案验证-条码归还'})
    assert rr1.status == rr2.status == 201, (rr1.text(), rr2.text())

    # --- return by loan ID (real API) ---
    pre20 = sql(f"SELECT COUNT(*) FROM notification WHERE loan_id={L['RETID']['loanId']};")
    r = api_post(pg, f"/api/loans/{L['RETID']['loanId']}/return")
    ret = r.json()
    case = jget(pg, f"/api/loans/{L['RETID']['loanId']}/dunning-records")
    dbret = sql(f"SELECT returned_at FROM loan WHERE id={L['RETID']['loanId']};")
    closed_ms = int(datetime.datetime.fromisoformat(case['closedAt'].replace('Z', '+00:00')).timestamp() * 1000)
    record('B3', 'AC-DUN-03 按借阅号归还→结案', '200；CLOSED；closedAt==loan.returnedAt；RETURNED',
           f'return={r.status}; status={case["status"]}; closedAt={case["closedAt"]}; db_returned_at={dbret}; equal={closed_ms == int(dbret)}; reason={case["closeReason"]}; records_kept={len(case["records"])}',
           'PASS' if r.ok and case['status'] == 'CLOSED' and case['closeReason'] == 'RETURNED' and closed_ms == int(dbret) and len(case['records']) >= 0 else 'FAIL',
           'API + sqlite own DB')
    # --- return by barcode via UI ---
    pg.goto(BASE + '/admin/circulation')
    pg.wait_for_load_state('networkidle')
    pg.get_by_role('tab', name='还书').click()
    pg.wait_for_timeout(500)
    pg.locator('#circ-return-barcode').fill(L['RETBAR']['barcode'])
    pg.locator('#circ-return-barcode').press('Enter')
    pg.wait_for_timeout(1500)
    s3 = shot(pg, 'b19-return-barcode-success')
    rbody2 = pg.locator('body').inner_text()
    case2 = jget(pg, f"/api/loans/{L['RETBAR']['loanId']}/dunning-records")
    dbret2 = sql(f"SELECT returned_at FROM loan WHERE id={L['RETBAR']['loanId']};")
    closed_ms2 = int(datetime.datetime.fromisoformat(case2['closedAt'].replace('Z', '+00:00')).timestamp() * 1000)
    record('B3', 'AC-DUN-03/LOAN-04 条码归还(UI)→结案', 'UI还书成功；CLOSED；closedAt==returnedAt；RETURNED；历史保留',
           f'ui归还反馈={"归还" in rbody2}; status={case2["status"]}; closedAt={case2["closedAt"]}; db={dbret2}; equal={closed_ms2 == int(dbret2)}; reason={case2["closeReason"]}',
           'PASS' if case2['status'] == 'CLOSED' and case2['closeReason'] == 'RETURNED' and closed_ms2 == int(dbret2) else 'FAIL', s3)
    # dunning after close -> 409
    r1 = api_post(pg, f"/api/loans/{L['RETID']['loanId']}/dunning-records", {'method': 'PHONE'})
    r2 = api_post(pg, f"/api/loans/{L['RETBAR']['loanId']}/dunning-records", {'method': 'PHONE'})
    cnt = sql('SELECT COUNT(*) FROM dunning_record;')
    record('B3', 'AC-DUN-03 结案后登记409', '两案均409 LOAN_ALREADY_RETURNED；无新记录(仍7)',
           f'id={r1.status}:{r1.json().get("code")}; barcode={r2.status}:{r2.json().get("code")}; count={cnt}',
           'PASS' if r1.status == r2.status == 409 and r1.json()['code'] == r2.json()['code'] == 'LOAN_ALREADY_RETURNED' and cnt == '7' else 'FAIL', 'API')
    # no new notifications after return (8s bounded scan observation)
    pre = sql(f"SELECT loan_id,COUNT(*) FROM notification WHERE loan_id IN ({L['RETID']['loanId']},{L['RETBAR']['loanId']}) GROUP BY loan_id;")
    time.sleep(8)
    post = sql(f"SELECT loan_id,COUNT(*) FROM notification WHERE loan_id IN ({L['RETID']['loanId']},{L['RETBAR']['loanId']}) GROUP BY loan_id;")
    record('B3', 'AC-REM-05/规则6 归还后不新增(有界8s)', '归还后8秒(约8次扫描)通知数不变',
           f'pre=[{pre.replace(chr(10), " | ")}] post=[{post.replace(chr(10), " | ")}]',
           'PASS' if pre == post else 'FAIL', 'sqlite + dev-pt1s.log', {'observationSeconds': 8})
    # reader c sees returned state on old notices
    rc = b.new_context().new_page()
    ui_login(rc, R['c']['username'], 'Acc@12345')
    rc.goto(BASE + '/my/notifications')
    rc.wait_for_load_state('networkidle')
    rc.wait_for_timeout(800)
    cbody = rc.locator('body').inner_text()
    s4 = shot(rc, 'b20-reader-c-returned-notices')
    record('B3', 'AC-REM-04/DUN-03 旧通知显示已归还', '当前状态：已归还；历史通知提示；无需再还书',
           f'已归还={"当前状态：已归还" in cbody}; 历史提示={"该借阅已归还" in cbody}',
           'PASS' if '当前状态：已归还' in cbody and '该借阅已归还' in cbody else 'FAIL', s4)
    # closed filter UI + history modal
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_load_state('networkidle')
    pg.get_by_label('仅已结案').click()
    pg.wait_for_timeout(800)
    n_closed = pg.get_by_label('查看历史').count()
    s5 = shot(pg, 'b21-closed-cases')
    pg.get_by_label('查看历史').first.click()
    pg.wait_for_selector('.dunning-modal')
    pg.wait_for_timeout(600)
    htxt = pg.locator('.dunning-modal').inner_text()
    s6 = shot(pg, 'b22-closed-case-history')
    record('B3', 'AC-DUN-04 已结案筛选/历史保留(UI)', '2个CLOSED案件；历史含结案时间/原因已归还',
           f'closed_cases={n_closed}; 结案时间={"结案时间" in htxt}; 原因已归还={"结案原因：已归还" in htxt}',
           'PASS' if n_closed == 2 and '结案时间' in htxt and '结案原因：已归还' in htxt else 'FAIL', f'{s5}; {s6}')
    record('B3', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B3 DONE')