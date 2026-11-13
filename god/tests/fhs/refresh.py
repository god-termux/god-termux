"""dapt refresh_caches: a cache is rebuilt only when its inputs or the cache file changed. Temporary tree, fake tools."""
from pathlib import Path
import os
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[3]
defs = (repo / 'god/runtime/dapt').read_text().split('\nexport APT_CONFIG=')[0]
bash = '/data/data/com.termux/files/usr/bin/bash'
with tempfile.TemporaryDirectory(prefix='dapt-refresh-') as tmp:
    root = Path(tmp)
    prefix, deb, log = root / 'prefix', root / 'debian', root / 'log'
    (prefix / 'glibc/shim').mkdir(parents=True)
    (prefix / 'bin').mkdir()
    (prefix / 'bin/sh').symlink_to(bash)
    caches = {
        'pixbuf': deb / 'usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders.cache',
        'schemas': deb / 'usr/share/glib-2.0/schemas/gschemas.compiled',
        'mime': deb / 'usr/share/mime/mime.cache',
        'icons': deb / 'usr/share/icons/hicolor/icon-theme.cache',
    }
    inputs = {
        'pixbuf': deb / 'usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/a.so',
        'schemas': deb / 'usr/share/glib-2.0/schemas/a.gschema.xml',
        'mime': deb / 'usr/share/mime/packages/a.xml',
        'icons': deb / 'usr/share/icons/hicolor/16x16/apps/a.png',
    }
    for p in inputs.values():
        p.parent.mkdir(parents=True)
        p.write_text('1')
    (deb / 'usr/share/icons/hicolor/index.theme').write_text('[Icon Theme]\n')
    # Stand-in for "fhs-run sh -c SCRIPT": records which caches the script would rebuild and writes their cache files,
    # except those listed in $FAIL (tool missing or failed: no name is printed).
    fake = prefix / 'glibc/shim/fhs-run'
    fake.write_text('#!' + bash + '\n' + 'script=$3; line=\n'
        + ''.join(f'case "$script" in *"echo {n}"*) line+=" {n}"; case " $FAIL " in *" {n} "*) ;; *) printf x >> "{c}"; echo {n};; esac;; esac\n'
                  for n, c in caches.items())
        + 'case "$script" in *PKGSYSTEM_ENABLE_FSYNC=0*) line+=" nofsync";; esac\n'
        + 'case "$script" in *"fc-cache -s"*) line+=" fc";; esac\n'
        + f'echo "$line" >> "{log}"\n')
    fake.chmod(0o700)
    env = dict(os.environ, PREFIX=str(prefix), DAPT_ROOT=str(deb))
    env.pop('LD_PRELOAD', None)

    def refresh(arg='', fail=''):
        log.write_text('')
        subprocess.run(['/system/bin/linker64', bash, '-c', defs + '\nrefresh_caches ' + arg], env=dict(env, FAIL=fail),
                       check=True, capture_output=True, text=True)
        return log.read_text().split()

    def touch(p, text):
        time.sleep(0.02)
        p.write_text(text)

    assert refresh() == ['pixbuf', 'schemas', 'mime', 'icons', 'nofsync', 'fc'], 'first run rebuilds everything'
    assert refresh() == ['fc'], 'nothing changed: only fc-cache, which checks itself'
    touch(inputs['icons'].with_name('b.png'), '2')
    assert refresh() == ['icons', 'fc'], 'new input file'
    touch(inputs['schemas'], '22')
    assert refresh() == ['schemas', 'fc'], 'changed input file'
    inputs['pixbuf'].unlink()
    assert refresh() == ['pixbuf', 'fc'], 'removed input file'
    touch(caches['mime'], 'rewritten by a maintainer script')
    assert refresh() == ['mime', 'nofsync', 'fc'], 'cache rewritten by something else'
    assert refresh() == ['fc']
    touch(inputs['mime'], '333')
    assert refresh(fail='mime') == ['mime', 'nofsync', 'fc']
    assert refresh() == ['mime', 'nofsync', 'fc'], 'a failed rebuild is retried'
    assert refresh() == ['fc']
    assert refresh('force') == ['pixbuf', 'schemas', 'mime', 'icons', 'nofsync', 'fc'], 'dapt refresh rebuilds everything'
    assert refresh() == ['fc']
    for p in list(inputs.values()) + list(caches.values()):
        p.unlink(missing_ok=True)
    (deb / 'usr/share/icons/hicolor/16x16/apps/b.png').unlink()
    (deb / 'usr/share/icons/hicolor/index.theme').unlink()
    assert refresh() == ['fc'], 'no inputs and no cache: nothing to build'
    print('PASS: caches rebuilt only for new/changed/removed inputs or a foreign rewrite; failures retried; force rebuilds all; MIME runs without fsync')
