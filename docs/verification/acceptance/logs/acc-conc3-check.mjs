// acc-conc3-check.mjs — 场景 2 终版：新读者并发借 8 本不同书（全新 ISBN）
const BASE = 'http://localhost:18095';
const S = 'c3' + Date.now();
function jarFrom(list){const jar={};for(const raw of list){const[kv]=raw.split(';');const eq=kv.indexOf('=');jar[kv.slice(0,eq)]=kv.slice(eq+1);}return jar;}
const jarToStr=jar=>Object.entries(jar).map(([k,v])=>k+'='+v).join('; ');
async function mkJar(){const r1=await fetch(BASE+'/api/auth/csrf');const jar=jarFrom(r1.headers.getSetCookie());
 const r2=await fetch(BASE+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':jar['XSRF-TOKEN'],cookie:jarToStr(jar)},body:JSON.stringify({username:'admin',password:'Admin@12345'})});
 Object.assign(jar,jarFrom(r2.headers.getSetCookie()));return jar;}
async function post(jar,path,body){const r0=await fetch(BASE+'/api/auth/csrf',{headers:{cookie:jarToStr(jar)}});Object.assign(jar,jarFrom(r0.headers.getSetCookie()));
 const r=await fetch(BASE+path,{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':jar['XSRF-TOKEN'],cookie:jarToStr(jar)},body:JSON.stringify(body)});
 Object.assign(jar,jarFrom(r.headers.getSetCookie()));return r;}
const get=(jar,p)=>fetch(BASE+p,{headers:{cookie:jarToStr(jar)}});

// 生成 8 个全新合法 ISBN（前缀 978-6 区段，避免与已用冲突）
const py = (await import('node:child_process')).execSync;
function validIsbn(base12){let t=0;for(let j=0;j<12;j++)t+=(j%2===0?1:3)*parseInt(base12[j]);return base12+String((10-t%10)%10);}
const isbns=[];for(let i=0;i<8;i++){isbns.push(validIsbn('97861' + String(1000000 + i*7).slice(0,7).padStart(7,'0')));}

const J=await mkJar();
const rd=await (await post(J,'/api/readers',{name:`并发读者3-${S}`,username:`acc3${S}`,initialPassword:'Acc@12345'})).json();
const barcodes=[];
for(const isbn of isbns){
  const r=await post(J,'/api/books',{isbn,title:`并发书3-${S}-${isbn}`,author:'acc',initialCopies:1});
  const bk=await r.json();
  if(!r.ok){console.log(`create fail ${isbn}: ${r.status} ${bk.code}`);process.exit(1);}
  barcodes.push((await (await get(J,`/api/books/${bk.id}/copies`)).json())[0].barcode);
}
console.log(`reader=${rd.cardNo} books=${barcodes.length}`);
const res=await Promise.all(barcodes.map(b=>post({...J},'/api/loans',{readerCardNo:rd.cardNo,copyBarcode:b}).then(async r=>({status:r.status,code:(await r.json()).code||'OK'})).catch(e=>({status:0,code:String(e)}))));
const ok=res.filter(r=>r.status===201).length;
const lim=res.filter(r=>r.code==='LOAN_LIMIT_EXCEEDED').length;
const other=res.filter(r=>r.status!==201&&r.code!=='LOAN_LIMIT_EXCEEDED');
console.log(`201=${ok} LIMIT=${lim} other=${JSON.stringify(other)}`);
console.log(ok<=5&&lim+ok+other.length===8?'PASS | 同一读者并发借 8 本不同书成功数≤5':'FAIL');
const rda=await (await get(J,`/api/readers/${rd.id}`)).json();
console.log(`activeLoanCount=${rda.activeLoanCount} ${rda.activeLoanCount<=5?'PASS':'FAIL'}`);
