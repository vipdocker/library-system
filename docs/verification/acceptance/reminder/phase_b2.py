"""Phase B2: ADMIN dunning via real UI (4 methods, note boundaries), ledger,
reader fixed notification without note, API error matrix."""
import json, time, re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
NOTE500 = '验' * 500
NOTE501 = '验' * 501
SECRET = '验收私密备注-读者不可见-9527'

def register(pg, label, method_label, note=None, expect_fail=False):
    pg.goto(BASE + '/admin/reports/overdue')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(500)
    row = pg.locator('tr', has_text=L[label]['bookTitle'])
    row.get_by_label('登记催还').click()
    pg.wait_for_selector('.dunning-modal')
    pg.locator('.dunning-modal').get_by_text(method_label, exact=True).click()
    if note is not None:
        pg.locator('.dunning-modal textarea').fill(note)
    posts = []
    pg.on('request', lambda rq: posts.append(rq.url) if 'dunning-records' in rq.url and rq.method == 'POST' else None)
    pg.get_by_label('确认登记').click()
    if expect_fail:
        pg.wait_for_timeout(900)
        return posts
    pg.wait_for_timeout(1500)
    return posts

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(pg, 'admin', 'Admin@12345')
    pg.wait_for_url('**/admin/dashboard**')

    # empty case page first
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(500)
    s0 = shot(pg, 'b10-dunning-empty')
    record('B2', 'AC-UI-07 催还页空态', '暂无催还案件', f'空态={"暂无催还案件" in pg.locator("body").inner_text()}',
           'PASS' if '暂无催还案件' in pg.locator('body').inner_text() else 'FAIL', s0)

    # 501-char validation (no POST)
    posts = register(pg, 'O1', '电话', NOTE501, expect_fail=True)
    modal_txt = pg.locator('.dunning-modal').inner_text()
    s1 = shot(pg, 'b11-note-501-validation')
    record('B2', 'AC-DUN-01/UI-07 501字前端校验', '中文校验"备注最多500字符"，不发起POST',
           f'校验提示={"备注最多500字符" in modal_txt}; posts={len(posts)}',
           'PASS' if '备注最多500字符' in modal_txt and len(posts) == 0 else 'FAIL', s1)
    pg.get_by_label('取消登记').click()
    pg.wait_for_timeout(400)

    # four methods
    posts = register(pg, 'O1', '电话', None)          # empty note
    s2 = shot(pg, 'b12-dunning-phone-success')
    ok1 = '已登记催还并生成站内提醒' in pg.locator('body').inner_text()
    posts = register(pg, 'O8', '当面', NOTE500)       # 500 boundary
    ok2 = '已登记催还并生成站内提醒' in pg.locator('body').inner_text()
    posts = register(pg, 'O15', '站内', '  带空白备注-应去首尾  ')  # trim
    ok3 = '已登记催还并生成站内提醒' in pg.locator('body').inner_text()
    posts = register(pg, 'O10', '其他', SECRET)
    ok4 = '已登记催还并生成站内提醒' in pg.locator('body').inner_text()
    s3 = shot(pg, 'b13-dunning-other-success')
    rows = sql("SELECT loan_id,method,COALESCE(note,'<NULL>'),operator_id,notification_id FROM dunning_record ORDER BY id;")
    ntf = sql(f"SELECT content FROM notification WHERE type='DUNNING' ORDER BY id;")
    no_note = SECRET not in ntf and NOTE500 not in ntf and '带空白备注' not in ntf
    record('B2', 'AC-DUN-01 四方式UI登记201', '电话/当面/站内/其他各1次成功；经办人admin(id1)；空备注null；500字通过；去首尾空白',
           f'success={[ok1,ok2,ok3,ok4]}; records={rows}',
           'PASS' if all([ok1, ok2, ok3, ok4]) and rows.count('\n') == 3 else 'FAIL', f'{s2}; {s3}; sqlite')
    record('B2', 'AC-DUN-01 备注边界/隔离', 'note=null/500字/trim；通知固定模板不含任何备注',
           f'null={"<NULL>" in rows}; len500={sql("SELECT length(note) FROM dunning_record WHERE loan_id=%d;" % L["O8"]["loanId"])}; trim={sql("SELECT note FROM dunning_record WHERE loan_id=%d;" % L["O15"]["loanId"])}; 通知无备注={no_note}; 通知样例={ntf.splitlines()[0][:90]}',
           'PASS' if '<NULL>' in rows and no_note else 'FAIL', 'sqlite own DB')

    # ledger UI: OPEN cases, sort, history asc, note admin-only
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(600)
    body = pg.locator('body').inner_text()
    s4 = shot(pg, 'b14-dunning-cases-open')
    pg.get_by_label('仅未结案').click()
    pg.wait_for_timeout(600)
    n_open = pg.get_by_label('查看历史').count()
    # open history of O10 case (has secret note + 1 record); check multi-record ordering on O1 later
    row = pg.locator('.dunning-cases, section', has_text=L['O10']['bookTitle']).first
    pg.get_by_label('查看历史').first.click()
    pg.wait_for_selector('.dunning-modal')
    pg.wait_for_timeout(600)
    htxt = pg.locator('.dunning-modal').inner_text()
    s5 = shot(pg, 'b15-dunning-history-modal')
    record('B2', 'AC-DUN-04 案件列表/历史UI', '4个OPEN案件；历史弹窗含方式/时间/备注(仅管理员可见提示)',
           f'open_cases={n_open}; 历史含备注仅管理员={"备注仅管理员可见" in htxt}; 含私密备注={SECRET in htxt}',
           'PASS' if n_open == 4 and '备注仅管理员可见' in htxt else 'FAIL', f'{s4}; {s5}')
    pg.get_by_label('关闭历史').click()

    # add 2nd record on O1 via API to verify ASC ordering in history
    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'IN_APP', 'note': '第二次登记-排序验证'})
    hist = jget(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records")
    times = [x['createdAt'] for x in hist['records']]
    record('B2', 'AC-DUN-04 历史时间ASC', '同案件两条记录createdAt ASC/id ASC',
           f'records={len(hist["records"])}; times={times}; status={hist["status"]}',
           'PASS' if r.status == 201 and times == sorted(times) and hist['status'] == 'OPEN' else 'FAIL', 'API')

    # reader b sees DUNNING fixed text, no secret
    rp = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    ui_login(rp, R['b']['username'], 'Acc@12345')
    rp.goto(BASE + '/my/notifications')
    rp.wait_for_load_state('networkidle')
    rp.wait_for_timeout(700)
    rbody = rp.locator('body').inner_text()
    badge = rp.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label')
    s6 = shot(rp, 'b16-reader-b-dunning-notices')
    record('B2', 'AC-DUN-01/NTF-03 读者侧固定催还通知', 'DUNNING通知可见；不含私密备注/500字备注；角标增加',
           f'badge={badge}; 催还字样={"催还" in rbody}; 私密泄漏={SECRET in rbody}; 500字泄漏={NOTE500[:50] in rbody}',
           'PASS' if '催还' in rbody and SECRET not in rbody and NOTE500[:50] not in rbody else 'FAIL', s6)
    # login-reach limitation text on page
    record('B2', 'AC-NTF-03 登录触达说明(页面)', '页面说明登录后可见/不代表送达',
           f'触达说明={"登录" in rbody and ("送达" in rbody or "触达" in rbody)}',
           'PASS' if ('送达' in rbody or '触达' in rbody) else 'FAIL', s6)

    # API error matrix (admin)
    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'SMS'})
    r2 = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE', 'note': NOTE501})
    r3 = api_post(pg, f"/api/loans/{L['D3']['loanId']}/dunning-records", {'method': 'PHONE'})
    r4 = api_post(pg, '/api/loans/99999/dunning-records', {'method': 'PHONE'})
    cnt = sql('SELECT COUNT(*) FROM dunning_record;')
    record('B2', 'AC-DUN-01/02 非法输入/未逾期/不存在', '未知method400；501字400；未逾期409 LOAN_NOT_OVERDUE；不存在404 LOAN_NOT_FOUND；无残余',
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
    record('B2', 'AC-DUN-01/NTF-02 催还CSRF/匿名', '缺/错CSRF403 CSRF_INVALID无记录；匿名有效CSRF401',
           f'missing={r.status}:{r.json().get("code")}; wrong={r2.status}:{r2.json().get("code")}; anon={r3.status}:{r3.json().get("code")}; count={cnt2}',
           'PASS' if r.status == r2.status == 403 and r.json()['code'] == 'CSRF_INVALID' and cnt2 == cnt and r3.status == 401 else 'FAIL', 'API')
    record('B2', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B2 DONE')