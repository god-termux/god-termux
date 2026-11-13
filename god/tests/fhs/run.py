"""Run shim ownership regressions in temporary files on aarch64 Termux."""
from pathlib import Path
import os
import struct
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
env = dict(os.environ)
env.pop('LD_PRELOAD', None)
with tempfile.TemporaryDirectory(prefix='fhs-memory-') as tmp:
    root = Path(tmp)
    script = root / 'script'
    script.write_text('#!' + str(root / 'missing') + ' option\n')
    script.chmod(0o700)
    elf = root / 'glibc-elf'
    interp = b'/lib/ld-linux-aarch64.so.1\0'
    ident = b'\x7fELF\x02\x01\x01' + b'\0' * 9
    header = struct.pack('<16sHHIQQQIHHHHHH', ident, 2, 183, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    ph = struct.pack('<IIQQQQQQ', 3, 0, 120, 0, 0, len(interp), len(interp), 1)
    elf.write_bytes(header + ph + interp)
    subprocess.run(['clang', '-O1', '-g', str(repo / 'god/tests/fhs/memory.c'), '-ldl', '-o', str(root / 'memory')], check=True)
    subprocess.run(['/system/bin/linker64', str(root / 'memory'), str(script), str(elf)], env=env, check=True)
