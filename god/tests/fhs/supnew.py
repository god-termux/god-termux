"""fhs-run supervisor: concurrency, umask, structure sizes, many threads (supnew.c).

Builds the baseline supervisor (git revision, default 45be14f7 = before the supervisor work) and the working tree one in a
temporary directory, runs every scenario under both, and checks:
  * the working tree supervisor gives the expected result in every scenario;
  * the baseline fails the scenarios that describe a known defect (so the tests really detect it).
Nothing is installed. The harness runs with umask 022 so that a supervisor ignoring the program's umask is visible.

    python3 god/tests/fhs/supnew.py [--baseline-rev REV] [--only NAME]
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import tempfile
import threading
import time

repo = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline-rev', default='45be14f7')
parser.add_argument('--only')
args = parser.parse_args()

env = {k: v for k, v in os.environ.items() if k not in ('LD_PRELOAD', 'FHS_TRACE', 'FHS_DEBIAN', 'FHS_SUP', 'FHS_FAST_ABI')}
os.umask(0o022)
tmpbase = os.environ['PREFIX'] + '/tmp'


def parse(text):
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


def run_test(fhs_run, prog, test, release_fifo_after=None):
    """Run one scenario under a supervisor in a fresh scratch directory; returns (returncode, parsed output)."""
    with tempfile.TemporaryDirectory(prefix='fhs-supnew-', dir=tmpbase) as scratch:
        name = Path(scratch).name
        (Path(scratch) / 'data').write_bytes(b'x' * 12345)
        fifo = Path(scratch) / 'fifo'
        if test == 'concurrent':
            os.mkfifo(fifo)
            def release():
                time.sleep(release_fifo_after)
                try:
                    fd = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
                    os.close(fd)
                except OSError:
                    try:
                        fd = os.open(fifo, os.O_WRONLY)
                        os.close(fd)
                    except OSError:
                        pass
            threading.Thread(target=release, daemon=True).start()
        e = dict(env, SUPNEW_REAL=scratch)
        proc = subprocess.run(['/system/bin/linker64', str(fhs_run), str(prog), test, name], env=e, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=90)
        leftovers = [p.name for p in Path(scratch).iterdir() if p.name not in ('data', 'fifo')]
        res = parse(proc.stdout)
        if test == 'stress':
            res['leftover_files'] = str(len(leftovers))
        return proc.returncode, res


EXPECT = {
    'umask': {'umask0_file': '0666', 'umask0_dir': '0777', 'umask077_file': '0600', 'umask077_dir': '0700', 'umask022_file664': '0644'},
    'statsize': {'stat_rc': '0', 'stat_size': '12345', 'stat_regular': '1', 'stat_guard_intact': '1', 'stat_equals_direct': '1',
                 'statx_rc': '0', 'statx_size': '12345', 'statx_guard_intact': '1'},
    'stress': {'stress_errors': '0', 'leftover_files': '0'},
}


def check(test, res):
    """Returns a list of problems (empty = scenario passed)."""
    bad = []
    if res.get('shim_loaded') != '0':
        bad.append(f"the shim is loaded into the program (shim_loaded={res.get('shim_loaded')}): this would test the shim, not the supervisor")
    if test == 'concurrent':
        if res.get('concurrent_stat_rc') != '0':
            bad.append(f"stat rc {res.get('concurrent_stat_rc')}")
        ms = float(res.get('concurrent_stat_ms', 'inf'))
        if not ms < 1000:
            bad.append(f'stat waited {ms:.0f} ms for a supervisor blocked in open() of a FIFO (limit 1000 ms)')
        if res.get('concurrent_done') != '1':
            bad.append('blocked open() never completed')
    else:
        for k, v in EXPECT[test].items():
            if res.get(k) != v:
                bad.append(f'{k}: got {res.get(k)}, expected {v}')
    return bad


with tempfile.TemporaryDirectory(prefix='fhs-supnew-build-') as tmp:
    root = Path(tmp)
    subprocess.run(['clang', '-O2', '-w', str(repo / 'god/tests/fhs/supnew.c'), '-o', str(root / 'supnew'), '-pthread'], check=True)
    base = root / 'base'
    base.mkdir()
    archive = subprocess.Popen(['git', 'archive', args.baseline_rev, 'god/fhs'], cwd=repo, stdout=subprocess.PIPE)
    subprocess.run(['tar', '-x', '-C', str(base)], stdin=archive.stdout, check=True)
    assert archive.wait() == 0
    new = root / 'new'
    shutil.copytree(repo / 'god/fhs', new)
    runs = {}
    for name, d in (('baseline', base / 'god/fhs'), ('new', new)):
        subprocess.run(['clang', '-O2', '-w', str(d / 'fhs-run.c'), '-o', str(d / 'fhs-run'), '-pthread'], check=True)
        runs[name] = d / 'fhs-run'
    tests = ['concurrent', 'umask', 'statsize', 'stress']
    if args.only:
        tests = [args.only]
    failures = []
    known_defects = {'concurrent', 'umask'}   # the baseline must fail these, otherwise the scenario proves nothing
    for test in tests:
        for name in ('new', 'baseline'):
            try:
                rc, res = run_test(runs[name], root / 'supnew', test, release_fifo_after=3)
                bad = check(test, res) if rc == 0 else [f'exit status {rc}']
            except subprocess.TimeoutExpired:
                bad = ['timeout (supervisor deadlocked)']
            if name == 'new':
                print(f"{'FAIL' if bad else 'PASS'}  new       {test}" + (': ' + '; '.join(bad) if bad else ''))
                failures += [f'new/{test}: {b}' for b in bad]
            else:
                detected = bool(bad)
                note = ('fails as expected: ' + '; '.join(bad)) if detected else 'passes'
                print(f"INFO  baseline  {test}: {note}")
                if test in known_defects and not detected:
                    failures.append(f'baseline/{test}: expected to fail (known defect) but passed: the scenario does not detect it')
    if failures:
        print('\n'.join(['FAILED:'] + failures))
        raise SystemExit(1)
    print(f'PASS: {len(tests)} scenarios')
