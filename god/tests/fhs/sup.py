"""Benchmark the fhs-run supervisor against a previous version.

--baseline DIR: directory with the previous fhs-run.c and fhsmap.h; default: git aac2c260.
The workload makes raw system calls under each supervisor; nothing is installed.
"""
from pathlib import Path
import argparse
import os
import statistics
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path)
parser.add_argument('-n', type=int, default=20000)
args = parser.parse_args()


def source(name):
    if args.baseline:
        return (args.baseline / name).read_text()
    return subprocess.check_output(['git', 'show', 'aac2c260:god/fhs/' + name], cwd=repo, text=True)


env = {k: v for k, v in os.environ.items() if k not in ('LD_PRELOAD', 'FHS_TRACE', 'FHS_DEBIAN', 'FHS_SUP')}
results = {'before': {}, 'after': {}}
with tempfile.TemporaryDirectory(prefix='fhs-sup-') as tmp:
    root = Path(tmp)
    subprocess.run(['clang', '-O2', str(repo / 'god/tests/fhs/sup.c'), '-o', str(root / 'load')], check=True)
    for name in ['before', 'after']:
        d = root / name
        d.mkdir()
        for f in ['fhs-run.c', 'fhsmap.h']:
            (d / f).write_text(source(f) if name == 'before' else (repo / 'god/fhs' / f).read_text())
        (d / 'fhs-fast.h').write_text((repo / 'god/fhs/fhs-fast.h').read_text())
        subprocess.run(['clang', '-O2', '-w', str(d / 'fhs-run.c'), '-o', str(d / 'fhs-run')], check=True)
    (root / 'fallback').mkdir()
    subprocess.run(['clang', '-O2', '-w', '-DFHS_VM_DEFAULT=0', str(root / 'after/fhs-run.c'), '-o', str(root / 'fallback/fhs-run')], check=True)
    # Without a supervisor /usr/bin/sh does not exist, so the exit status is non-zero: timing reference only.
    # Behaviour: the same operations under both supervisors, in a scratch directory reached only through the FHS name /tmp/...
    subprocess.run(['clang', '-O2', '-w', str(repo / 'god/tests/fhs/supops.c'), '-o', str(root / 'ops')], check=True)
    outputs = {}
    for name in ['before', 'after', 'fallback']:
        with tempfile.TemporaryDirectory(prefix='fhs-supops-', dir=os.environ['PREFIX'] + '/tmp') as scratch:
            run = subprocess.run([str(root / name / 'fhs-run'), str(root / 'ops'), '/tmp/' + Path(scratch).name],
                                 env=env, text=True, stdout=subprocess.PIPE)
            assert run.returncode == 0, (name, run.returncode)
            outputs[name] = run.stdout
            assert not list(Path(scratch).iterdir()), (name, 'scratch directory not empty')
    expected = (repo / 'god/tests/fhs/supops.expected').read_text()
    assert outputs['after'] == expected, 'new supervisor:\n' + outputs['after']
    assert outputs['fallback'] == expected, 'new supervisor, /proc/PID/mem fallback:\n' + outputs['fallback']
    # "new:" lines describe behaviour added after the baseline (account lookups are not redirected); compare the rest
    old = lambda text: ''.join(line for line in text.splitlines(True) if not line.startswith('new: '))
    assert old(outputs['before']) == old(expected), 'baseline supervisor differs:\n' + outputs['before']
    print(f"PASS: {len(expected.splitlines())} operation results identical under the previous supervisor, the new one and its /proc/PID/mem fallback, and equal to supops.expected")
    direct = subprocess.run([str(root / 'load'), str(args.n)], env=env, text=True, stdout=subprocess.PIPE).stdout
    for name in ['before', 'after', 'after', 'before'] * 2:
        out = subprocess.check_output([str(root / name / 'fhs-run'), str(root / 'load'), str(args.n)], env=env, text=True)
        for line in out.splitlines():
            key, value = line.split()
            results[name].setdefault(key, []).append(float(value))
    for name in results:
        assert results[name]['ok'] == [15.0 * args.n] * 4, (name, results[name]['ok'])
    # A real command: a shell listing /usr/bin five times (about 14 000 intercepted calls, mostly distinct translated names).
    import time
    real = {'before': [], 'after': []}
    command = [os.environ['PREFIX'] + '/bin/sh', '-c', 'for i in 1 2 3 4 5; do ls -l /usr/bin; done']
    listing = {}
    for name in ['before', 'after', 'after', 'before'] * 3:
        start = time.perf_counter()
        listing[name] = subprocess.run([str(root / name / 'fhs-run')] + command, env=env, stdout=subprocess.PIPE, check=True).stdout
        real[name].append(time.perf_counter() - start)
    assert listing['before'] == listing['after'] and len(listing['after']) > 10000
print(f'PASS: all {15 * args.n} calls returned the expected result under both supervisors')
plain = {}
for line in direct.splitlines():
    key, value = line.split()
    plain.setdefault(key, []).append(float(value))
for key in results['before']:
    if key == 'ok':
        continue
    before = statistics.median(results['before'][key]) / args.n * 1e6
    after = statistics.median(results['after'][key]) / args.n * 1e6
    none = statistics.median(plain[key]) / args.n * 1e6
    print(f'{key}: {before:.2f} -> {after:.2f} us per call ({before/after:.2f}x); without supervisor {none:.2f}')
before, after = statistics.median(real['before']), statistics.median(real['after'])
print(f'ls -l /usr/bin x5 under the supervisor, identical output: {before:.3f} -> {after:.3f} s ({before/after:.2f}x), median of 6')
