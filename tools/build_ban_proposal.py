#!/usr/bin/env python3
"""Build a proposed banned list for one region scope from the modpack catalogue.

Reads catalogue.csv (from scan_modpack_blocks.py) and a rules module, matches
every id against the rules, and writes:
  proposal_<scope>.csv   one row per id: tag, category, id, name, mod, reason
  proposal_<scope>.md    the same, grouped by category and reason, for reading

Each rule: (tag, category, reason, [patterns]). A pattern is a full-match regex
on the id ("mekanism:digital_miner", "ballistix:.*"), or "#ns:tag" to put an
existing tag straight into the list. Ids in EXTRA are not in the scan (made at
load) and are written as optional.

Usage:
  python3 -I tools/build_ban_proposal.py --catalogue <catalogue.csv> \
      --rules tools/ban_rules_sanctuary.py --out <dir>
"""
import argparse
import csv
import importlib.util
import re
import sys
from collections import OrderedDict
from pathlib import Path

sys.dont_write_bytecode = True  # loading the rules file must not leave tools/__pycache__ behind


def load_rules(path):
    spec = importlib.util.spec_from_file_location("ban_rules", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalogue", required=True, type=Path)
    ap.add_argument("--rules", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()

    rules = load_rules(a.rules)
    rows = list(csv.DictReader(open(a.catalogue, encoding="utf-8")))
    by_id = {r["id"]: r for r in rows}
    exclude = [re.compile(p) for p in getattr(rules, "EXCLUDE", [])]

    picked = OrderedDict()     # id -> (tag, category, reason)
    tag_refs = OrderedDict()   # "#tag" -> (tag, category, reason)
    unmatched = []
    for tag, category, reason, patterns in rules.RULES:
        for pat in patterns:
            if pat.startswith("#"):
                tag_refs.setdefault(pat, (tag, category, reason))
                continue
            rx = re.compile(pat)
            hits = [i for i in by_id if rx.fullmatch(i) and not any(e.fullmatch(i) for e in exclude)]
            if not hits:
                unmatched.append(f"{category}: {pat}")
            for i in hits:
                picked.setdefault(i, (tag, category, reason))
    # Drop model-only variants (tracker_07, syringe3, *_thrown, *_connected): they have
    # no display name and their base id is also in the scan.
    variant = re.compile(r"(_?\d+|_(connected|disconnected|active|on|open|closed|filled|charged|thrown|pulling_\d))$")
    for i in list(picked):
        r = by_id.get(i)
        base = variant.sub("", i)
        if r and not r["name"] and base != i and base in by_id:
            del picked[i]
    for i, (tag, category, reason) in getattr(rules, "EXTRA", {}).items():
        picked.setdefault(i, (tag, category, reason))

    scope = rules.SCOPE
    a.out.mkdir(parents=True, exist_ok=True)
    out_rows = []
    for ref, (tag, category, reason) in tag_refs.items():
        out_rows.append({"tag": tag, "category": category, "id": ref, "name": "(existing tag)",
                         "mod": ref[1:].split(":")[0], "reason": reason, "in_scan": "tag"})
    for i, (tag, category, reason) in picked.items():
        r = by_id.get(i)
        out_rows.append({"tag": tag, "category": category, "id": i,
                         "name": r["name"] if r else "", "mod": r["mod_name"] if r else i.split(":")[0],
                         "reason": reason, "in_scan": "yes" if r else "no (made at load)"})

    with open(a.out / f"proposal_{scope}.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out_rows[0].keys()))
        w.writeheader()
        w.writerows(out_rows)

    # Markdown: tag > category > reason > ids
    groups = OrderedDict()
    for r in out_rows:
        groups.setdefault(r["tag"], OrderedDict()).setdefault(r["category"], OrderedDict()) \
              .setdefault(r["reason"], []).append(r)
    with open(a.out / f"proposal_{scope}.md", "w", encoding="utf-8") as f:
        f.write(f"# Proposed banned list: {scope}\n\n")
        for tag, cats in groups.items():
            n = sum(len(v) for c in cats.values() for v in c.values())
            f.write(f"## `#bsp:{tag}` ({n} entries)\n\n")
            for cat, reasons in cats.items():
                f.write(f"### {cat} ({sum(len(v) for v in reasons.values())})\n\n")
                for reason, items in reasons.items():
                    ids = ", ".join(f"`{x['id']}`" + ("*" if x["in_scan"].startswith("no") else "")
                                    for x in items)
                    f.write(f"- **{reason}** ({len(items)}): {ids}\n")
                f.write("\n")
        f.write("`*` not in the scan (made at load); written as optional.\n")
        if unmatched:
            f.write("\n## Rules that matched nothing (check)\n\n" + "\n".join(f"- {u}" for u in unmatched) + "\n")

    counts = OrderedDict()
    for r in out_rows:
        counts[(r["tag"], r["category"])] = counts.get((r["tag"], r["category"]), 0) + 1
    for (tag, cat), n in counts.items():
        print(f"{tag:24} {cat:32} {n}")
    print(f"total {len(out_rows)}; rules with no match: {len(unmatched)}")
    for u in unmatched:
        print("  no match:", u)


if __name__ == "__main__":
    sys.exit(main())
