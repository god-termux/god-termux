#!/data/data/com.termux/files/usr/bin/python3
"""webbench collector: serves bench.html on 127.0.0.1:PORT and appends the posted result to RESULT_FILE (one JSON per line). Ctrl+C / kill to stop."""
import http.server, json, os, sys
PORT = int(os.environ.get("WB_PORT", "8765")); OUT = os.environ.get("WB_OUT", os.path.join(os.environ.get("PREFIX", "/tmp"), "tmp", "webbench.log")); DIR = os.path.dirname(os.path.abspath(__file__))
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=DIR, **k)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n).decode("utf-8", "replace")
        with open(OUT, "a") as f: f.write(body + "\n")
        self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
