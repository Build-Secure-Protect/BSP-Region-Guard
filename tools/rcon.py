#!/usr/bin/env python3
"""Sends console commands to the local Mohist test server over RCON and prints the replies.

  python3 tools/rcon.py "rg ranks" "lp listgroups"

Reads the port and password from server/server.properties (rcon.port, rcon.password). Only for the local test server.
"""
import socket
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def settings():
    port, password = 25575, ""
    for line in (ROOT / "server" / "server.properties").read_text().splitlines():
        if line.startswith("rcon.port="):
            port = int(line.split("=", 1)[1])
        elif line.startswith("rcon.password="):
            password = line.split("=", 1)[1]
    return port, password


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        raise ConnectionError("no reply")
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", "replace")


def main(commands):
    port, password = settings()
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        sock.sendall(packet(1, 3, password))
        req_id, _, _ = read(sock)
        if req_id == -1:
            sys.exit("RCON: wrong password")
        for i, command in enumerate(commands, start=2):
            sock.sendall(packet(i, 2, command))
            _, _, body = read(sock)
            print(f"> {command}\n{body.strip() or '(no reply)'}")


if __name__ == "__main__":
    main(sys.argv[1:] or ["list"])
