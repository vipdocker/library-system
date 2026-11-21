"""B1 fix: exact locators; single-read flow on reader b; remaining security/UI checks.
Prior '单条已读' FAIL was harness substring-locator error (clicked 全部标为已读), not product."""
import json, time, re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
PW = 'Acc@12345'

record('B1', '更正说明：单条已读首测FAIL归因', '区分脚本缺陷与产品缺陷',
       'get_by_label("标为已读")子串匹配命中"全部标为已读"致全读；非产品缺陷；用exact定位在读者b重测',
       'NOT_APPLICABLE', '本脚本; observations.jsonl原行保留', {'note': 'harness error, retest below'})

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    adm = b.new_context().new_page()
    api_login(adm, 'admin', 'Admin@12345')
    ctx = b.new_context(viewport={'width': 1440, 'height': 900})
    pg = ctx.new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(pg, R['b']['username'], PW)
    pg.wait_for_load_state('networkidle')
    pg.get_by_role('menu').get_by_text('我的提醒').click()
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(600)
    body = pg.locator('body').inner_text()
    badge = pg.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label')
    s1 = shot(pg, 'b07-reader-b-inbox')
    record('B1', 'AC-NTF-01/REM-02 收件箱字段(读者b)', '角标4；书名/条码/生成应还日/当前应还日/当前状态：逾期未还',
           f'badge={badge}; 书名={L["O1"]["bookTitle"] in body}; 条码={L["O1"]["barcode"] in body}; 当前应还日={"当前应还日" in body}; 当前状态逾期未还={"当前状态：逾期未还" in body}',
           'PASS' if badge == '未读提醒 4 条' and '当前状态：逾期未还' in body and L['O1']['bookTitle'] in body else 'FAIL', s1)

    pg.get_by_label('仅看未读').check()
    pg.wait_for_timeout(600)
    n_btns = pg.get_by_label('标为已读', exact=True).count()
    items = jget(pg, '/api/notifications/me?unreadOnly=true&page=0&size=20')['items']
    fid = items[0]['id']
    pg.get_by_label('标为已读', exact=True).first.click()
    pg.wait_for_timeout(900)
    badge2 = pg.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label')
    ra_before = sql(f"SELECT read_at FROM notification WHERE id={fid};")
    r1 = api_post(pg, f'/api/notifications/me/{fid}/read')
    ra_after = sql(f"SELECT read_at FROM notification WHERE id={fid};")
    s2 = shot(pg, 'b08-reader-b-single-read')
    record('B1', 'AC-NTF-01 单条已读+幂等(重测)', '未读按钮4；点击后角标4→3；重复read幂等且readAt不变',
           f'btns={n_btns}; badge_after={badge2}; repeat={r1.status}; readAt {ra_before}->{ra_after}',
           'PASS' if n_btns == 4 and badge2 == '未读提醒 3 条' and r1.status in (200, 204) and ra_before == ra_after and ra_before != '' else 'FAIL', s2)
    # unread filter shows only unread
    n_list = pg.locator('.ant-list-item, [class*=notification]').count()
    unread_api = jget(pg, '/api/notifications/me?unreadOnly=true&page=0&size=20')
    record('B1', 'AC-NTF-01 仅未读过滤+分页形状', 'unreadOnly仅3条；分页items/page/size/totalItems/totalPages',
           f'totalItems={unread_api["totalItems"]}; keys={sorted(unread_api.keys())}',
           'PASS' if unread_api['totalItems'] == 3 and all(k in unread_api for k in ['items', 'page', 'size', 'totalItems', 'totalPages']) else 'FAIL', 'API')
    # page switch refresh
    reqs = []
    pg.on('request', lambda rq: reqs.append(rq.url) if '/api/notifications' in rq.url else None)
    pg.get_by_role('menu').get_by_text('我的借阅').click()
    pg.wait_for_load_state('networkidle')
    pg.get_by_role('menu').get_by_text('我的提醒').click()
    pg.wait_for_timeout(900)
    record('B1', 'AC-NTF-01 切换页面刷新角标', '切页触发通知API事件刷新', f'reqs={len(reqs)}',
           'PASS' if len(reqs) >= 1 else 'FAIL', 'network')

    # security matrix
    a_ntf = sql(f"SELECT id,read_at FROM notification WHERE reader_id={R['a']['id']} ORDER BY id LIMIT 1;")
    a_id, a_readat = a_ntf.split('|')
    r = api_post(pg, f'/api/notifications/me/{a_id}/read')
    a2 = sql(f"SELECT read_at FROM notification WHERE id={a_id};")
    record('B1', 'AC-NTF-02 IDOR(b读a通知)', '404 NOTIFICATION_NOT_FOUND且数据不变',
           f'{r.status}:{r.json().get("code")}; read_at {a_readat}->{a2}',
           'PASS' if r.status == 404 and r.json()['code'] == 'NOTIFICATION_NOT_FOUND' and a2 == a_readat else 'FAIL', 'API+sqlite')
    r = api_get(pg, f'/api/notifications/me?readerId={R["a"]["id"]}&page=0&size=50')
    ids = [i['id'] for i in r.json()['items']]
    own = sql(f"SELECT GROUP_CONCAT(id) FROM (SELECT id FROM notification WHERE reader_id={R['b']['id']} ORDER BY created_at DESC,id DESC);")
    record('B1', 'AC-NTF-02 readerId不越权', '仅本人通知', f'ids={ids}; own={own}',
           'PASS' if r.ok and all(str(i) in own.split(',') for i in ids) else 'FAIL', 'API')
    r = api_post(pg, f'/api/notifications/me/{fid}/read', token=None)
    r2 = api_post(pg, f'/api/notifications/me/{fid}/read', token='wrong-token')
    record('B1', 'AC-NTF-02 缺/错CSRF', '403 CSRF_INVALID', f'{r.status}:{r.json().get("code")} / {r2.status}:{r2.json().get("code")}',
           'PASS' if r.status == r2.status == 403 and r.json()['code'] == r2.json()['code'] == 'CSRF_INVALID' else 'FAIL', 'API')
    r = api_get(adm, '/api/notifications/me')
    anon = b.new_context().new_page()
    r2 = api_get(anon, '/api/notifications/me')
    r3 = api_post(anon, '/api/notifications/me/read-all', token='x')
    record('B1', 'AC-NTF-02 ADMIN403/匿名401', 'ADMIN403；匿名401', f'admin={r.status}; anon_get={r2.status}; anon_post={r3.status}',
           'PASS' if r.status == 403 and r2.status == 401 and r3.status == 401 else 'FAIL', 'API')
    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE'})
    record('B1', 'AC-DUN-02 读者催还403', '403且无记录', f'{r.status}:{r.json().get("code")}; count={sql("SELECT COUNT(*) FROM dunning_record;")}',
           'PASS' if r.status == 403 and sql('SELECT COUNT(*) FROM dunning_record;') == '0' else 'FAIL', 'API')
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_load_state('networkidle')
    s4 = shot(pg, 'b04-reader-403-page')
    record('B1', 'AC-UI-05增量 读者访问管理路由403页', '403页', f'url={pg.url}',
           'PASS' if '403' in pg.locator('body').inner_text() else 'FAIL', s4)
    # reader self-renew absent
    pg.goto(BASE + '/my/loans')
    pg.wait_for_load_state('networkidle')
    s5 = shot(pg, 'b09-my-loans-no-renew')
    record('B1', 'AC-UI-05增量 读者无自助续借', '我的借阅无续借按钮', f'续借按钮数={pg.get_by_role("button", name="续借").count()}',
           'PASS' if pg.get_by_role('button', name='续借').count() == 0 else 'FAIL', s5)

    # disabled reader d
    dl = b.new_context().new_page()
    r = api_post(dl, '/api/auth/login', {'username': R['d']['username'], 'password': PW})
    record('B1', '停用读者登录拒绝', '非2xx中文错误', f'{r.status}:{r.text()[:100]}', 'PASS' if not r.ok else 'FAIL', 'API')
    r = api_put(adm, f"/api/readers/{R['d']['id']}", {'name': R['d']['name'], 'status': 'ACTIVE'})
    ui_login(dl, R['d']['username'], PW)
    dl.wait_for_load_state('networkidle')
    dl.get_by_role('menu').get_by_text('我的提醒').click()
    dl.wait_for_load_state('networkidle')
    dl.wait_for_timeout(600)
    dbody = dl.locator('body').inner_text()
    s6 = shot(dl, 'b05-disabled-reader-reenabled-inbox')
    record('B1', '规则6 停用期间生成+启用后可见', 'DIS通知可见', f'书名可见={L["DIS"]["bookTitle"] in dbody}',
           'PASS' if L['DIS']['bookTitle'] in dbody else 'FAIL', s6)

    # 390px
    m = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    ui_login(m, R['b']['username'], PW)
    m.get_by_role('menu').get_by_text('我的提醒').click()
    m.wait_for_load_state('networkidle')
    m.wait_for_timeout(600)
    w = m.evaluate('document.documentElement.scrollWidth')
    s7 = shot(m, 'b06-inbox-390px')
    record('B1', 'AC-UI-07 390px收件箱', 'scrollWidth<=390', f'scrollWidth={w}', 'PASS' if w <= 390 else 'FAIL', s7)
    record('B1', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B1 FIX DONE')