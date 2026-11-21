"""B2 tail: reader b DUNNING visibility/no-note-leak, reach text, API error matrix."""
import json, re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
NOTE500 = '验' * 500; NOTE501 = '验' * 501
SECRET = '验收私密备注-读者不可见-9527'

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    api_login(pg, 'admin', 'Admin@12345')
    rp = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    ui_login(rp, R['b']['username'], 'Acc@12345')
    rp.goto(BASE + '/my/notifications')
    rp.wait_for_load_state('networkidle')
    rp.wait_for_timeout(900)
    rbody = rp.locator('body').inner_text()
    badge = rp.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label')
    s6 = shot(rp, 'b16-reader-b-dunning-notices')
    record('B2', 'AC-DUN-01/NTF-03 读者侧固定催还通知', 'DUNNING可见；不含私密/500字备注；角标=8(3逾期未读+5催还)',
           f'badge={badge}; 催还={"催还" in rbody}; 私密泄漏={SECRET in rbody}; 500字泄漏={NOTE500[:50] in rbody}',
           'PASS' if '催还' in rbody and SECRET not in rbody and NOTE500[:50] not in rbody and badge == '未读提醒 8 条' else 'FAIL', s6)
    record('B2', 'AC-NTF-03 登录触达说明(页面)', '页面含登录触达/送达限制说明',
           f'送达/触达字样={"送达" in rbody or "触达" in rbody}',
           'PASS' if ('送达' in rbody or '触达' in rbody) else 'FAIL', s6)

    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'SMS'})
    r2 = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE', 'note': NOTE501})
    r3 = api_post(pg, f"/api/loans/{L['D3']['loanId']}/dunning-records", {'method': 'PHONE'})
    r4 = api_post(pg, '/api/loans/99999/dunning-records', {'method': 'PHONE'})
    cnt = sql('SELECT COUNT(*) FROM dunning_record;')
    record('B2', 'AC-DUN-01/02 非法/未逾期/不存在', 'SMS400；501字400；未逾期409；不存在404；无残余(仍5条)',
           f'SMS={r.status}:{r.json().get("code")}; 501={r2.status}:{r2.json().get("code")}; 未逾期={r3.status}:{r3.json().get("code")}; 不存在={r4.status}:{r4.json().get("code")}; count={cnt}',
           'PASS' if r.status == r2.status == 400 and r3.status == 409 and r3.json()['code'] == 'LOAN_NOT_OVERDUE'
           and r4.status == 404 and r4.json()['code'] == 'LOAN_NOT_FOUND' and cnt == '5' else 'FAIL', 'API+sqlite')
    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE'}, token=None)
    r2 = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE'}, token='bad')
    cnt2 = sql('SELECT COUNT(*) FROM dunning_record;')
    anon = b.new_context().new_page()
    tok = csrf(anon)
    r3 = anon.request.post(BASE + f"/api/loans/{L['O1']['loanId']}/dunning-records",
                           data={'method': 'PHONE'}, headers={'X-XSRF-TOKEN': tok})
    record('B2', 'AC-DUN-01 催还CSRF/匿名', '缺/错CSRF403无记录；匿名有效CSRF401',
           f'missing={r.status}:{r.json().get("code")}; wrong={r2.status}:{r2.json().get("code")}; anon={r3.status}:{r3.json().get("code")}; count={cnt2}',
           'PASS' if r.status == r2.status == 403 and r.json()['code'] == 'CSRF_INVALID' and cnt2 == cnt and r3.status == 401 else 'FAIL', 'API')
    # case list sort & pagination API
    cases = jget(pg, '/api/dunning-cases?status=OPEN&page=0&size=2')
    la = [c['lastRecordedAt'] for c in cases['items']]
    record('B2', 'AC-DUN-04 案件分页/排序', 'size2分页；lastRecordedAt DESC；totalItems=4',
           f'totalItems={cases["totalItems"]}; totalPages={cases["totalPages"]}; sort_desc={la == sorted(la, reverse=True)}',
           'PASS' if cases['totalItems'] == 4 and len(cases['items']) == 2 and la == sorted(la, reverse=True) else 'FAIL', 'API')
    record('B2', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('B2 TAIL DONE')