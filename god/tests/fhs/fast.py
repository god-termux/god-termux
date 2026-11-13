"""Fast path past the fhs-run supervisor: same answers with and without it, and far fewer intercepted calls.

Builds the supervisor, the bionic shim and the dapt helper from the working tree into a temporary directory; nothing is installed.
Programs are started as "linker64 fhs-run env LD_PRELOAD=... PROGRAM" so that the shim of the calling shell cannot replace them.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[3]
prefix = os.environ['PREFIX']
env0 = {k: v for k, v in os.environ.items() if k not in ('LD_PRELOAD', 'FHS_TRACE', 'FHS_DEBIAN', 'FHS_SUP', 'FHS_FAST_ABI', 'FHS_FAST_OFF', 'FHS_SUP_ACCT')}
with tempfile.TemporaryDirectory(prefix='fhs-fast-') as tmp:
    root = Path(tmp)
    fhs = repo / 'god/fhs'
    subprocess.run(['clang', '-O2', '-w', str(fhs / 'fhs-run.c'), '-o', str(root / 'fhs-run')], check=True)
    subprocess.run(['clang', '-shared', '-fPIC', '-O2', '-w', str(fhs / 'fhs.c'), '-o', str(root / 'libfhs-bionic.so'), '-ldl'], check=True)
    subprocess.run(['clang', '-shared', '-fPIC', '-O2', '-w', str(repo / 'god/runtime/nolink.c'), '-o', str(root / 'libdapt-nolink.so')], check=True)
    subprocess.run(['clang', '-O2', '-w', str(repo / 'god/tests/fhs/fastops.c'), '-o', str(root / 'ops')], check=True)
    subprocess.run(['clang', '-O2', '-w', str(repo / 'god/tests/fhs/sigops.c'), '-o', str(root / 'sigops')], check=True)
    shim, nolink, stats = str(root / 'libfhs-bionic.so'), str(root / 'libdapt-nolink.so'), root / 'stats'
    gcc = prefix + '/glibc/bin/aarch64-linux-gnu-gcc'
    have_glibc = Path(gcc).exists()
    if have_glibc:
        genv = dict(os.environ, PATH=prefix + '/glibc/bin:' + os.environ['PATH'])
        subprocess.run([gcc, '-shared', '-fPIC', '-O2', '-w', '-DGLIBC_BUILD', '-fuse-ld=bfd', str(fhs / 'fhs.c'), '-o', str(root / 'libfhs-glibc.so'), '-ldl'], check=True, env=genv)
        subprocess.run([gcc, '-O2', '-w', str(repo / 'god/tests/fhs/fastops.c'), '-o', str(root / 'gops'), '-ldl'], check=True, env=genv)

    def supervised(command, preload, **extra):
        """Run command under the new supervisor; returns (stdout, intercepted calls, seconds)."""
        stats.unlink(missing_ok=True)
        started = time.perf_counter()
        run = subprocess.run(['/system/bin/linker64', str(root / 'fhs-run'), prefix + '/bin/env', 'LD_PRELOAD=' + preload] + command,
                             env=dict(env0, FHS_SUP_STATS=str(stats), **extra), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        elapsed = time.perf_counter() - started
        assert run.returncode == 0, (command[:2], preload, extra, run.returncode, run.stderr[-400:])
        for _ in range(100):            # the supervisor writes the count after the last process is gone
            if stats.exists() and stats.read_text().endswith('\n'):
                break
            time.sleep(0.02)
        return run.stdout, int(stats.read_text().split('=')[1]), elapsed

    # 1. libc read calls: identical output without the shim, with the fast path, and with the fast path switched off
    for mode in ({}, {'FHS_DEBIAN': '1'}):
        out = {}
        for name, preload, extra in [('supervisor only', '', {}), ('fast path', shim, {}), ('fast path off', shim, {'FHS_FAST_OFF': '1'})]:
            with tempfile.TemporaryDirectory(prefix='fhs-fastops-', dir=prefix + '/tmp') as scratch:
                text, count, _ = supervised([str(root / 'ops'), '/tmp/' + Path(scratch).name], preload, **mode, **extra)
                out[name] = (text.replace(Path(scratch).name, 'SCRATCH'), count)
                assert not list(Path(scratch).iterdir()), 'scratch directory not empty'
        base = out['supervisor only']
        assert len(base[0].splitlines()) > 90
        # realpath() and fchmodat(AT_SYMLINK_NOFOLLOW) open with O_PATH inside libc: they used to fail with EBADF under the supervisor
        assert 'realpath 0 [<absolute host path>]' in base[0].split('/usr/bin/sh\n', 1)[1].split('\n/', 1)[0], 'realpath of a translated name failed'
        assert 'fchmodat 0 nofollow 0 mode 604' in base[0], 'fchmodat(AT_SYMLINK_NOFOLLOW) on a translated name failed'
        for name in ['fast path', 'fast path off']:
            assert out[name][0] == base[0], f'{name} {mode}: output differs\n' + '\n'.join(
                a + '\n' + b for a, b in zip(base[0].splitlines(), out[name][0].splitlines()) if a != b)
        assert out['fast path'][1] * 1.5 < base[1], (out['fast path'][1], base[1])   # writes and libc-internal calls stay intercepted
        assert out['fast path off'][1] > base[1] * 0.8, (out['fast path off'][1], base[1])
        print(f"PASS: {len(base[0].splitlines())} result lines identical ({'Debian mode' if mode else 'normal mode'}); intercepted calls: "
              f"supervisor only {base[1]}, fast path {out['fast path'][1]}, fast path off {out['fast path off'][1]}")

    # 1b. the same program built for glibc under the glibc shim: its hooks give the same answers through libc and through tagged calls.
    # (Compared with itself only: the glibc shim translates creating calls by its own rules, with or without a supervisor.)
    if have_glibc:
        gshim = str(root / 'libfhs-glibc.so')
        for mode in ({}, {'FHS_DEBIAN': '1'}):
            out = {}
            for name, extra in [('fast path', {}), ('fast path off', {'FHS_FAST_OFF': '1'})]:
                with tempfile.TemporaryDirectory(prefix='fhs-fastops-', dir=prefix + '/tmp') as scratch:
                    text, count, _ = supervised([str(root / 'gops'), '/tmp/' + Path(scratch).name], gshim, **mode, **extra)
                    out[name] = (text.replace(Path(scratch).name, 'SCRATCH'), count)
                    assert not list(Path(scratch).iterdir()), 'scratch directory not empty'
            assert len(out['fast path'][0].splitlines()) > 90 and out['fast path'][0] == out['fast path off'][0], f'glibc {mode}: output differs\n' + '\n'.join(
                a + '\n' + b for a, b in zip(out['fast path off'][0].splitlines(), out['fast path'][0].splitlines()) if a != b)
            assert out['fast path'][1] * 1.5 < out['fast path off'][1], (out['fast path'][1], out['fast path off'][1])
            print(f"PASS: glibc shim, {len(out['fast path'][0].splitlines())} result lines identical ({'Debian mode' if mode else 'normal mode'}); "
                  f"intercepted calls: fast path off {out['fast path off'][1]}, fast path {out['fast path'][1]}")
    else:
        print('SKIP: glibc toolchain not installed, glibc shim not tested')

    # 1c. signal handlers that start in the middle of fast-path calls and make libc-internal path calls of their own
    calls, bad, runs, untranslated = map(int, supervised([str(root / 'sigops')], shim)[0].split())
    assert bad == 0 and untranslated == 0 and runs > 500, (calls, bad, runs, untranslated)
    print(f'PASS: {runs} signal handlers interrupted {calls} fast-path calls; every opendir() inside a handler was still translated')

    # 2. dpkg unpacking a synthetic package into a temporary tree (no maintainer scripts, nothing outside the tree is written)
    if not shutil.which('dpkg-deb') or not shutil.which('dpkg'):
        print('SKIP: dpkg not available')
        raise SystemExit
    files = 300
    pkg = root / 'pkg'
    for i in range(files):
        d = pkg / 'opt/fhs-bench' / f'd{i % 20}'
        d.mkdir(parents=True, exist_ok=True)
        (d / f'f{i}').write_text('x' * (100 + i % 900))
    (pkg / 'opt/fhs-bench/link').symlink_to('d0/f0')
    (pkg / 'DEBIAN').mkdir()
    (pkg / 'DEBIAN/control').write_text('Package: fhs-bench\nVersion: 1\nArchitecture: all\nMaintainer: t <t@localhost>\nDescription: test\n synthetic\n')
    os.chmod(pkg, 0o755)
    os.chmod(pkg / 'DEBIAN', 0o755)
    # Debian packages carry root:root; a package built on the phone without --root-owner-group carries the app user
    subprocess.run(['dpkg-deb', '--root-owner-group', '--build', '-Zgzip', str(pkg), str(root / 'root.deb')], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['dpkg-deb', '--build', '-Zgzip', str(pkg), str(root / 'user.deb')], check=True, stdout=subprocess.DEVNULL)

    def install(deb, preload, **extra):
        with tempfile.TemporaryDirectory(prefix='fhs-dpkg-', dir=prefix + '/tmp') as inst:
            t = Path(inst)
            (t / 'var/lib/dpkg/info').mkdir(parents=True)
            (t / 'var/lib/dpkg/updates').mkdir()
            (t / 'var/lib/dpkg/status').write_text('')
            (t / 'var/lib/dpkg/available').write_text('')
            _, count, elapsed = supervised([prefix + '/bin/dpkg', f'--admindir={t}/var/lib/dpkg', f'--instdir={t}', '--force-architecture',
                                            '--force-script-chrootless', '--force-depends', '--log=/dev/null', '-i', str(root / deb)],
                                           preload, FHS_DEBIAN='1', **extra)
            tree = sorted((str(p.relative_to(t)), p.lstat().st_mode, p.lstat().st_size if p.is_file() and not p.is_symlink() else 0,
                           os.readlink(p) if p.is_symlink() else '') for p in (t / 'opt').rglob('*'))
            listed = sorted((t / 'var/lib/dpkg/info/fhs-bench.list').read_text().splitlines())
        return tree, listed, count, elapsed

    plain = install('root.deb', '')
    fast = install('root.deb', nolink + ':' + shim)
    assert len([e for e in plain[0] if e[2]]) == files and plain[0] == fast[0] and plain[1] == fast[1], 'installed tree differs'
    assert fast[2] < plain[2] * 0.5, (fast[2], plain[2])   # what remains is mostly the dynamic linker of each started program
    print(f'PASS: dpkg, {files} files owned by root: same tree; intercepted calls {plain[2]} -> {fast[2]} with the fast path '
          f'({plain[3]:.2f} -> {fast[3]:.2f} s)')
    user = install('user.deb', nolink + ':' + shim)
    assert user[0] == plain[0], 'installed tree differs (non-root owner)'
    assert user[2] < files * 60, f'{user[2]} intercepted calls for {files} files: account lookups are flooding logd again'
    line = f'PASS: dpkg, {files} files owned by a name bionic does not know: {user[2]} intercepted calls ({user[3]:.2f} s)'
    if Path(prefix + '/debian/etc/group').exists():
        flood = install('user.deb', nolink + ':' + shim, FHS_SUP_ACCT='1')
        assert flood[0] == plain[0]
        line += f'; with the redirect of account files restored (FHS_SUP_ACCT=1): {flood[2]} ({flood[3]:.2f} s)'
    print(line)
