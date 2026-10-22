#!/usr/bin/env python3
# Test double for libxodus_service.so: Unix socket echo server, clean exit on SIGINT.
import os, signal, socket, sys, threading
path = os.path.join(os.environ["XDG_RUNTIME_DIR"], "xodus.sock")
srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
srv.bind(path); srv.listen(8)
def stop(*_):
    try: os.unlink(path)
    except OSError: pass
    os._exit(0)
signal.signal(signal.SIGINT, stop)
def serve(conn):
    with conn:
        while True:
            data = conn.recv(4096)
            if not data: return
            conn.sendall(b"echo:" + data)
while True:
    conn, _ = srv.accept()
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
