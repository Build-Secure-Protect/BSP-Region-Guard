#!/usr/bin/env python3
"""Write a Region Guard banned-list datapack from a proposal CSV.

Input:  proposal_<scope>.csv (from build_ban_proposal.py) and catalogue.csv.
Output: <pack>/pack.mcmeta
        <pack>/data/bsp/tags/items/<tag>.json   every entry
        <pack>/data/bsp/tags/blocks/<tag>.json  only blocks that have no item
                                                (Region Guard checks blocks
                                                through their item's tags)
Vanilla ids found in the scan are written plain; everything else, existing tag
references included, is {"id": ..., "required": false}, so a removed mod or a
wrong id is skipped instead of breaking the whole tag.

Then it checks: each file parses, ids are lower case, no duplicates, no tag
refers to itself, and every id is in the scan or marked optional.

Usage:
  python3 -I tools/write_ban_datapack.py --proposal <proposal.csv> \
      --catalogue <catalogue.csv> --pack <dir>
"""
import argparse
import csv
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path

PACK_FORMAT = 15  # Minecraft 1.20.1


def entry(rid, plain):
    return rid if plain else {"id": rid, "required": False}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proposal", required=True, type=Path)
    ap.add_argument("--catalogue", required=True, type=Path)
    ap.add_argument("--pack", required=True, type=Path)
    ap.add_argument("--description", default="BSP banned lists")
    a = ap.parse_args()

    cat = {r["id"]: r for r in csv.DictReader(open(a.catalogue, encoding="utf-8"))}
    rows = list(csv.DictReader(open(a.proposal, encoding="utf-8")))

    by_tag = OrderedDict()
    for r in rows:
        by_tag.setdefault(r["tag"], []).append(r["id"])

    a.pack.mkdir(parents=True, exist_ok=True)
    (a.pack / "pack.mcmeta").write_text(json.dumps(
        {"pack": {"pack_format": PACK_FORMAT, "description": a.description}}, indent=2) + "\n")

    problems, written = [], []
    for tag, ids in by_tag.items():
        if not re.fullmatch(r"[a-z0-9_./-]+", tag):
            problems.append(f"tag name not lower case: {tag}")
        seen, items, blocks = set(), [], []
        for rid in ids:
            if rid in seen:
                problems.append(f"{tag}: duplicate {rid}")
                continue
            seen.add(rid)
            if rid != rid.lower():
                problems.append(f"{tag}: id not lower case {rid}")
            if rid.lstrip("#") == f"bsp:{tag}":
                problems.append(f"{tag}: refers to itself")
                continue
            if rid.startswith("#"):
                items.append(entry(rid, rid in ("#minecraft:swords",)))
                continue
            row = cat.get(rid)
            plain = bool(row) and rid.startswith("minecraft:")
            items.append(entry(rid, plain))
            if row and row["type"] == "block":
                blocks.append(entry(rid, plain))

        for kind, values in (("items", items), ("blocks", blocks)):
            path = a.pack / "data" / "bsp" / "tags" / kind / f"{tag}.json"
            if not values:
                if path.exists():
                    path.unlink()
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"replace": False, "values": values}, indent=2) + "\n")
            written.append((path, len(values)))

    # Checks
    for path, _ in written + [(a.pack / "pack.mcmeta", 0)]:
        try:
            json.loads(path.read_text())
        except ValueError as e:
            problems.append(f"{path}: does not parse: {e}")
    for path, n in written:
        body = json.loads(path.read_text())
        plain = [v for v in body["values"] if isinstance(v, str)]
        for v in plain:
            if not v.startswith("#") and v not in cat:
                problems.append(f"{path.name}: plain id not in scan: {v}")
        print(f"{path.relative_to(a.pack)}: {n} entries ({len(plain)} plain, {n - len(plain)} optional)")
    not_in_scan = [r["id"] for r in rows if not r["id"].startswith("#") and r["id"] not in cat]
    if not_in_scan:
        print("optional, not in the scan (made at load):", ", ".join(not_in_scan))
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  " + p)
        return 1
    print("checks passed: files parse, lower case, no duplicates, no self reference, plain ids all in scan")
    return 0


if __name__ == "__main__":
    sys.exit(main())
