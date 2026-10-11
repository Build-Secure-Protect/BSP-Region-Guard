#!/usr/bin/env python3
"""Writes every generated asset of BSP Region Guard: the language file, item models and item textures. Edit this
script, never the output. Textures are 16 x 16 pixel maps drawn with a palette; the PNG encoder is pure Python so no
package needs installing.

  python3 tools/gen_assets.py
"""
import json
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_region_guard"

LANG = {
    "item.bsp_region_guard.region_wand": "Region Wand",
    "bsp_region_guard.name": "BSP Region Guard",
}

# ---------------------------------------------------------------- textures

PALETTE = {
    ".": (0, 0, 0, 0),
    "w": (92, 66, 40, 255),      # wood, dark
    "W": (130, 96, 58, 255),     # wood, light
    "s": (42, 47, 58, 255),      # steel band
    "S": (154, 163, 181, 255),   # steel band, lit
    "t": (25, 211, 176, 255),    # turquoise crystal
    "T": (168, 244, 228, 255),   # crystal highlight
    "g": (255, 210, 58, 255),    # gold tip
}

REGION_WAND = [
    "..............T.",
    ".............Tt.",
    "............Ttt.",
    "...........tttg.",
    "..........Sstt..",
    ".........sSs....",
    "........WwS.....",
    ".......Www......",
    "......Www.......",
    ".....Www........",
    "....Www.........",
    "...Www..........",
    "..Www...........",
    ".Www............",
    "ww..............",
    "w...............",
]


def png(rows):
    width, height = len(rows[0]), len(rows)
    raw = b""
    for row in rows:
        raw += b"\x00" + b"".join(bytes(PALETTE[c]) for c in row)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
    print(path.relative_to(ASSETS.parent.parent.parent.parent))


def main():
    write(ASSETS / "lang/en_us.json", json.dumps(LANG, indent=2, ensure_ascii=False) + "\n")
    write(ASSETS / "models/item/region_wand.json", json.dumps({
        "parent": "item/handheld",
        "textures": {"layer0": "bsp_region_guard:item/region_wand"},
    }, indent=2) + "\n")
    write(ASSETS / "textures/item/region_wand.png", png(REGION_WAND))


if __name__ == "__main__":
    main()
