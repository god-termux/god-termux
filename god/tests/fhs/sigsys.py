"""SIGSYS -> ENOSYS in the glibc shim (Android's app seccomp filter kills openat2/437 with SIGSYS).

Builds libfhs-glibc.so from god/fhs/fhs.c and the test program god/tests/fhs/sigsys.c into a temporary directory (nothing is installed)
and runs four scenarios. Processes are started through `linker64 env`: an ordinary `env` carries the installed shim, which replaces
LD_PRELOAD on exec.  Run: python3 god/tests/fhs/sigsys.py   (needs: pkg install glibc-repo glibc)
"""
from pathlib import Path
import os
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
prefix = os.environ['PREFIX']
genv = dict(os.environ, PATH=prefix + '/glibc/bin:' + os.environ['PATH'])
genv.pop('LD_PRELOAD', None)
gcc = 'aarch64-linux-gnu-gcc'


def run(lib, mode, **extra):
    cmd = ['/system/bin/linker64', prefix + '/bin/env'] + (['LD_PRELOAD=' + str(lib)] if lib else [])
    cmd += [f'{k}={v}' for k, v in extra.items()] + [str(root / 'sigsys'), mode]
    p = subprocess.run(cmd, env=genv, capture_output=True, text=True, timeout=60)
    return p.stdout + p.stderr


def check(name, ok, out):
    print(('ok   ' if ok else 'FAIL ') + name)
    if not ok:
        print(out)
        raise SystemExit(1)


with tempfile.TemporaryDirectory(prefix='fhs-sigsys-', dir=prefix + '/tmp') as tmp:
    root = Path(tmp)
    subprocess.run([gcc, '-O1', '-o', str(root / 'sigsys'), str(repo / 'god/tests/fhs/sigsys.c')], check=True, env=genv)
    lib = root / 'libfhs-glibc.so'
    subprocess.run([gcc, '-shared', '-fPIC', '-O2', '-DGLIBC_BUILD', '-fuse-ld=bfd', '-o', str(lib), str(repo / 'god/fhs/fhs.c'), '-ldl'],
                   check=True, env=genv)

    out = run(None, 'raw')
    check('без шима openat2 убивает процесс сигналом 31', 'убит сигналом 31' in out, out)

    out = run(lib, 'raw', FHS_SIGSYS_LOG=1)
    check('с шимом openat2 -> ENOSYS (38)', 'errno=38' in out and 'завершился сам, код 0' in out, out)
    check('после ENOSYS работает обычный openat', 'openat fallback -> ' in out and 'openat fallback -> -' not in out, out)
    check('FHS_SIGSYS_LOG печатает номер вызова', 'fhs: SIGSYS syscall 437 -> ENOSYS' in out, out)

    out = run(lib, 'crash')
    check('собственный обработчик SIGSYS программы не устанавливается', 'APP-обработчик' not in out and 'завершился сам, код 0' in out, out)

    out = run(lib, 'raw', FHS_SIGSYS=0)
    check('FHS_SIGSYS=0 возвращает прежнее поведение (сигнал 31)', 'убит сигналом 31' in out, out)

print('все проверки прошли')
