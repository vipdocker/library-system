"""REM-INT round2: explicitly authorized clean candidate reproduction, not git clone.
Copies only approved inputs into a system temporary directory. No dev DB, git,
node_modules or prior build artifacts copied; no registry/TLS/config changes.
Run from repository root: python3 docs/verification/logs/reminder/clean-candidate-verify.py
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import time

workspace = pathlib.Path.cwd()
evidence = workspace / 'docs/verification/logs/reminder'
assert evidence.is_dir()
inputs = set()
for directory in ['backend/src', 'frontend/src', 'frontend/e2e']:
    for file in (workspace / directory).rglob('*'):
        if file.is_file():
            assert not file.is_symlink(), f'Unexpected symlink: {file}'
            inputs.add(file.relative_to(workspace).as_posix())
for name in ['README.md', '.gitignore', 'backend/pom.xml', 'frontend/package.json',
             'frontend/package-lock.json', 'frontend/index.html', 'frontend/vite.config.ts',
             'frontend/tsconfig.json', 'frontend/eslint.config.js', 'frontend/vitest.setup.ts',
             'frontend/playwright.config.ts', 'docs/design/reminder-increment.md',
             'docs/design/approval-record.md', 'docs/design/ac-dod.md', 'docs/design/solution-design.md']:
    assert (workspace / name).is_file(), name
    inputs.add(name)
for file in (workspace / 'scripts').glob('*.sh'):
    inputs.add(file.relative_to(workspace).as_posix())

candidate = pathlib.Path(tempfile.mkdtemp(prefix='library-reminder-clean-candidate-'))
manifest = []
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
for name in sorted(inputs):
    source = workspace / name
    destination = candidate / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    digest = sha(source)
    assert sha(destination) == digest
    manifest.append({'path': name, 'sha256': digest})
assert not (candidate / '.git').exists()
assert not (candidate / 'frontend/node_modules').exists()
assert not (candidate / 'backend/target').exists()
assert not (candidate / 'backend/data').exists()
assert not (candidate / 'frontend/dist').exists()
assert not (candidate / '.vite').exists()
# This is a generated verification manifest in the disposable test artifact directory.
manifest_file = candidate / 'verification-inputs.json'
manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
aggregate = hashlib.sha256(''.join(x['sha256'] + '  ' + x['path'] + '\n' for x in manifest).encode()).hexdigest()
print(f'CLEAN_CANDIDATE_ROOT {candidate}', flush=True)
print(f'INITIAL_INPUTS {len(manifest)} SHA256 {aggregate}; node_modules/target/dist/git/data ABSENT', flush=True)
commands = [
    ('npm-ci', ['/opt/homebrew/bin/npm', '--prefix', 'frontend', 'ci']),
    ('backend-verify', ['/bin/bash', '-c', 'source scripts/env.sh && mvn -f backend/pom.xml verify']),
    ('frontend-build', ['/opt/homebrew/bin/npm', '--prefix', 'frontend', 'run', 'build']),
]
results = []
java_home = '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'
child_env = dict(os.environ, JAVA_HOME=java_home, PATH=java_home + '/bin:/opt/homebrew/bin:' + os.environ['PATH'])
for name, command in commands:
    logfile = evidence / f'clean-candidate-{name}.log'
    started = time.monotonic()
    # Actual command output is a normal test log, not an edited project source file.
    with logfile.open('w') as output:
        output.write('CWD ' + str(candidate) + '\nCOMMAND ' + json.dumps(command) + '\n')
        output.flush()
        result = subprocess.run(command, cwd=candidate, env=child_env,
                                stdout=output, stderr=subprocess.STDOUT, timeout=600)
        output.write(f'\nACTUAL_EXIT_CODE {result.returncode}\n')
    results.append({'phase': name, 'command': command, 'exitCode': result.returncode,
                    'seconds': round(time.monotonic()-started, 3), 'log': str(logfile), 'sha256': sha(logfile)})
    print('PHASE_RESULT ' + json.dumps(results[-1]), flush=True)
    if result.returncode:
        break
for item in manifest:
    assert sha(workspace / item['path']) == item['sha256'], 'Workspace source drifted: ' + item['path']
    assert sha(candidate / item['path']) == item['sha256'], 'Copied input changed: ' + item['path']
summary = {
    'taskId': 'lib-borrow-reminder-p1', 'subtaskId': 'REM-INT', 'operation': 'integrate',
    'planVersion': 'reminder-plan-v1.5', 'dispatchRound': 2,
    'candidateRoot': str(candidate), 'isGitClone': False,
    'method': 'Explicitly authorized isolated copy of current uncommitted candidate; empty dependencies/build outputs',
    'initialAbsent': ['.git', 'frontend/node_modules', 'backend/target', 'backend/data', 'frontend/dist', '.vite'],
    'excluded': ['.git', 'node_modules', 'backend/target', 'backend/data', 'frontend/dist', 'frontend/test-results',
                 'frontend/playwright-report', '.vite', 'docs/hongkong-*', 'unrelated scripts', 'stray acceptance jar', 'other user untracked files'],
    'inputCount': len(manifest), 'inputAggregateSha256': aggregate,
    'inputManifest': str(manifest_file), 'inputManifestSha256': sha(manifest_file),
    'allInputsMatchedWorkspaceBeforeAndAfter': True, 'dependencyConfigurationChanged': False,
    'commands': results, 'retainedTemporaryCandidate': True,
    'result': 'PASS' if len(results) == 3 and all(x['exitCode'] == 0 for x in results) else 'FAIL',
}
print('CLEAN_SUMMARY ' + json.dumps(summary, ensure_ascii=False), flush=True)
assert summary['result'] == 'PASS', 'Clean candidate reproduction failed; logs retained; no source/TLS changes'
