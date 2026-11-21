"""Run dev.sh under a real PTY so acceptance can send genuine Ctrl-C (ETX).
Watches for stop.signal file; logs child output; reports exit code."""
import os, pty, select, signal, sys, time, subprocess, errno

ACC = os.path.dirname(os.path.abspath(__file__))
ROOT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10'
env = dict(os.environ)
env.update({'LIB_DB_PATH': open(ACC + '/tmpdir.txt').read().strip() + '/library.db',
            'SERVER_PORT': '18099', 'FRONTEND_PORT': '15199',
            'LIB_REMINDER_SCANINTERVAL': 'PT1S'})  # acceptance-only override, disclosed
log = open(ACC + '/dev-pt1s.log', 'wb')
pid, master = pty.fork()
if pid == 0:
    os.chdir(ROOT)
    os.execvpe('bash', ['bash', 'scripts/dev.sh'], env)
stop = ACC + '/stop.signal'
if os.path.exists(stop): os.remove(stop)
sent = False
eof = False
start = time.time()
while True:
    try:
        if not eof:
            r, _, _ = select.select([master], [], [], 0.5)
            if r:
                try: data = os.read(master, 65536)
                except OSError: data = b''
                if not data: eof = True
                else: log.write(data); log.flush()
        else:
            time.sleep(0.3)
        if not sent and os.path.exists(stop):
            os.write(master, b'\x03')  # real Ctrl-C
            sent = True
            print(f'ETX sent at {time.time()-start:.1f}s', flush=True)
        done, status = os.waitpid(pid, os.WNOHANG)
        if done:
            code = os.waitstatus_to_exitcode(status)
            print(f'dev.sh exit={code} after {time.time()-start:.1f}s (ctrlc_sent={sent})', flush=True)
            with open(ACC + '/pty-exit.txt', 'w') as f: f.write(str(code))
            break
        if eof and time.time() - start > 3600:
            print('timeout waiting exit', flush=True); break
    except KeyboardInterrupt:
        break
log.close()