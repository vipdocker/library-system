"""Hold an EXCLUSIVE write lock on the OWN acceptance temp DB (BEGIN IMMEDIATE,
rollback at end — no data change). Signals readiness via lock-ready file;
releases when lock-release file appears or after max_seconds."""
import sqlite3, os, sys, time
ACC = os.path.dirname(os.path.abspath(__file__))
DB = open(ACC + '/tmpdir.txt').read().strip() + '/library.db'
maxs = float(sys.argv[1]) if len(sys.argv) > 1 else 30
for f in ('lock-ready', 'lock-release'):
    p = f'{ACC}/{f}'
    if os.path.exists(p): os.remove(p)
con = sqlite3.connect(DB, timeout=15)
con.execute('BEGIN IMMEDIATE')
con.execute("CREATE TABLE IF NOT EXISTS _acc_lock_probe (x INTEGER)")  # holds write lock; rolled back
open(ACC + '/lock-ready', 'w').write(str(time.time()))
start = time.time()
while time.time() - start < maxs:
    if os.path.exists(ACC + '/lock-release'): break
    time.sleep(0.2)
con.rollback()
con.close()
print(f'lock released after {time.time()-start:.1f}s (rolled back, no data change)')