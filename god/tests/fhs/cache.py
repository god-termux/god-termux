"""Benchmark workloads against a supplied previous fhsmap.h (or git baseline)."""
from pathlib import Path
import argparse
import os
import statistics
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path, help='previous fhsmap.h; default: aac2c260')
args = parser.parse_args()
base = args.baseline.read_text() if args.baseline else subprocess.check_output(
    ['git', 'show', 'aac2c260:god/fhs/fhsmap.h'], cwd=repo, text=True)
env = dict(os.environ)
env.pop('LD_PRELOAD', None)
env.pop('FHS_TRACE', None)
results = {'before': {}, 'after': {}}
with tempfile.TemporaryDirectory(prefix='fhs-cache-') as tmp:
    root = Path(tmp)
    (root / 'cache.c').write_text((repo / 'god/tests/fhs/cache.c').read_text())
    for name, header in [('before', base), ('after', (repo / 'god/fhs/fhsmap.h').read_text())]:
        (root / 'fhsmap.h').write_text(header)
        subprocess.run(['clang', '-O2', str(root / 'cache.c'), '-o', str(root / name)], check=True)
    for name in ['before', 'after', 'after', 'before']:
        output = subprocess.check_output(['/system/bin/linker64', str(root / name)], env=env, text=True)
        for line in output.splitlines():
            key, value = line.split()
            results[name].setdefault(key, []).append(float(value))
for key in results['before']:
    before = statistics.median(results['before'][key])
    after = statistics.median(results['after'][key])
    print(f'{key}: {before*1000:.3f} -> {after*1000:.3f} ms per 100000 calls ({before/after:.2f}x)')
