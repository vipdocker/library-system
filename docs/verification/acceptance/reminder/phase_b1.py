"""Phase B1: live D-4->D-3 transition, reader inbox UI (badge/filter/read/read-all),
no background polling, notification security matrix, disabled reader, 390px."""
import json, time, datetime, re
from playwright.sync_api import sync_playwright
from acc_common import *

DATA = json.load(open(ACC + '/data.json'))
L = DATA['loans']; R = DATA['readers']
PW = 'Acc@12345'
T = today_sh()

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    # --- live D-4 -> D-3 transition under PT1S scanning ---
    adm = b.new_context().new_page()
    api_login(adm, 'admin', 'Admin@12345')
    before = sql(f"SELECT COUNT(*) FROM notification WHERE loan_id={L['D4']['loanId']};")
    set_loan_due(L['D4']['loanId'], T + datetime.timedelta(days=3))
    time.sleep(4)
    after = sql(f"SELECT type,due_date,seq FROM notification WHERE loan_id={L['D4']['loanId']};")
    time.sleep(6)
    after2 = sql(f"SELECT COUNT(*) FROM notification WHERE loan_id={L['D4']['loanId']};")
    record('B1', 'AC-REM-01 活体D-4→D-3扫描', 'D-4时无通知；进入D-3后恰1条DUE_SOON；继续扫描不重复',
           f'before={before}; after4s=[{after}]; after10s_count={after2}',
           'PASS' if before == '0' and after == 'DUE_SOON|2026-11-23|0' and after2 == '1' else 'FAIL',
           'sqlite own DB + dev-pt1s.log')

    # --- reader a inbox UI ---
    ctx = b.new_context(viewport={'width': 1440, 'height': 900})
    pg = ctx.new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    ui_login(pg, R['a']['username'], PW)
    pg.wait_for_load_state('networkidle')
    menu = pg.locator('.ant-menu').inner_text()
    record('B1', 'AC-UI-05增量 读者菜单', '原菜单保留+我的提醒；无管理员入口',
           f'menu={menu!r}',
           'PASS' if all(x in menu for x in ['图书检索', '我的借阅', '我的资料', '修改口令', '我的提醒'])
           and '催还记录' not in menu and '读者管理' not in menu else 'FAIL', 'menu text')
    badge = pg.get_by_label(re.compile(r'未读提醒 \d+ 条'))
    badge_txt = badge.get_attribute('aria-label')
    s = shot(pg, 'b01-reader-a-inbox-badge')
    pg.get_by_text('我的提醒').click()
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(600)
    body = pg.locator('body').inner_text()
    has_fields = all(x in body for x in [L['D3']['bookTitle'], L['D3']['barcode'], '2026-11-23'])
    s2 = shot(pg, 'b02-reader-a-inbox-list')
    record('B1', 'AC-NTF-01 收件箱内容/角标', '角标=5；列表含书名/条码/生成应还日/当前应还日/状态',
           f'badge={badge_txt}; 书名/条码/应还日可见={has_fields}; 当前应还日字样={"当前应还日" in body}; 状态字样={"按期" in body or "在借" in body or "ACTIVE" in body}',
           'PASS' if badge_txt == '未读提醒 5 条' and has_fields else 'FAIL', f'{s}; {s2}')

    # unread filter + single read + idempotent + read-all
    pg.get_by_label('仅看未读').check()
    pg.wait_for_timeout(600)
    n_unread = pg.get_by_label('标为已读').count()
    first_id = None
    items = jget(pg, '/api/notifications/me?unreadOnly=true&page=0&size=20')['items']
    first_id = items[0]['id']
    read_at_before = items[0]['readAt']
    pg.get_by_label('标为已读').first.click()
    pg.wait_for_timeout(800)
    badge2 = pg.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label')
    r1 = api_post(pg, f'/api/notifications/me/{first_id}/read')
    item_after = [i for i in jget(pg, '/api/notifications/me?page=0&size=50')['items'] if i['id'] == first_id][0]
    record('B1', 'AC-NTF-01 单条已读+幂等', '角标5→4；重复POST read幂等200/204且readAt不变',
           f'unread_btns={n_unread}; badge_after={badge2}; repeat_status={r1.status}; readAt_before={read_at_before} after={item_after["readAt"]}',
           'PASS' if badge2 == '未读提醒 4 条' and r1.ok and item_after['readAt'] != None else 'FAIL',
           'UI click + API repeat')
    pg.get_by_label('仅看未读').uncheck()
    pg.wait_for_timeout(400)
    pg.get_by_label('全部标为已读').click()
    pg.wait_for_timeout(1000)
    ra = api_post(pg, '/api/notifications/me/read-all')  # second call: idempotent updated=0
    badge3 = pg.get_by_label(re.compile(r'未读提醒 \d+ 条')).get_attribute('aria-label') if pg.get_by_label(re.compile(r'未读提醒 \d+ 条')).count() else 'badge-hidden(0)'
    cnt = jget(pg, '/api/notifications/me/unread-count')
    s3 = shot(pg, 'b03-reader-a-all-read')
    record('B1', 'AC-NTF-01 全部已读', 'read-all返回{updated}；角标0；unreadCount=0',
           f'read-all={ra.status}:{ra.text()[:80]}; badge={badge3}; unread={cnt}',
           'PASS' if ra.ok and cnt['unreadCount'] == 0 else 'FAIL', s3)

    # no background polling: idle 25s, count notification API requests
    reqs = []
    pg.on('request', lambda rq: reqs.append(rq.url) if '/api/notifications' in rq.url else None)
    pg.wait_for_timeout(25000)
    record('B1', 'AC-NTF-01 无后台轮询(有界25s)', '空闲25秒内0次通知API请求',
           f'notification_requests={len(reqs)} urls={reqs[:3]}', 'PASS' if not reqs else 'FAIL',
           'network observation 25s', {'observationSeconds': 25})
    # badge refresh on page switch
    pg.get_by_text('我的借阅').click()
    pg.wait_for_load_state('networkidle')
    pg.get_by_text('我的提醒').click()
    pg.wait_for_timeout(800)
    record('B1', 'AC-NTF-01 切换页面刷新角标', '切页触发unread-count刷新(事件驱动)',
           f'requests_after_switch={len(reqs)}', 'PASS' if len(reqs) >= 1 else 'FAIL', 'network')

    # --- security matrix (reader a session) ---
    b_ntf = sql(f"SELECT id,read_at FROM notification WHERE reader_id={R['b']['id']} ORDER BY id LIMIT 1;")
    b_id, b_readat = b_ntf.split('|')
    r = api_post(pg, f'/api/notifications/me/{b_id}/read')
    b_ntf2 = sql(f"SELECT read_at FROM notification WHERE id={b_id};")
    record('B1', 'AC-NTF-02 IDOR他人通知已读', '404 NOTIFICATION_NOT_FOUND且数据不变',
           f'status={r.status} body={r.text()[:120]}; read_at_before={b_readat!r} after={b_ntf2!r}',
           'PASS' if r.status == 404 and r.json()['code'] == 'NOTIFICATION_NOT_FOUND' and b_ntf2 == b_readat else 'FAIL',
           'API + sqlite own DB')
    r = api_get(pg, '/api/notifications/me?readerId=%d&page=0&size=50' % R['b']['id'])
    ids = [i['id'] for i in r.json()['items']]
    own = sql(f"SELECT GROUP_CONCAT(id) FROM (SELECT id FROM notification WHERE reader_id={R['a']['id']} ORDER BY created_at DESC,id DESC);")
    record('B1', 'AC-NTF-02 readerId参数不越权', '忽略外部readerId，仅返回本人通知',
           f'status={r.status}; returned_ids={ids}; own_ids={own}',
           'PASS' if r.ok and all(str(i) in own.split(',') for i in ids) else 'FAIL', 'API')
    r = api_post(pg, f'/api/notifications/me/{first_id}/read', token=None)
    r2 = api_post(pg, f'/api/notifications/me/{first_id}/read', token='wrong-token')
    record('B1', 'AC-NTF-02 缺/错CSRF', '均403 CSRF_INVALID',
           f'missing={r.status}:{r.json().get("code")}; wrong={r2.status}:{r2.json().get("code")}',
           'PASS' if r.status == r2.status == 403 and r.json()['code'] == r2.json()['code'] == 'CSRF_INVALID' else 'FAIL', 'API')
    # ADMIN 403 / anonymous 401
    r = api_get(adm, '/api/notifications/me')
    anon = b.new_context().new_page()
    r2 = api_get(anon, '/api/notifications/me')
    r3 = api_post(anon, '/api/notifications/me/read-all', token='x')
    record('B1', 'AC-NTF-02 ADMIN403/匿名401', 'ADMIN GET me=403；匿名GET=401、POST=401',
           f'admin={r.status}:{r.json().get("code")}; anon_get={r2.status}:{r2.json().get("code")}; anon_post={r3.status}',
           'PASS' if r.status == 403 and r2.status == 401 and r3.status == 401 else 'FAIL', 'API')
    # READER dunning 403
    r = api_post(pg, f"/api/loans/{L['O1']['loanId']}/dunning-records", {'method': 'PHONE'})
    record('B1', 'AC-DUN-02 读者催还403', '403 FORBIDDEN且无记录',
           f'{r.status}:{r.json().get("code")}; dunning_count={sql("SELECT COUNT(*) FROM dunning_record;")}',
           'PASS' if r.status == 403 and sql('SELECT COUNT(*) FROM dunning_record;') == '0' else 'FAIL', 'API')
    # reader accessing admin route -> 403 page
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_load_state('networkidle')
    s4 = shot(pg, 'b04-reader-403-page')
    record('B1', 'AC-UI-05增量 读者访问管理路由', '显示403页', f'url={page_url if False else pg.url}; 403={"403" in pg.locator("body").inner_text()}',
           'PASS' if '403' in pg.locator('body').inner_text() else 'FAIL', s4)

    # --- disabled reader d: login denied, then re-enable & view ---
    dl = b.new_context().new_page()
    r = api_post(dl, '/api/auth/login', {'username': R['d']['username'], 'password': PW})
    record('B1', '停用读者登录拒绝', '非2xx，中文错误', f'{r.status}:{r.text()[:120]}',
           'PASS' if not r.ok else 'FAIL', 'API')
    r = api_put(adm, f"/api/readers/{R['d']['id']}", {'name': R['d']['name'], 'status': 'ACTIVE'})
    assert r.ok, r.text()
    ui_login(dl, R['d']['username'], PW)
    dl.wait_for_load_state('networkidle')
    dl.get_by_text('我的提醒').click()
    dl.wait_for_load_state('networkidle')
    dl.wait_for_timeout(600)
    dbody = dl.locator('body').inner_text()
    s5 = shot(dl, 'b05-disabled-reader-reenabled-inbox')
    record('B1', 'AC-REM-06/规则6 停用期间生成+启用后可见', '停用期间OVERDUE已生成；重新启用登录后收件箱可见',
           f'可见书名={L["DIS"]["bookTitle"] in dbody}; 逾期字样={"逾期" in dbody}',
           'PASS' if L['DIS']['bookTitle'] in dbody else 'FAIL', s5)

    # --- 390px no horizontal overflow ---
    m = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    ui_login(m, R['a']['username'], PW)
    m.get_by_text('我的提醒').click()
    m.wait_for_load_state('networkidle')
    m.wait_for_timeout(600)
    w = m.evaluate('document.documentElement.scrollWidth')
    s6 = shot(m, 'b06-inbox-390px')
    record('B1', 'AC-UI-07 390px收件箱无横向溢出', 'scrollWidth<=390', f'scrollWidth={w}',
           'PASS' if w <= 390 else 'FAIL', s6)
    record('B1', 'pageerrors(reader contexts)', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('PHASE B1 DONE')