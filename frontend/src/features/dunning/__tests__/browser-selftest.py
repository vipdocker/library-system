import json
import os
import re
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[5]
OUT = ROOT / 'frontend/test-results/rem-fe-dunning'
BASE = 'http://127.0.0.1:15197'
DB = Path(os.environ['LIB_DB_PATH']).resolve()
assert DB.parent == OUT.resolve() and not str(DB).startswith(str(ROOT / 'backend/data'))
OUT.mkdir(exist_ok=True)
results, traffic, page_errors = [], [], []
secret = '<script>仅管理员可见的自测备注</script>'

def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def passed(name, mode='REAL', **data):
    results.append({'name': name, 'mode': mode, 'result': 'PASS', **data})

def shot(page, name):
    page.screenshot(path=str(OUT / (name + '.png')), full_page=page.viewport_size['width'] > 600 and page.get_by_role('dialog').count() == 0, animations='disabled')

def watch(page, mode):
    page.on('pageerror', lambda error: page_errors.append({'mode': mode, 'message': str(error)}))
    page.on('response', lambda response: traffic.append({'mode': mode, 'method': response.request.method, 'path': response.url.split(BASE)[-1], 'status': response.status}) if response.url.startswith(BASE + '/api/') else None)

def get(page, path):
    response = page.request.get(BASE + path)
    assert response.ok, (path, response.status, response.text())
    return response.json()

def post(page, path, body=None, expected=201):
    response = page.request.get(BASE + '/api/auth/csrf')
    assert response.status == 204
    token = next(cookie['value'] for cookie in page.context.cookies() if cookie['name'] == 'XSRF-TOKEN')
    response = page.request.post(BASE + path, data=body, headers={'X-XSRF-TOKEN': token})
    assert response.status == expected, (path, response.status, response.text())
    traffic.append({'mode': 'REAL_API_FIXTURE_OR_ASSERTION', 'method': 'POST', 'path': path, 'status': response.status})
    return response.json() if expected != 204 else None

def login(page, username):
    page.goto(BASE + '/login')
    page.wait_for_load_state('networkidle')
    page.get_by_label('用户名').fill(username)
    page.get_by_label('密码', exact=True).fill('Admin@12345' if username == 'admin' else 'Reader@123')
    page.get_by_role('button', name=re.compile(r'登\s*录')).click()
    expect(page).not_to_have_url(BASE + '/login')
    page.wait_for_load_state('networkidle')

def nav(page, label):
    page.get_by_role('menuitem').filter(has_text=label).click()
    page.wait_for_load_state('networkidle')

def register_ui(page, item, method, note, double=False):
    row = page.get_by_role('row').filter(has_text=item['copyBarcode'])
    row.get_by_role('button', name='登记催还', exact=True).click()
    dialog = page.get_by_role('dialog')
    expect(dialog.get_by_text('借阅号：' + str(item['loanId']), exact=True)).to_be_visible()
    dialog.get_by_role('radio', name=method, exact=True).check()
    dialog.get_by_label('管理员备注').fill(note)
    with page.expect_response(lambda r: r.url.endswith('/api/loans/' + str(item['loanId']) + '/dunning-records') and r.request.method == 'POST') as response:
        button = dialog.get_by_role('button', name='确认登记', exact=True)
        if double:
            button.evaluate('(b) => { b.click(); b.click(); }')
        else:
            button.click()
    data = response.value.json()
    assert response.value.status == 201
    expect(page.get_by_text('已登记催还并生成站内提醒，不代表已送达', exact=True).last).to_be_visible()
    expect(page.get_by_role('dialog')).not_to_be_visible()
    page.wait_for_load_state('networkidle')
    return data

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    contexts = []
    try:
        admin_context = browser.new_context(viewport={'width': 1440, 'height': 1000})
        contexts.append(admin_context)
        admin = admin_context.new_page()
        watch(admin, 'REAL')
        admin.goto(BASE + '/admin/dunning')
        expect(admin.get_by_label('用户名')).to_be_visible()
        shot(admin, 'real-login-guard')
        login(admin, 'admin')
        nav(admin, '催还记录')
        expect(admin.get_by_text('暂无催还案件', exact=True)).to_be_visible()
        shot(admin, 'real-empty-cases')
        nav(admin, '逾期报表')
        overdue = get(admin, '/api/reports/overdue?page=0&size=100')['items']
        seed = next(item for item in overdue if item['reader']['cardNo'] == 'R000002')
        row = admin.get_by_role('row').filter(has_text=seed['copyBarcode'])
        row.get_by_role('button', name='登记催还', exact=True).click()
        dialog = admin.get_by_role('dialog')
        dialog.get_by_role('button', name='确认登记', exact=True).click()
        expect(dialog.get_by_text('请选择催还方式', exact=True)).to_be_visible()
        dialog.get_by_role('radio', name='电话', exact=True).check()
        dialog.get_by_label('管理员备注').fill('字' * 501)
        dialog.get_by_role('button', name='确认登记', exact=True).click()
        expect(dialog.get_by_text('备注最多500字符', exact=True)).to_be_visible()
        shot(admin, 'real-form-validation')
        assert not get(admin, '/api/loans/' + str(seed['loanId']) + '/dunning-records')['records']
        dialog.get_by_role('button', name='取消登记', exact=True).click()
        first = register_ui(admin, seed, '电话', '  ' + secret + '  ', double=True)
        second = register_ui(admin, seed, '当面', '  \n ')
        third = register_ui(admin, seed, '站内', '字' * 500)
        fourth = register_ui(admin, seed, '其他', '多次登记历史自测')
        assert first['operator']['username'] == 'admin' and first['note'] == secret
        assert second['note'] is None and len(third['note']) == 500
        hist = get(admin, '/api/loans/' + str(seed['loanId']) + '/dunning-records')
        assert len(hist['records']) == 4 and [r['method'] for r in hist['records']] == ['PHONE', 'IN_PERSON', 'IN_APP', 'OTHER']
        assert [(r['createdAt'], r['id']) for r in hist['records']] == sorted((r['createdAt'], r['id']) for r in hist['records'])
        save('real-registration-dtos.json', {'records': [first, second, third, fourth], 'history': hist})
        nav(admin, '催还记录')
        expect(admin.get_by_text('4 次', exact=True)).to_be_visible()
        admin.get_by_role('button', name='查看历史', exact=True).click()
        dialog = admin.get_by_role('dialog')
        expect(dialog.get_by_text(secret, exact=True)).to_be_visible()
        assert dialog.locator('script').count() == 0
        expect(dialog.get_by_text('无备注', exact=True)).to_be_visible()
        shot(admin, 'real-history-open')
        dialog.get_by_role('button', name='关闭历史', exact=True).click()
        passed('管理员四种方式登记→201、500字符/可空备注、真实经办人、每借阅单案件、多记录稳定排序、防重复')

        reader_context = browser.new_context(viewport={'width': 1440, 'height': 1000})
        contexts.append(reader_context)
        reader = reader_context.new_page()
        watch(reader, 'REAL_READER')
        login(reader, 'reader2')
        nav(reader, '我的提醒')
        notices = get(reader, '/api/notifications/me?page=0&size=100')['items']
        dunning = [notice for notice in notices if notice['type'] == 'DUNNING' and notice['loanId'] == seed['loanId']]
        assert len(dunning) == 4
        assert {notice['id'] for notice in dunning} == {r['notificationId'] for r in hist['records']}
        assert all(secret not in json.dumps(notice, ensure_ascii=False) and '多次登记历史自测' not in json.dumps(notice, ensure_ascii=False) and '字' * 500 not in json.dumps(notice, ensure_ascii=False) for notice in dunning)
        assert len({notice['content'] for notice in dunning}) == 1
        expect(reader.get_by_text('催还通知', exact=True)).to_have_count(4)
        assert secret not in reader.locator('body').inner_text()
        shot(reader, 'real-reader-fixed-notices-no-note')
        save('real-reader-dunning-notices.json', dunning)
        reader.goto(BASE + '/admin/dunning')
        expect(reader.get_by_text('无权限访问该页面', exact=True)).to_be_visible()
        assert reader.request.get(BASE + '/api/dunning-cases').status == 403
        passed('真实读者登录收到四条固定模板通知，无管理员备注；读者页面/API角色守卫')

        today = datetime.now(timezone(timedelta(hours=8))).date()
        suffix = str(int(datetime.now().timestamp() * 1000))
        book = post(admin, '/api/books', {'title': '催还分页自测-' + suffix, 'author': '本机测试', 'isbn': '978' + suffix[-9:] + str((-sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate('978' + suffix[-9:]))) % 10), 'initialCopies': 21})
        copies = get(admin, '/api/books/' + str(book['id']) + '/copies')
        fixtures = []
        for index, copy in enumerate(copies):
            reader_fixture = post(admin, '/api/readers', {'name': '分页读者' + str(index), 'username': 'dun_' + suffix + '_' + str(index), 'initialPassword': 'Reader@123'})
            loan = post(admin, '/api/loans', {'readerCardNo': reader_fixture['cardNo'], 'copyBarcode': copy['barcode']})
            fixtures.append({'loan': loan, 'reader': reader_fixture, 'copy': copy})
        connection = sqlite3.connect(DB)
        with connection:
            for fixture in fixtures:
                connection.execute('UPDATE loan SET borrow_date=?, due_date=?, borrowed_at=? WHERE id=? AND returned_at IS NULL', (str(today - timedelta(days=40)), str(today - timedelta(days=10)), int(datetime.now().timestamp() * 1000) - 40 * 86400000, fixture['loan']['id']))
        connection.close()
        for fixture in fixtures:
            fixture['record'] = post(admin, '/api/loans/' + str(fixture['loan']['id']) + '/dunning-records', {'method': 'OTHER', 'note': None})
        save('isolated-pagination-fixtures.json', {'database': str(DB), 'precondition': 'API创建独立读者/副本/借阅，仅临时库将借出/应还日设置为逾期，再通过真实API登记', 'fixtures': fixtures})
        admin.get_by_role('button', name='刷新案件', exact=True).click()
        expect(admin.get_by_text('共 22 个案件', exact=True)).to_be_visible()
        page1 = get(admin, '/api/dunning-cases?page=0&size=20')
        assert len(page1['items']) == 20 and page1['totalItems'] == 22
        assert [(item['lastRecordedAt'], item['loanId']) for item in page1['items']] == sorted(((item['lastRecordedAt'], item['loanId']) for item in page1['items']), reverse=True)
        with admin.expect_response(lambda r: '/api/dunning-cases?' in r.url and 'page=1' in r.url) as page2_response:
            admin.locator('.ant-pagination-item-2').click()
        assert page2_response.value.json()['page'] == 1
        expect(admin.locator('.dunning-case')).to_have_count(2)
        shot(admin, 'real-cases-page2')
        admin.get_by_role('radio', name='仅未结案', exact=True).check()
        expect(admin.locator('.dunning-case')).to_have_count(20)
        admin.get_by_role('radio', name='仅已结案', exact=True).check()
        expect(admin.get_by_text('暂无催还案件', exact=True)).to_be_visible()
        admin.get_by_role('radio', name='全部案件', exact=True).check()
        expect(admin.locator('.dunning-case')).to_have_count(20)
        passed('真实22案件、分页、OPEN/CLOSED/全部过滤、稳定最近时间/借阅号排序')

        nav(admin, '借阅记录')
        admin.get_by_label('副本条码', exact=True).fill(seed['copyBarcode'])
        admin.get_by_label('副本条码', exact=True).press('Enter')
        row = admin.get_by_role('row').filter(has_text=seed['copyBarcode'])
        row.get_by_role('button', name=re.compile(r'还\s*书')).click()
        with admin.expect_response(lambda r: r.url.endswith('/api/loans/' + str(seed['loanId']) + '/return') and r.request.method == 'POST') as returned_by_id:
            admin.locator('.ant-popconfirm').get_by_role('button', name=re.compile(r'还\s*书')).click()
        assert returned_by_id.value.status == 200
        nav(admin, '催还记录')
        admin.get_by_role('radio', name='仅已结案', exact=True).check()
        expect(admin.locator('.dunning-case')).to_have_count(1)
        admin.get_by_role('button', name='查看历史', exact=True).click()
        dialog = admin.get_by_role('dialog')
        expect(dialog.get_by_text('已结案', exact=True)).to_be_visible()
        expect(dialog.get_by_text('结案原因：已归还', exact=True)).to_be_visible()
        closed_by_id = get(admin, '/api/loans/' + str(seed['loanId']) + '/dunning-records')
        connection = sqlite3.connect(DB)
        returned_at = connection.execute('SELECT returned_at FROM loan WHERE id=?', (seed['loanId'],)).fetchone()[0]
        connection.close()
        assert int(datetime.fromisoformat(closed_by_id['closedAt'].replace('Z', '+00:00')).timestamp() * 1000) == returned_at
        shot(admin, 'real-closed-return-by-id')
        dialog.get_by_role('button', name='关闭历史', exact=True).click()
        assert all(item['loanId'] != seed['loanId'] for item in get(admin, '/api/reports/overdue?page=0&size=100')['items'])
        post(admin, '/api/loans/' + str(seed['loanId']) + '/dunning-records', {'method': 'PHONE'}, expected=409)
        passed('真实借阅列表按ID归还→缓存重新获取CLOSED、结案时间严格等于returnedAt、报表移除、再登记409')

        barcode_fixture = fixtures[-1]
        nav(admin, '流通柜台')
        admin.get_by_role('tab', name='还书', exact=True).click()
        admin.get_by_label('副本条码', exact=True).fill(barcode_fixture['copy']['barcode'])
        with admin.expect_response(lambda r: r.url.endswith('/api/loans/return-by-barcode') and r.request.method == 'POST') as returned_by_barcode:
            admin.get_by_label('副本条码', exact=True).press('Enter')
        assert returned_by_barcode.value.status == 200
        nav(admin, '催还记录')
        admin.get_by_role('radio', name='仅已结案', exact=True).check()
        expect(admin.locator('.dunning-case')).to_have_count(2)
        case = admin.locator('.dunning-case').filter(has_text=barcode_fixture['copy']['barcode'])
        case.get_by_role('button', name='查看历史', exact=True).click()
        dialog = admin.get_by_role('dialog')
        expect(dialog.get_by_text('结案原因：已归还', exact=True)).to_be_visible()
        closed_by_barcode = get(admin, '/api/loans/' + str(barcode_fixture['loan']['id']) + '/dunning-records')
        connection = sqlite3.connect(DB)
        returned_at = connection.execute('SELECT returned_at FROM loan WHERE id=?', (barcode_fixture['loan']['id'],)).fetchone()[0]
        connection.close()
        assert int(datetime.fromisoformat(closed_by_barcode['closedAt'].replace('Z', '+00:00')).timestamp() * 1000) == returned_at
        shot(admin, 'real-closed-return-by-barcode')
        save('real-closure-dtos.json', {'byId': closed_by_id, 'byBarcode': closed_by_barcode})
        dialog.get_by_role('button', name='关闭历史', exact=True).click()
        nav(admin, '逾期报表')
        expect(admin.get_by_text('共 20 条逾期', exact=True)).to_be_visible()
        passed('真实流通柜台按条码归还→服务器自动结案、时间/原因和报表正确')

        admin.set_viewport_size({'width': 390, 'height': 844})
        nav(admin, '催还记录')
        expect(admin.locator('.dunning-case').first).to_be_visible()
        metrics = admin.evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})')
        assert metrics['width'] == metrics['scrollWidth'] == 390
        shot(admin, 'real-cases-390px')
        admin.get_by_role('button', name='查看历史', exact=True).first.click()
        expect(admin.get_by_role('dialog').get_by_text('共 1 条记录', exact=True)).to_be_visible()
        bounds = admin.get_by_role('dialog').bounding_box()
        assert bounds['x'] >= 0 and bounds['x'] + bounds['width'] <= 390
        shot(admin, 'real-history-390px')
        admin.get_by_role('button', name='关闭历史', exact=True).click()
        nav(admin, '逾期报表')
        admin.get_by_role('button', name='登记催还', exact=True).first.click()
        expect(admin.get_by_role('dialog').get_by_label('管理员备注')).to_be_visible()
        bounds = admin.get_by_role('dialog').bounding_box()
        assert bounds['x'] >= 0 and bounds['x'] + bounds['width'] <= 390
        shot(admin, 'real-registration-390px')
        admin.get_by_role('button', name='取消登记', exact=True).click()
        passed('真实390px案件/历史/表单布局，无文档横向溢出', metrics=metrics)

        mock_context = browser.new_context(viewport={'width': 1440, 'height': 1000})
        contexts.append(mock_context)
        mock = mock_context.new_page()
        watch(mock, 'MOCK_BOUNDARY')
        login(mock, 'admin')
        current_case = get(mock, '/api/dunning-cases?status=OPEN&page=0&size=20')['items'][0]
        state = {'list': 'hold', 'post': None, 'history': None, 'held': [], 'posts': 0}
        def error(route, status, code, fields=None):
            route.fulfill(status=status, content_type='application/json', body=json.dumps({'code': code, 'status': status, 'message': '', 'fieldErrors': fields or [], 'details': {}, 'path': route.request.url.split(BASE)[-1], 'timestamp': '2026-11-20T00:00:00Z'}))
        def boundary(route):
            pathname = route.request.url.split(BASE)[-1].split('?')[0]
            if pathname == '/api/dunning-cases':
                if state['list'] == 'hold': state['held'].append(route); return
                if state['list'] == 'busy': error(route, 503, 'DB_BUSY'); return
                if state['list'] == 'empty': route.fulfill(status=200, content_type='application/json', body=json.dumps({'items': [], 'page': 0, 'size': 20, 'totalItems': 0, 'totalPages': 0})); return
            if pathname.endswith('/dunning-records'):
                if route.request.method == 'POST':
                    state['posts'] += 1
                    if state['post'] == 'hold': state['held'].append(route); return
                    if state['post']:
                        status, code, fields = state['post']; error(route, status, code, fields); return
                elif state['history']:
                    status, code = state['history']; error(route, status, code); return
            route.continue_()
        mock.route(BASE + '/api/**', boundary)
        nav_link = mock.get_by_role('menuitem').filter(has_text='催还记录')
        nav_link.click()
        expect(mock.get_by_text('正在加载催还案件…', exact=True)).to_be_visible()
        shot(mock, 'mock-list-loading')
        state['list'] = 'busy'
        for route in state['held']: error(route, 503, 'DB_BUSY')
        state['held'].clear()
        expect(mock.get_by_text('系统繁忙，请稍后再试', exact=True)).to_be_visible()
        shot(mock, 'mock-list-503')
        state['list'] = 'empty'
        mock.get_by_role('button', name=re.compile(r'重\s*试')).click()
        expect(mock.get_by_text('暂无催还案件', exact=True)).to_be_visible()
        state['list'] = None
        mock.get_by_role('button', name='刷新案件', exact=True).click()
        expect(mock.locator('.dunning-case').first).to_be_visible()
        state['history'] = (404, 'LOAN_NOT_FOUND')
        mock.get_by_role('button', name='查看历史', exact=True).first.click()
        dialog = mock.get_by_role('dialog')
        expect(dialog.get_by_text('借阅记录不存在', exact=True)).to_be_visible()
        shot(mock, 'mock-history-404')
        state['history'] = None
        dialog.get_by_role('button', name=re.compile(r'重\s*试')).click()
        expect(dialog.get_by_text('共 1 条记录', exact=True)).to_be_visible()
        state['history'] = (503, 'DB_BUSY')
        dialog.get_by_role('button', name='刷新历史', exact=True).click()
        expect(dialog.get_by_text('系统繁忙，请稍后再试', exact=True)).to_be_visible()
        assert not dialog.locator('.dunning-history-state').count()
        dialog.get_by_role('button', name='关闭历史', exact=True).click()
        passed('加载/空态/列表503显式重试/历史404和503不展示过期缓存', 'MOCK')

        nav(mock, '逾期报表')
        mock.get_by_role('button', name='登记催还', exact=True).first.click()
        dialog = mock.get_by_role('dialog')
        dialog.get_by_role('radio', name='电话', exact=True).check()
        dialog.get_by_label('管理员备注').fill('失败路径保留备注')
        for status, code, text in [(409, 'LOAN_ALREADY_RETURNED', '该借阅已归还，请勿重复操作'), (409, 'LOAN_NOT_OVERDUE', '该借阅尚未逾期，不能登记催还'), (503, 'DB_BUSY', '系统繁忙，请稍后再试'), (403, 'CSRF_INVALID', '页面安全令牌已失效，请重试'), (500, 'INTERNAL_ERROR', '系统内部错误，请稍后再试')]:
            state['post'] = (status, code, [])
            before = state['posts']
            dialog.get_by_role('button', name='确认登记', exact=True).click()
            expect(dialog.get_by_text(text, exact=True)).to_be_visible()
            expect(dialog.get_by_label('管理员备注')).to_have_value('失败路径保留备注')
            assert state['posts'] == before + 1
            shot(mock, 'mock-post-' + code.lower())
        state['post'] = (400, 'VALIDATION_FAILED', [{'field': 'note', 'message': '自测服务端备注错误'}, {'field': 'method', 'message': '自测服务端方式错误'}])
        dialog.get_by_role('button', name='确认登记', exact=True).click()
        expect(dialog.get_by_text('自测服务端备注错误', exact=True)).to_be_visible()
        expect(dialog.get_by_text('自测服务端方式错误', exact=True)).to_be_visible()
        shot(mock, 'mock-server-field-errors')
        state['post'] = 'hold'
        before = state['posts']
        dialog.get_by_role('button', name='确认登记', exact=True).click()
        expect(dialog.get_by_role('button', name='确认登记', exact=True)).to_be_disabled()
        expect(dialog.get_by_role('button', name='取消登记', exact=True)).to_be_disabled()
        dialog.locator('form').evaluate('(f) => { f.dispatchEvent(new Event("submit", {bubbles:true,cancelable:true})); f.dispatchEvent(new Event("submit", {bubbles:true,cancelable:true})); }')
        assert state['posts'] == before + 1
        shot(mock, 'mock-post-pending-no-repeat')
        state['post'] = (503, 'DB_BUSY', [])
        for route in state['held']: error(route, 503, 'DB_BUSY')
        state['held'].clear()
        expect(dialog.get_by_text('系统繁忙，请稍后再试', exact=True)).to_be_visible()
        dialog.get_by_role('button', name='刷新逾期报表', exact=True).click()
        expect(mock.get_by_role('dialog')).not_to_be_visible()
        before = len(traffic)
        mock.wait_for_timeout(1200)
        assert not [t for t in traffic[before:] if '/dunning-cases' in t['path'] or '/dunning-records' in t['path']]
        passed('409已还/未逾期、503、CSRF、500、400字段错误、保留输入、提交锁和显式刷新；无自动POST重试/轮询', 'MOCK')
        save('browser-report.json', {'results': results, 'database': str(DB), 'ports': {'backend': 18097, 'frontend': 15197}, 'pageErrors': page_errors, 'traffic': traffic})
        assert not page_errors, page_errors
        print(json.dumps({'result': 'PASS', 'checks': len(results), 'real': len([r for r in results if r['mode'] == 'REAL']), 'mock': len([r for r in results if r['mode'] == 'MOCK']), 'database': str(DB)}, ensure_ascii=False))
    except Exception as error:
        save('browser-failure-' + DB.stem + '.json', {'error': str(error), 'results': results, 'traffic': traffic, 'pageErrors': page_errors, 'database': str(DB)})
        for index, context in enumerate(contexts):
            for page_index, page in enumerate(context.pages):
                try: shot(page, 'failure-' + str(index) + '-' + str(page_index))
                except Exception: pass
        raise
    finally:
        for context in contexts: context.close()
        browser.close()
