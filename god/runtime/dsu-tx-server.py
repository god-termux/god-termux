#!/data/data/com.termux/files/usr/bin/python3
"""dsu-tx-server: executes Termux commands for the `tx` client running in a `dsu --tx` shell session (uid 2000).

Started and stopped by `dsu --tx` (child process); never run it by hand and never as a daemon.
  * listens on 127.0.0.1 only, random port, port written to the file named by DSU_TX_PORTFILE
  * every request must start with the session token (DSU_TX_TOKEN, compared in constant time)
  * protocol (one request per connection, newline separated header, then raw stdin until EOF):
        TOKEN
        run ID          | status ID
        BASE64(cwd)     \\ only for "run"
        BASE64(argv)    / argv separated by NUL, executed directly (no shell, no eval)
    "run" streams stdout+stderr (merged) of the command back and closes the connection;
    "status ID" (a second connection) returns the exit code of the finished run ID.
  * at most 4 concurrent runs, 2 s to send the header, log in $PREFIX/tmp/dsu-tx.log (no tokens)
  * exits when its parent (`dsu`) dies, kills running children on exit
"""
import base64
import hmac
import os
import signal
import socket
import sys
import threading
import time

TOKEN = os.environ.pop("DSU_TX_TOKEN", "").encode()
PORTFILE = os.environ.pop("DSU_TX_PORTFILE", "")
PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
LOG = os.environ.get("DSU_TX_LOG", os.path.join(PREFIX, "tmp", "dsu-tx.log"))
MAX_RUNS = 4
HEADER_TIMEOUT = 2.0
RESULT_TTL = 300

if len(TOKEN) < 32 or not PORTFILE:
    sys.stderr.write("dsu-tx-server: нужны DSU_TX_TOKEN (>=32 байт) и DSU_TX_PORTFILE\n")
    sys.exit(2)

import subprocess  # noqa: E402  (after the sanity check)

lock = threading.Lock()
slots = threading.BoundedSemaphore(MAX_RUNS)
procs = set()
results = {}  # id -> (rc, time)
port = 0


def log(msg):
    try:
        with lock, open(LOG, "a") as f:
            f.write("%s %d %s\n" % (time.strftime("%F %T"), port, msg))
    except OSError:
        pass


def say(conn, text):
    conn.sendall(text.encode("utf-8"))


def show(argv):
    s = b" ".join(argv)[:200]
    return s.decode("utf-8", "replace").replace("\n", "\\n")


def kill_all():
    with lock:
        ps = list(procs)
    for p in ps:
        try:
            p.kill()
        except OSError:
            pass


def read_line(f, limit=65536):
    line = f.readline(limit)
    if not line.endswith(b"\n"):
        raise ValueError("incomplete header line")
    return line[:-1]


def handle(conn, peer):
    conn.settimeout(HEADER_TIMEOUT)
    f = conn.makefile("rb")
    try:
        try:
            tok = read_line(f, 512)
            if not hmac.compare_digest(tok, TOKEN):
                log("отказ: неверный токен (peer %s:%d)" % peer)
                say(conn, "tx: отказ: неверный токен\n")
                return
            op = read_line(f, 256).split()
            if len(op) != 2 or op[0] not in (b"run", b"status") or len(op[1]) > 64:
                log("отказ: неверный запрос")
                say(conn, "tx: неверный запрос\n")
                return
            if op[0] == b"status":
                now = time.time()
                with lock:
                    for k in [k for k, v in results.items() if now - v[1] > RESULT_TTL]:
                        del results[k]
                    r = results.pop(op[1], None)
                conn.sendall(b"%d\n" % r[0] if r else b"unknown\n")
                return
            cwd = base64.b64decode(read_line(f, 8192)).decode("utf-8", "replace")
            argv = base64.b64decode(read_line(f, 1 << 20)).split(b"\0")
            if argv and argv[-1] == b"":
                argv.pop()  # only the terminator after the last NUL; empty arguments in the middle/end are kept
            if not argv or not argv[0]:
                say(conn, "tx: пустая команда\n")
                return
        except (socket.timeout, ValueError, OSError) as e:
            log("отказ: нет корректного заголовка за %.0f с (%s)" % (HEADER_TIMEOUT, e.__class__.__name__))
            return
        conn.settimeout(None)
        if not slots.acquire(blocking=False):
            log("отказ: слишком много одновременных команд: %s" % show(argv))
            say(conn, "tx: слишком много одновременных команд (максимум %d)\n" % MAX_RUNS)
            with lock:
                results[op[1]] = (125, time.time())
            return
        rid = op[1]
        rc = 255
        try:
            if not os.path.isdir(cwd):
                cwd = os.environ.get("HOME", "/")
            try:
                p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, cwd=cwd, close_fds=True)
            except OSError as e:
                msg = "tx: %s: %s\n" % (argv[0].decode("utf-8", "replace"), e.strerror)
                conn.sendall(msg.encode())
                rc = 127 if isinstance(e, FileNotFoundError) else 126
                log("run %s -> %d (%s)" % (show(argv), rc, e.strerror))
                return
            with lock:
                procs.add(p)

            def feed():
                try:
                    while True:
                        d = f.read1(65536) if hasattr(f, "read1") else f.read(65536)
                        if not d:
                            break
                        p.stdin.write(d)
                        p.stdin.flush()
                except (OSError, ValueError):
                    pass
                finally:
                    try:
                        p.stdin.close()
                    except OSError:
                        pass
            threading.Thread(target=feed, daemon=True).start()
            try:
                while True:
                    d = os.read(p.stdout.fileno(), 65536)
                    if not d:
                        break
                    conn.sendall(d)
            except OSError:
                p.kill()  # client went away
            rc = p.wait()
            if rc < 0:
                rc = 128 - rc  # killed by signal N -> 128+N, like a shell
            with lock:
                procs.discard(p)
            log("run %s -> %d" % (show(argv), rc))
        finally:
            with lock:
                results[rid] = (rc, time.time())
            slots.release()
    finally:
        try:
            conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        conn.close()


def watch_parent(ppid):
    while True:
        time.sleep(1)
        if os.getppid() != ppid:
            kill_all()
            os._exit(0)


def main():
    global port
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(8)
    port = srv.getsockname()[1]
    tmp = PORTFILE + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, b"%d\n" % port)
    os.close(fd)
    os.rename(tmp, PORTFILE)
    log("старт, порт %d" % port)

    def bye(*_):
        kill_all()
        log("остановлен")
        os._exit(0)
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)
    threading.Thread(target=watch_parent, args=(os.getppid(),), daemon=True).start()
    while True:
        conn, peer = srv.accept()
        threading.Thread(target=handle, args=(conn, peer), daemon=True).start()


if __name__ == "__main__":
    main()
