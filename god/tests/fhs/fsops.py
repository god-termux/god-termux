"""Creating calls with paths (rename/symlink/link/unlinkat/chown/utimensat/...) in a glibc program with the shim linked in."""
from pathlib import Path
import os, subprocess, tempfile

repo = Path(__file__).resolve().parents[3]
env = dict(os.environ); env.pop('LD_PRELOAD', None)
gcc = Path(os.environ['PREFIX']) / 'glibc/bin/aarch64-linux-gnu-gcc'
with tempfile.TemporaryDirectory(prefix='fhs-fsops-') as tmp:
    root = Path(tmp)
    (root / 'main.c').write_text('int bmain(int, char**);\nint main(int c, char** v) { return bmain(c, v); }\n')
    exe = root / 'fsops'
    subprocess.run([str(gcc), '-O1', '-g', '-DGLIBC_BUILD', '-fuse-ld=bfd', '-I', str(repo / 'god/fhs'), '-o', str(exe),
                    str(repo / 'god/fhs/fhs.c'), str(repo / 'god/tests/fhs/fsops.c'), str(root / 'main.c'), '-ldl'],
                   check=True, env={**env, 'PATH': f"{Path(os.environ['PREFIX'])}/glibc/bin:{env['PATH']}"})
    out = subprocess.run([str(exe)], env=env, capture_output=True, text=True)
    assert out.returncode == 0 and out.stdout.strip() == 'OK', out.stdout + out.stderr
print('PASS: rename/symlink/link/chmod/chown/utimes/unlinkat map /tmp into $PREFIX/tmp')
