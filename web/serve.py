#!/usr/bin/env python3
"""Local preview server for the STREAMR web player.

Serves this directory with the same headers _headers declares, so the embed route behaves the way it
will on Netlify/Cloudflare. Statics only — there is no backend here; sourcesUrl points somewhere else.

    python3 serve.py            # http://0.0.0.0:8080
    PORT=9000 python3 serve.py
"""
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))

# Demo policy: "" means any parent may frame the player, which makes an <iframe> test trivial.
# Deploy with the value from _headers instead ('self' plus agreed partners).
EMBED_FRAME_ANCESTORS = os.environ.get("EMBED_FRAME_ANCESTORS", "*")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        path = self.path.split("?")[0].split("#")[0]
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
        if path.endswith("/embed.html"):
            self.send_header("Content-Security-Policy", "frame-ancestors " + EMBED_FRAME_ANCESTORS)
        elif path.endswith(".html"):
            self.send_header("Content-Security-Policy", "frame-ancestors 'self'")
        if path.startswith("/assets/"):
            self.send_header("Cache-Control", "public, max-age=300")
        else:
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("[web] " + fmt % args, flush=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"[web] serving {ROOT} on http://0.0.0.0:{port}", flush=True)
    server.serve_forever()
