"""Compare mappings with the baseline and benchmark warm cache hits, read-only."""
from pathlib import Path
import os
import statistics
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
env = dict(os.environ)
env.pop('LD_PRELOAD', None)
env.pop('FHS_TRACE', None)
base = subprocess.check_output(['git', 'show', 'aac2c260:god/fhs/fhsmap.h'], cwd=repo, text=True)
results = {}
with tempfile.TemporaryDirectory(prefix='fhs-paths-') as tmp:
    root = Path(tmp)
    (root / 'paths.c').write_text((repo / 'god/tests/fhs/paths.c').read_text())
    for name, source in [('before', base), ('after', (repo / 'god/fhs/fhsmap.h').read_text())]:
        (root / 'fhsmap.h').write_text(source)
        subprocess.run(['clang', '-O2', str(root / 'paths.c'), '-o', str(root / name)], check=True)
        text = subprocess.check_output(['/system/bin/linker64', str(root / name)], text=True, env=env)
        lines = text.splitlines()
        mappings = [s for s in lines if not s.startswith(('COUNTERS', 'BENCH'))]
        counters = next(s for s in lines if s.startswith('COUNTERS'))
        median = statistics.median(float(s.split()[1]) for s in lines if s.startswith('BENCH'))
        results[name] = (mappings, counters, median)
assert results['before'][0] == results['after'][0], 'Path mapping behavior changed'
assert results['after'][1] == 'COUNTERS 0 0', results['after'][1]
print(f'PASS: {len(results["after"][0])} mappings match (normal/Debian, read/create, cold/warm/invalidated/expired, long paths, cache churn, mutable input)')
for name, (_, counters, median) in results.items():
    print(f'{name}: {counters}; median of 5 x 200000 cached mappings: {median:.6f}s')
print(f'Warm mapping speedup: {results["before"][2] / results["after"][2]:.2f}x')
