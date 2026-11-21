"""B4 tail: list error-state via DISCLOSED network-layer simulation (real DB write
lock cannot fail WAL reads — prior FAIL was harness expectation error);
retry recovery; 390px admin pages."""
import re
from playwright.sync_api import sync_playwright
from acc_common import *

record('B4', '更正说明：列表错误态首测FAIL归因', '区分脚本预期错误与产品缺陷',
       '外部写锁不阻塞WAL读，GET案件列表正常返回数据是正确行为；错误态改用披露的网络层模拟验证；真实DB_BUSY 503已由真实锁POST证明',
       'NOT_APPLICABLE', '原行保留; lock-holder.log')

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_context(viewport={'width': 1440, 'height': 900}).new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    api_login(pg, 'admin', 'Admin@12345')
    pg.route('**/api/dunning-cases**', lambda route: route.abort('failed'))
    pg.goto(BASE + '/admin/dunning')
    pg.wait_for_timeout(9000)
    body = pg.locator('body').inner_text()
    n_retry = pg.get_by_role('button', name=re.compile(r'重\s*试')).count()
    s1 = shot(pg, 'b23-case-list-error-simulated')
    record('B4', 'AC-UI-07 列表错误态+重试(网络层模拟,披露)', '错误提示+重试按钮；非真实DB故障',
           f'错误文案={"失败" in body or "错误" in body or "繁忙" in body}; retry={n_retry}',
           'PASS' if n_retry >= 1 else 'FAIL', s1, {'method': 'SIMULATED network abort, disclosed'})
    pg.unroute('**/api/dunning-cases**')
    pg.get_by_role('button', name=re.compile(r'重\s*试')).first.click()
    pg.wait_for_timeout(1500)
    s2 = shot(pg, 'b26-case-list-recovered')
    record('B4', 'AC-UI-07 错误态重试恢复', '重试后真实数据加载', f'cases={pg.get_by_label("查看历史").count()}',
           'PASS' if pg.get_by_label('查看历史').count() >= 1 else 'FAIL', s2)
    m = b.new_context(viewport={'width': 390, 'height': 844}).new_page()
    api_login(m, 'admin', 'Admin@12345')
    widths = {}
    for path, name in [('/admin/reports/overdue', 'b27-overdue-390px'), ('/admin/dunning', 'b28-dunning-390px')]:
        m.goto(BASE + path)
        m.wait_for_load_state('networkidle')
        m.wait_for_timeout(900)
        widths[path] = m.evaluate('document.documentElement.scrollWidth')
        shot(m, name)
    record('B4', 'AC-UI-07 390px管理页无横向溢出', 'scrollWidth<=390', str(widths),
           'PASS' if all(w <= 390 for w in widths.values()) else 'FAIL', SHOTS + '/b27*,b28*')
    record('B4', 'pageerrors', '0', str(errs), 'PASS' if not errs else 'FAIL', 'console')
    b.close()
print('B4 TAIL DONE')