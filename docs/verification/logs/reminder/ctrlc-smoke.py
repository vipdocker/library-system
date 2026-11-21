"""REM-INT: actual terminal Ctrl-C, only own disposable dev.sh instance.
Run from repo root: python3 docs/verification/logs/reminder/ctrlc-smoke.py
No application edits, environment installation, or existing database access.
"""
import errno
import os
import pathlib
import pty
import select
import signal
import subprocess
import tempfile
import time
import urllib.request

ports = [18098, 15198]

def assert_ports_free():
    for port in ports:
        result = subprocess.run(['lsof', '-nP', f'-iTCP:{port}', '-sTCP:LISTEN'], capture_output=True, text=True)
        assert result.returncode == 1 and not result.stdout.strip(), f'Port {port} occupied: {result.stdout}'

assert_ports_free()
database = pathlib.Path(tempfile.mkdtemp(prefix='library-reminder-ctrlc-')) / 'library.db'
pid, master = pty.fork()
if pid == 0:
    os.environ.update(LIB_DB_PATH=str(database), SERVER_PORT='18098', FRONTEND_PORT='15198')
    os.environ.pop('LIB_REMINDER_SCANINTERVAL', None)
    os.execvp('bash', ['bash', 'scripts/dev.sh'])

start = time.monotonic()
status = None
log = ''

def drain():
    global log
    if select.select([master], [], [], 0.1)[0]:
        try:
            value = os.read(master, 65536)
            text = value.decode(errors='replace')
            log += text
            print(text, end='', flush=True)
        except OSError as error:
            if error.errno != errno.EIO:
                raise

try:
    ready = False
    while time.monotonic() - start < 120:
        drain()
        done, current = os.waitpid(pid, os.WNOHANG)
        assert done == 0, f'Own dev.sh exited early: {current}'
        try:
            with urllib.request.urlopen('http://localhost:15198', timeout=0.2) as response:
                ready = response.status == 200
        except (OSError, urllib.error.URLError):
            pass
        if ready:
            break
    assert ready, 'README startup exceeded 120 seconds'
    print(f'CTRL_C_READY seconds={time.monotonic()-start:.3f} database={database}', flush=True)
    # ETX through this owned terminal, rather than treating TERM as keyboard Ctrl-C.
    os.write(master, b'\x03')
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        drain()
        done, current = os.waitpid(pid, os.WNOHANG)
        if done:
            status = current
            break
    assert status is not None, 'Ctrl-C did not terminate own dev.sh'
    assert os.waitstatus_to_exitcode(status) == 130, f'Unexpected Ctrl-C exit: {status}'
    assert_ports_free()
    print(f'CTRL_C_PASS devExit=130 backend/front ports free; temporary DB retained: {database}')
finally:
    if status is None:
        # Only our PTY child owns this process group. Do not stop unrelated services.
        try:
            os.kill(pid, signal.SIGTERM)
            os.waitpid(pid, 0)
        except ProcessLookupError:
            pass
    os.close(master)
