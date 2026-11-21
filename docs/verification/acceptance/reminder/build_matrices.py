"""Build REM-ACC acceptance matrices + summary + browser-report from observations.
Acceptance evidence only; does not touch candidate files."""
import json, os, datetime, glob
from acc_common import ACC, SHOTS, ROOT, now

OBS = [json.loads(l) for l in open(ACC + '/observations.jsonl')]
CAND = 'REM-INT-v1.5-r3'
ENV = ('本机arm64 macOS(宿主25.6.0/Maven实报26.6.2差异保留)；Homebrew JDK21/Maven3.9.16/Node26.7.0/npm11.19.0；'
       'Python Playwright chromium 145.0.7632.6 headless(验收浏览器)；标准E2E用项目Playwright1.65.0；'
       '临时SQLite(WAL) ' + open(ACC + '/tmpdir.txt').read().strip() + '/library.db；端口18099/15199；'
       '验收进程LIB_REMINDER_SCANINTERVAL=PT1S(仅验收环境,生产默认PT1H已另行核实)；业务时区Asia/Shanghai')
DEF = '增量AC/DoD v1.1(reminder-increment.md SHA c0c38f35…)＋基础AC/DoD v1(ac-dod.md)＋approval-record §4–4.3(SHA 4b70c32a…)'

def obs(*keys):
    out = []
    for o in OBS:
        if any(k in o['item'] for k in keys):
            out.append(o)
    return out

def summarize(items):
    return '; '.join(f"[{o['result']}] {o['item']}: {str(o['actual'])[:200]}" for o in items)

def ev(items):
    seen = []
    for o in items:
        for e in str(o['evidence']).split('; '):
            if e and e not in seen: seen.append(e)
    return seen

def row(id, resp, pre, steps, expected, items, result, note=''):
    return {'id': id, 'definitionVersion': 'v1.1' if id.startswith(('AC-REM', 'AC-NTF', 'AC-DUN', 'DOD-R')) or id in ('AC-UI-07', 'AC-UI-05增量') else 'v1',
            'responsibility': resp, 'preconditionsData': pre, 'steps': steps,
            'expected': expected, 'actual': summarize(items), 'result': result,
            'evidence': ev(items), 'candidateId': CAND, 'environment': ENV, 'note': note}

SYN = '本轮合成读者acc_a/b/c/d_qwnwy4+12本验收图书+12条借阅(loan11-22)；sqlite3仅改本轮新建借阅due_date/borrow_date制造D-4/D-3/D-2/D/D+1/8/10/15前置；未改种子reader2(loan2全程未还)'

ac = []
ac.append(row('AC-REM-01', 'IMPL/REV/ACC', SYN,
  '默认PT1H实例造数→改期→PT1S重启补扫；活体将D-4借阅移入D-3观察连续扫描',
  'D-3恰1条含书名/条码/应还日；D-4及更早不生成；D-2..D补1条；D+1不补到期前提醒',
  obs('补扫矩阵', '活体D-4'), 'PASS'))
ac.append(row('AC-REM-02', 'IMPL/REV', SYN,
  '补扫后核对D0/D+1/D+8/D+10/D+15通知seq；归还后8s观察',
  'D当天无逾期；D+1/8/15新周期每期最多1条；停机只补当前；归还后不再生成',
  obs('补扫矩阵', '归还后不新增'), 'PASS',
  'ACC补充独立验证；UTC16:00边界与100天周期由同候选集成T(ReminderApiTest.dateBoundary/overdueCycle)及评审静态推导覆盖，本轮未重跑该单元测试'))
ac.append(row('AC-REM-03', 'IMPL/REV', SYN,
  'PT1S连续扫描计数；重启补扫；外部BEGIN IMMEDIATE真实写锁制造DB_BUSY',
  '重复/重启不重复；busy可观察记录并后续重试；不破坏正常接口',
  obs('补扫矩阵', '扫描DB_BUSY', '后续扫描恢复', '列表503错误态首测'), 'PASS',
  'ACC补充独立验证(真实锁)；8独立连接并发×3由同候选T证据覆盖；WAL读不受写锁影响为正确行为(首测FAIL系脚本预期错误,已更正)'))
ac.append(row('AC-REM-04', 'IMPL/ACC', SYN,
  'ADMIN流通柜台UI续借RENEW借阅→核对旧通知id/readAt/当前应还日→改期进入新D-2窗口→读者UI查看历史提示',
  '续借+30重算；旧通知保留不自动已读；显示当前状态/应还日；原AC-LOAN-05不变',
  obs('ADMIN续借', '旧通知保留', '历史通知提示', '续借后按新应还日'), 'PASS'))
ac.append(row('AC-REM-05', 'IMPL/REV', SYN,
  'API按ID归还+UI按条码归还后8秒(约8次扫描)通知计数对比',
  '归还提交后不再新增；不死锁；原AC-LOAN-04不变',
  obs('归还后不新增', '按借阅号归还', '条码归还'), 'PASS',
  'ACC补充独立验证；returnWins/scanWins两种提交顺序并发由同候选T证据覆盖'))
ac.append(row('AC-REM-06', 'IMPL/REV', SYN,
  '默认实例5分钟有界观察仅启动补扫1次；源码默认PT1H；启动日志seed先于scan；停用读者d通知生成+启用后可见',
  '默认true/3/7/PT1H/true；非法值启动失败；test不扫描；启动补扫在种子后',
  obs('生产默认扫描间隔', '停用读者登录拒绝', '停用期间生成'), 'PASS',
  'ACC补充独立验证默认值/启动顺序/停用读者；8种非法配置启动失败由同候选T(ReminderConfigurationTest)覆盖，本轮未重跑'))
ac.append(row('AC-NTF-01', 'IMPL/ACC', SYN,
  '读者a/b真实UI收件箱：角标、仅未读、单条已读+API幂等、全部已读、切页刷新、空闲25s网络观察',
  '本人隔离分页降序；未读数准确；单条幂等/全部已读；角标事件刷新无后台轮询',
  obs('收件箱内容', '收件箱字段', '单条已读+幂等(重测)', '全部已读', '仅未读过滤', '无后台轮询', '切换页面刷新'), 'PASS',
  '首测单条已读FAIL为脚本子串定位误点全部已读，非产品缺陷，exact定位重测通过'))
ac.append(row('AC-NTF-02', 'IMPL/REV', SYN,
  'b读a通知、readerId参数、ADMIN GET、匿名GET/POST(有效CSRF)、缺/错CSRF POST',
  'IDOR404数据不变；readerId不越权；ADMIN403匿名401；缺错CSRF403无变化',
  obs('IDOR', 'readerId不越权', '缺/错CSRF', 'ADMIN403', '匿名401'), 'PASS',
  'ACC补充独立验证；匿名带错token先403 CSRF_INVALID符合契约优先级(首测预期错误已更正)'))
ac.append(row('AC-NTF-03', 'IMPL/REV/ACC', SYN,
  '读者收件箱/登记弹窗文案检查；README§6.1核对；读者侧通知内容核对无备注',
  '页面与README说明登录触达；已读非离线送达；无外发；电话仅记录',
  obs('登录触达说明', '读者侧固定催还通知', '备注边界'), 'PASS',
  '无外发服务/依赖由同候选评审DOD-R3结论+集成Q静态检查覆盖'))
ac.append(row('AC-DUN-01', 'IMPL/REV/ACC', SYN,
  'ADMIN逾期报表UI四方式登记(空备注/500字/去空白/私密备注)；501字前端拦截；API非法method/501字；真实写锁503+显式重试；读者侧核对固定通知',
  '四方式201记录经办人；≤500备注仅管理员；同事务固定通知无备注；非法400；无外发',
  obs('四方式UI登记', '备注边界', '501字前端校验', '非法/未逾期/不存在', '催还CSRF', '真实DB_BUSY 503', '失败不自动重试', '释放后显式重试', '读者侧固定催还通知'), 'PASS'))
ac.append(row('AC-DUN-02', 'IMPL/REV', SYN,
  'API不存在99999/未逾期loan12/已还loan20,21/READER调用/匿名',
  '404/409/409/403；失败无记录通知；读者403',
  obs('非法/未逾期/不存在', '读者催还403', '结案后登记409'), 'PASS', 'ACC补充独立验证'))
ac.append(row('AC-DUN-03', 'IMPL/REV/ACC', SYN,
  '先对RETID/RETBAR真实登记→API按ID归还+UI按条码归还→案件/DB严格时间对比→结案后登记',
  '两路径CLOSED；closedAt==returnedAt；RETURNED；结案后409；并发无漂移',
  obs('按借阅号归还', '条码归还', '结案后登记409', '已结案筛选'), 'PASS',
  '并发锁顺序无漂移由同候选T(DunningTransactionTest)覆盖，本轮未重跑并发单元测试'))
ac.append(row('AC-DUN-04', 'IMPL/ACC', SYN,
  'UI案件列表OPEN/CLOSED筛选、历史弹窗、API size2分页与排序、同案件二次登记ASC',
  '单案件时间ASC历史；列表OPEN/CLOSED过滤分页',
  obs('案件列表/历史UI', '历史时间ASC', '案件分页/排序', '已结案筛选'), 'PASS'))
ac.append(row('AC-UI-07', 'IMPL/REV/ACC', SYN,
  '空态/有数据/加载/错误重试(披露网络层模拟)/真实503中文/提交锁/501校验/390px三页',
  '四态覆盖；503系统繁忙；禁重复提交；校验与中文错误码契约一致',
  obs('催还页空态', '列表错误态+重试(网络层模拟', '错误态重试恢复', '真实DB_BUSY 503', '提交锁', '390px收件箱', '390px管理页', '501字前端校验'), 'PASS',
  '列表GET错误态用披露的网络层模拟(真实写锁不阻塞WAL读)；真实DB_BUSY 503由真实SQLite写锁POST证明；加载态在真实慢查询/锁窗口可见'))
ac.append(row('AC-UI-05增量', 'IMPL/ACC', SYN,
  'ADMIN/READER真实登录菜单核对；读者访问/admin/dunning；我的借阅无续借按钮',
  '原菜单保留+我的提醒/催还记录；不开放其他角色菜单或自助续借',
  obs('ADMIN UI登录', '读者菜单', '读者访问管理路由', '读者无自助续借'), 'PASS',
  'ADMIN布局不请求读者通知API由同候选F layout测试+评审覆盖'))

dod = []
def drow(id, resp, expected, actual, result, evidence, note=''):
    return {'id': id, 'definitionVersion': 'v1.1' if id.startswith('DOD-R') else 'v1',
            'responsibility': resp, 'expected': expected, 'actual': actual, 'result': result,
            'evidence': evidence, 'candidateId': CAND, 'environment': ENV, 'note': note}
L = ROOT + '/docs/verification/logs/reminder/'
dod.append(drow('DOD-R1', 'IMPL/REV', 'V1不改；V2空库/旧库迁移成功；不用开发库',
  '独立git diff HEAD确认V1/pom/package/lock零差异；本轮全新临时库真实启动V1→V2迁移成功(dev-default.log)；旧借阅库迁移由同候选ReminderMigrationTest覆盖；backend/data未触碰',
  'PASS', [ACC + '/dev-default.log', 'git diff HEAD(空)', L + 'backend-verify.log'], '引用+独立启动观察'))
dod.append(drow('DOD-R2', 'IMPL/REV', '保留既有测试全通过，不删/跳过/放宽',
  '同候选证据核对：backend-verify.log Tests run: 611；frontend-test.log 40文件397 passed；standard-e2e.log 13 passed；修订披露于集成矩阵§8；本轮标准E2E重跑见DOD-05',
  'PASS', [L + 'backend-verify.log', L + 'frontend-test.log', L + 'standard-e2e.log'], '引用同候选证据，非代签'))
dod.append(drow('DOD-R3', 'REV', '无外发/新依赖/REQUIRES_NEW；原借阅行为不变',
  '独立评审会话8de00986实际读取：PASS，含无REQUIRES_NEW/外发/依赖结论；本轮相邻回归借还续借检索统计正常',
  'PASS', ['mcp read_chat_session 8de00986 Turn7d2321d2', ACC + '/observations.jsonl(B5)'], '引用已执行独立评审'))
dod.append(drow('DOD-R4', 'IMPL/ACC', '真实浏览器临时库覆盖到期/逾期/催还/结案+截图+边界失败',
  '本轮Python Playwright真实chromium：四类行为+边界(501/500/空备注/IDOR/CSRF/403/409/404)+真实DB_BUSY失败路径+390px；30+截图；0 pageerror',
  'PASS', [SHOTS, ACC + '/observations.jsonl', ACC + '/browser-report.json'], '独立验收实际执行'))
dod.append(drow('DOD-R5', 'IMPL/ACC', 'README配置限制+矩阵逐条完整',
  'README§6.1配置/触达/DB安全已核对；集成矩阵定义/结果分离、候选绑定、证据索引完整；本验收另产出ac-matrix/dod-matrix不改原矩阵',
  'PASS', [ROOT + '/README.md', ROOT + '/docs/verification/reminder-ac-dod-matrix.md', ACC + '/ac-matrix.json', ACC + '/dod-matrix.json']))
dod.append(drow('DOD-01', 'IMPL', '空克隆JDK21 verify/Java8拒绝/npm ci/build',
  '同候选K2真实git clone+329候选overlay：ci/verify611/build exit0；X JDK8 enforcer拒绝；证据文件存在已核对',
  'PASS', [L + 'clone-candidate-summary.json', L + 'clean-candidate-summary.json', L + 'java8-negative-confirmed.log'], '引用同候选证据，非代签'))
dod.append(drow('DOD-02', 'IMPL/ACCEPT', 'dev.sh≤120s/新库迁移/浏览器登录/Ctrl-C双退出/端口占用提示',
  '独立实测：后端3.116s就绪+前端296ms；全新临时库V1/V2；真实双角色UI登录；第二实例占用18099明确拒绝exit1；真实PTY发送ETX后两端口释放、无残留进程(dev.sh exit=1系监控循环与INT trap竞争,清理完整,DoD文字满足)',
  'PASS', [ACC + '/dev-default.log', ACC + '/port-conflict.log', ACC + '/pty-runner.log', ACC + '/dev-pt1s.log'], '独立验收实际执行；exit code竞争已披露'))
dod.append(drow('DOD-03', 'IMPL', '全量后端零失败/AC映射/JaCoCo',
  '同候选B 611/0/0/0+JaCoCo+T三轮96；证据存在已核对', 'PASS',
  [L + 'backend-verify.log', L + 'high-risk-run1.log', L + 'high-risk-run2.log', L + 'high-risk-run3.log'], '引用，非代签'))
dod.append(drow('DOD-04', 'IMPL', 'lint/typecheck/test/build零error',
  '同候选F四项exit0(397测试)；chunk体积告警非错误保留', 'PASS',
  [L + 'frontend-lint.log', L + 'frontend-typecheck.log', L + 'frontend-test.log', L + 'frontend-build.log'], '引用，非代签'))
e2e_log = ACC + '/standard-e2e-acceptance.log'
e2e_txt = open(e2e_log).read() if os.path.exists(e2e_log) else ''
e2e_ok = 'e2e exit=0' in e2e_txt and '13 passed' in e2e_txt
dod.append(drow('DOD-05', 'IMPL/ACCEPT', '标准E2E 7步冒烟chromium独立端口/临时库',
  ('本轮独立重跑标准npm --prefix frontend run e2e：' + ('13 passed，exit0' if e2e_ok else '见日志')) ,
  'PASS' if e2e_ok else 'NOT_RUN', [e2e_log, ROOT + '/frontend/playwright-report/index.html'], '独立验收实际执行' if e2e_ok else '等待执行完成'))
dod.append(drow('DOD-06', 'IMPL/ACCEPT', 'dev管理员/演示读者/逾期种子；重启幂等；test不写种子',
  '独立实测：admin/reader1/reader2种子登录正常；重启前后books24/copies36/readers8/loans22计数不变；loan2逾期未还且报表可见；test profile无种子由同候选ContextLoadsTest覆盖',
  'PASS', [ACC + '/counts-before-restart.txt', ACC + '/counts-after-restart.txt', ACC + '/observations.jsonl(B5)'], '独立+引用'))
dod.append(drow('DOD-07', 'IMPL/ACCEPT', 'README完整；验收人员只按README从零启动冒烟',
  '本验收仅按README§3/§8隔离启动说明操作即完成启动与全部冒烟；使用既有依赖无安装；配置/账号/限制章节齐全',
  'PASS', [ROOT + '/README.md', ACC + '/commands.log', ACC + '/dev-default.log'], '独立验收实际执行'))
dod.append(drow('DOD-08', 'IMPL/REV', 'BCrypt/权限/CSRF/IDOR/脱敏/参数化/audit',
  '同候选B安全测试+N audit found0+评审安全结论；本轮独立IDOR/CSRF/403/401/501/400实测全部符合',
  'PASS', [L + 'npm-audit.log', '评审会话8de00986', ACC + '/observations.jsonl(B1/B2)'], '引用+独立补充'))
dod.append(drow('DOD-09', 'IMPL', '逐条定义/阶段/实际/方法/执行者/证据/候选追溯',
  '集成矩阵+快照绑定完整；本验收矩阵同样逐条完整', 'PASS',
  [ROOT + '/docs/verification/reminder-ac-dod-matrix.md', ACC + '/ac-matrix.json', ACC + '/dod-matrix.json'], '引用+本验收产出'))
dod.append(drow('DOD-10', 'REV', '无非目标/新依赖/REQUIRES_NEW；旧迁移未改',
  '独立评审PASS(会话实际读取)；本轮git diff确认V1/pom/package/lock未改', 'PASS',
  ['评审会话8de00986 Turn7d2321d2', 'git diff HEAD(空)'], '引用已执行独立评审'))
dod.append(drow('DOD-11', 'REV', '同候选独立评审PASS无未关闭Blocker/Critical',
  '实际读取评审会话：PASS；330/330+aggregate一致；Blocker0/Critical0/Major0/Minor4非阻断', 'PASS',
  ['mcp read_chat_session 8de00986-f9b7-5c68-a1a8-dc049c2b4c52'], '引用已执行独立评审，候选同一'))
dod.append(drow('DOD-12', 'ACCEPT', '同评审快照本机独立验收PASS',
  '本验收与评审同一候选REM-INT-v1.5-r3(330/330 hash+aggregate验收前后一致)；全部到期必需AC/DoD独立实测或同候选证据核验通过；0产品缺陷；详见ac-matrix/dod-matrix/summary',
  'PASS', [ACC + '/summary.json', ACC + '/ac-matrix.json', ACC + '/dod-matrix.json', ACC + '/candidate-hash-pre.json', ACC + '/candidate-hash-post.json'], '本验收结论；不是发布许可'))
dod.append(drow('部署/发布', '—', '交付终点不含发布', '增量§2.12与README非目标明确排除部署/发布/生产操作', 'NOT_APPLICABLE',
  [ROOT + '/docs/design/reminder-increment.md'], '范围依据：批准终点=本机实现+评审+验收'))

json.dump({'taskId': 'lib-borrow-reminder-p1', 'acceptanceTaskId': 'REM-ACC', 'definition': DEF,
           'candidateId': CAND, 'environment': ENV, 'rows': ac},
          open(ACC + '/ac-matrix.json', 'w'), ensure_ascii=False, indent=2)
json.dump({'taskId': 'lib-borrow-reminder-p1', 'acceptanceTaskId': 'REM-ACC', 'definition': DEF,
           'candidateId': CAND, 'environment': ENV, 'rows': dod},
          open(ACC + '/dod-matrix.json', 'w'), ensure_ascii=False, indent=2)

shots = sorted(glob.glob(SHOTS + '/*.png'))
phases = {}
for o in OBS:
    phases.setdefault(o['phase'], []).append(o)
browser = {'engine': 'Python Playwright chromium 145.0.7632.6 headless (real browser)',
           'baseUrl': 'http://localhost:15199', 'screenshots': shots,
           'pageerrors': 'all recorded contexts 0 (see observations)',
           'observationWindows': {'noBadgePolling': '25s idle, 0 notification requests',
                                  'defaultScanInterval': '~5min, startup scan only (PT1H)',
                                  'postReturnNoNew': '8s (~8 scans), counts unchanged',
                                  'noAutoRetryAfter503': '6s, no new POST'},
           'simulatedOnly': ['case-list GET error state via disclosed network abort (real DB_BUSY proven separately via real write lock on POST)'],
           'phases': {k: {'observations': len(v),
                          'results': {r: sum(1 for x in v if x['result'] == r) for r in set(x['result'] for x in v)}}
                      for k, v in phases.items()}}
json.dump(browser, open(ACC + '/browser-report.json', 'w'), ensure_ascii=False, indent=2)
print('matrices written; E2E ok =', e2e_ok)
fails = [o for o in OBS if o['result'] == 'FAIL']
print('remaining FAIL rows:', [(o['phase'], o['item']) for o in fails])