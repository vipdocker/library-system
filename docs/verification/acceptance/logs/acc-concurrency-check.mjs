// acc-concurrency-check.mjs — AC-LOAN-03 黑盒并发：10 并发借同一副本 / 并发借 8 / 并发还 2 次
const BASE = 'http://localhost:18095';
const S = 'conc' + Date.now();

function jarFrom(list) {
  const jar = {};
  for (const raw of list) {
    const [kv] = raw.split(';');
    const eq = kv.indexOf('=');
    jar[kv.slice(0, eq)] = kv.slice(eq + 1);
  }
  return jar;
}
const jarToStr = jar => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');

async function adminLogin() {
  const r1 = await fetch(`${BASE}/api/auth/csrf`);
  const jar = jarFrom(r1.headers.getSetCookie());
  const r2 = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': jar['XSRF-TOKEN'], cookie: jarToStr(jar) },
    body: JSON.stringify({ username: 'admin', password: 'Admin@12345' }),
  });
  Object.assign(jar, jarFrom(r2.headers.getSetCookie()));
  return { jar };
}

async function post(jar, path, body) {
  const r0 = await fetch(`${BASE}/api/auth/csrf`, { headers: { cookie: jarToStr(jar) } });
  Object.assign(jar, jarFrom(r0.headers.getSetCookie()));
  const r = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': jar['XSRF-TOKEN'], cookie: jarToStr(jar) },
    body: JSON.stringify(body),
  });
  Object.assign(jar, jarFrom(r.headers.getSetCookie()));
  return r;
}
const get = (jar, p) => fetch(`${BASE}${p}`, { headers: { cookie: jarToStr(jar) } });

const admin = await adminLogin();
const J = admin.jar;

// 准备
const cat = await (await post(J, '/api/categories', { name: `并发分类${S}` })).json();
const bk = await (await post(J, '/api/books', {
  isbn: '9780262033848', title: `并发测试书${S}`, author: 'acc', initialCopies: 0, categoryId: cat.id,
})).json();
const cp = await (await post(J, `/api/books/${bk.id}/copies`, { count: 1 })).json();
const bc = cp[0].barcode;
const rd = await (await post(J, '/api/readers', {
  name: `并发读者${S}`, username: `acc${S}`, initialPassword: 'Acc@12345',
})).json();
console.log(`prepared: book=${bk.id} barcode=${bc} reader=${rd.cardNo}`);

// ---- 场景 1：10 并发借同一副本 ----
const s1res = await Promise.all(Array.from({ length: 10 }, () =>
  post({ ...J }, '/api/loans', { readerCardNo: rd.cardNo, copyBarcode: bc })
    .then(async r => ({ status: r.status, code: (await r.json()).code || 'OK' }))
    .catch(e => ({ status: 0, code: String(e) }))));
const ok1 = s1res.filter(r => r.status === 201).length;
const na1 = s1res.filter(r => r.code === 'COPY_NOT_AVAILABLE').length;
const other1 = s1res.filter(r => r.status !== 201 && r.code !== 'COPY_NOT_AVAILABLE');
console.log(`S1: 201=${ok1} COPY_NOT_AVAILABLE=${na1} other=${JSON.stringify(other1)}`);
console.log(ok1 === 1 && na1 === 9 && other1.length === 0 ? 'PASS | 10 并发借同一副本恰好 1 成功' : 'FAIL | S1');

const loans = await (await get(J, `/api/loans?readerId=${rd.id}`)).json();
const act = loans.items.filter(i => i.copy.barcode === bc && !i.returnDate);
console.log(`active-on-copy=${act.length} ${act.length === 1 ? 'PASS' : 'FAIL'}`);

// ---- 场景 2：同一读者并发借 8 个不同副本 ----
const bk2 = await (await post(J, '/api/books', {
  isbn: '9780134685991', title: `并发书组${S}`, author: 'acc', initialCopies: 8,
})).json();
const list2 = await (await get(J, `/api/books/${bk2.id}/copies`)).json();
console.log(`book2 copies=${list2.length}`);
const s2res = await Promise.all(list2.map(c =>
  post({ ...J }, '/api/loans', { readerCardNo: rd.cardNo, copyBarcode: c.barcode })
    .then(async r => ({ status: r.status, code: (await r.json()).code || 'OK' }))
    .catch(e => ({ status: 0, code: String(e) }))));
const ok2 = s2res.filter(r => r.status === 201).length;
const lim2 = s2res.filter(r => r.code === 'LOAN_LIMIT_EXCEEDED').length;
const other2 = s2res.filter(r => r.status !== 201 && r.code !== 'LOAN_LIMIT_EXCEEDED');
console.log(`S2: 201=${ok2} LIMIT=${lim2} other=${JSON.stringify(other2)}`);
console.log(ok2 <= 5 && (ok2 + lim2 + other2.length === 8) ? 'PASS | 并发借 8 副本成功数≤5' : 'FAIL | S2');
const rdAfter = await (await get(J, `/api/readers/${rd.id}`)).json();
console.log(`final activeLoanCount=${rdAfter.activeLoanCount} ${rdAfter.activeLoanCount <= 5 ? 'PASS' : 'FAIL'}`);

// ---- 场景 3：并发还同一借阅两次 ----
const loanId = act[0].id;
const r3a = post({ ...J }, `/api/loans/${loanId}/return`, {});
const r3b = post({ ...J }, `/api/loans/${loanId}/return`, {});
const [a, b] = await Promise.all([r3a, r3b]);
const [ja, jb] = await Promise.all([a.json(), b.json()]);
const codes = [a.status, b.status];
console.log(`S3: statuses=${codes} codes=${ja.code || 'OK'}|${jb.code || 'OK'}`);
const ok3 = codes.filter(s => s === 200).length;
const dup3 = [ja.code, jb.code].filter(c => c === 'LOAN_ALREADY_RETURNED').length;
console.log(ok3 === 1 && dup3 === 1 ? 'PASS | 并发还两次恰好 1 成功' : 'FAIL | S3');
