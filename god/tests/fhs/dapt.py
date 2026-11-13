"""Exercise generated wrappers in a temporary tree; never invokes real apt/dpkg."""
from pathlib import Path
import os
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[3]
source = (repo / 'god/runtime/dapt').read_text()
base = subprocess.check_output(['git', 'show', 'HEAD:god/runtime/dapt'], cwd=repo, text=True)
with tempfile.TemporaryDirectory(prefix='dapt-opt-') as tmp:
    root = Path(tmp)
    prefix = root / 'prefix'
    bindir = prefix / 'bin'
    bindir.mkdir(parents=True)
    bash = Path('/data/data/com.termux/files/usr/bin/bash')
    (bindir / 'bash').symlink_to(bash)
    shim = prefix / 'glibc/shim'
    shim.mkdir(parents=True)
    (shim / 'libdapt-nolink.so').symlink_to('/data/data/com.termux/files/usr/glibc/shim/libdapt-nolink.so')
    dpkg = bindir / 'dpkg'
    dpkg.write_text('#!' + str(bash) + '\nexit 7\n')
    dpkg.chmod(0o700)
    env = dict(os.environ, PREFIX=str(prefix), DAPT_ROOT=str(root / 'debian'))
    env.pop('LD_PRELOAD', None)
    def run(defs, command):
        return subprocess.run(['/system/bin/linker64', str(bash), '-c', defs + '\n' + command], env=env,
                              text=True, capture_output=True, check=True)
    defs = source.split('\nexport APT_CONFIG=')[0]
    for args, expected in [
        (['--root', '/tmp/dapt-root', '--help'], '/tmp/dapt-root'),
        (['--root=/tmp/dapt-root', '--help'], '/tmp/dapt-root'),
        (['--help'], str(root / 'debian')),
    ]:
        result = subprocess.run(['/system/bin/linker64', str(bash), '-c', source, 'dapt', *args],
                                env=env, text=True, capture_output=True)
        assert result.returncode == 0 and expected in result.stderr, (args, result.stdout, result.stderr)
    run(defs, 'wrappers')
    wrappers = root / 'debian/.ddpkg-bin'
    before = {p.name: p.stat().st_mtime_ns for p in wrappers.iterdir()}
    run(defs, 'wrappers')
    assert before == {p.name: p.stat().st_mtime_ns for p in wrappers.iterdir()}
    (wrappers / 'mkdir').unlink()
    run(defs, 'wrappers')
    assert (wrappers / 'mkdir').is_file()
    wrapper = (wrappers / 'dpkg').read_text()
    assert 'PATH="$D/.ddpkg-bin:$D/usr/bin:$D/bin:$PATH' in wrapper
    (wrappers / '.version').write_text('old\n')
    run(defs, 'wrappers')
    assert (wrappers / '.version').read_text().startswith('4|')
    # --fix resolves the requested package and broken dependencies in the same Debian root.
    default_root = root / 'debian'
    (default_root / 'etc/apt').mkdir(parents=True, exist_ok=True)
    (default_root / 'etc/apt/apt.conf').write_text('test config\n')
    apt = bindir / 'apt-get'
    apt.write_text('#!' + str(bash) + '\nprintf "%s|%s\\n" "$APT_CONFIG" "$*" >> "$DAPT_LOG"\n')
    apt.chmod(0o700)
    log = root / 'apt.log'
    env['DAPT_LOG'] = str(log)
    result = subprocess.run(['/system/bin/linker64', str(bash), '-c', source, 'dapt', 'install', 'nano', '--fix'],
                            env=env, text=True, capture_output=True)
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert log.read_text().strip() == str(default_root / 'etc/apt/apt.conf') + '|--fix-broken install -y nano', log.read_text()
    assert not (default_root / 'packages/nano').exists()
    print('PASS: install PKG --fix uses apt-get in the existing Debian root')
    marker = root / 'repaired'
    (wrappers / 'fixlinks').write_text('#!' + str(bash) + '\nprintf x >> "' + str(marker) + '"\n')
    for action in ['--status', '--print-foreign-architectures', '--unpack', '--unknown']:
        result = subprocess.run(['/system/bin/linker64', str(bash), str(wrappers / 'dpkg'), action], env=env, capture_output=True)
        assert result.returncode == 7, (action, result.returncode, result.stderr)
        if action.startswith('--status') or action.startswith('--print'):
            assert not marker.exists()
    assert marker.read_text() == 'xx'
    print('PASS: unchanged wrappers cached; missing/versioned wrappers regenerated; queries skip fixlinks; mutations/unknown actions repair; exit code preserved')
    for name, text in [('before', base), ('after', source)]:
        started = time.perf_counter()
        run(text.split('\nexport APT_CONFIG=')[0], 'for ((i=0; i<20; i++)); do wrappers; done')
        print(f'{name}: 20 wrapper checks/generations = {time.perf_counter() - started:.3f}s')
