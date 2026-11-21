#!/usr/bin/env python3
"""Read-only candidate hash verifier for REM-ACC. Does not modify candidate."""
import json,hashlib,sys,datetime
ROOT='/Users/duff/Documents/Lingma-Demo/qoder-pl-test10'
s=json.load(open(ROOT+'/docs/verification/reminder-candidate-snapshot.txt'))
sf=sorted(s['sourceFiles'],key=lambda x:x['path'])
mism=[];agg=hashlib.sha256();ok=0
for e in sf:
    p=ROOT+'/'+e['path']
    try:
        h=hashlib.sha256(open(p,'rb').read()).hexdigest()
    except FileNotFoundError:
        mism.append({'path':e['path'],'expected':e['sha256'],'actual':'MISSING'});continue
    if h!=e['sha256']: mism.append({'path':e['path'],'expected':e['sha256'],'actual':h})
    else: ok+=1
    agg.update((e['sha256']+'  '+e['path']+'\n').encode())
a=agg.hexdigest()
res={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':sys.argv[1] if len(sys.argv)>1 else 'unknown',
     'candidateId':s['candidateId'],'total':len(sf),'matched':ok,'mismatches':mism,
     'aggregateSha256':a,'expectedAggregate':s['candidateAggregateSha256'],
     'aggregateMatch':a==s['candidateAggregateSha256'],
     'snapshotFileSha256':hashlib.sha256(open(ROOT+'/docs/verification/reminder-candidate-snapshot.txt','rb').read()).hexdigest()}
out=ROOT+'/docs/verification/acceptance/reminder/candidate-hash-'+res['phase']+'.json'
json.dump(res,open(out,'w'),indent=2,ensure_ascii=False)
print(json.dumps({k:res[k] for k in ('phase','total','matched','aggregateMatch','snapshotFileSha256')},ensure_ascii=False))
if mism: print('MISMATCHES:',json.dumps(mism,indent=1)); sys.exit(1)
