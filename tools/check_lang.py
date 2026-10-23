#!/usr/bin/env python3
"""Checks the screen text: every key the client code looks up exists in the language file.

Claude cannot open the game, so a missing key (which Minecraft would show as the raw key) is caught here instead.
Run after editing a screen or tools/gen_assets.py (which writes the language file):

  python3 tools/gen_assets.py && python3 tools/check_lang.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = ROOT / "src/main/java/com/mrgregles/bsp_region_guard/client"
LANG = ROOT / "src/main/resources/assets/bsp_region_guard/lang/en_us.json"
P = "gui.bsp_region_guard."


def array(src, name):
    m = re.search(name + r"\s*=\s*\{([^}]*)\}", src)
    return [s.lower() for s in re.findall(r'"([^"]+)"', m.group(1))] if m else []


def main():
    lang = json.loads(LANG.read_text())
    src = {p.name: p.read_text() for p in CLIENT.glob("*.java")}
    every = "\n".join(src.values())
    region = src.get("RegionScreen.java", "")
    needed = set()
    needed |= set(re.findall(r'Tr\.t\("([a-z0-9_.]+)"\s*[,)]', every))
    for k in re.findall(r'Tr\.n\("([a-z0-9_.]+)"', every):
        needed |= {k + ".one", k + ".many"}
    needed |= set(re.findall(r'(?:SENTENCES|MESSAGES)\.put\("[^"]+", "([a-z0-9_]+)"\)', every))
    # keys built from a family name and a value the code knows
    sides = ["north", "east", "south", "west"]
    families = {
        "tab.": array(region, "TABS"),
        "filter.": array(region, "FILTERS"),
        "state.": ["inh", "allow", "deny"],
        "side.": sides,
        "edge.": sides,
        "cause.": ["enter", "exit", "timer", "shutdown"],
        "type.": ["protected", "plot_for_sale", "open_for_buying", "claim", "mine", "global", "remote"],
        "rule.": ["place", "use", "break", "carry"],
        "mode.": ["region", "plot", "mine"],
        "tool.": ["pick", "draw", "reshape"],
        "pm_new.": ["region", "plot", "mine"],
        "coin_tier.": ["copper", "gold", "diamond", "netherite", "illyrium"],
        "plots_rule.": ["not_pvp", "pvp_denied", "world_allows", "config_allows", "world_refuses", "config_refuses"],
    }
    used_families = set(re.findall(r'Tr\.[tn]\("([a-z0-9_]+\.)"\s*\+', every))
    for fam in used_families:
        if fam not in families:
            print(f"unknown key family {fam!r}: add its values to check_lang.py")
            return 1
        needed |= {fam + v for v in families[fam]}
    missing = sorted(k for k in needed if P + k not in lang)
    unused = sorted(k[len(P):] for k in lang if k.startswith(P) and k[len(P):] not in needed
                    and not any(k[len(P):].startswith(f) for f in used_families))
    for k in missing:
        print("MISSING", P + k)
    # Minecraft fills values in with String.format: a call with values needs %s, and a lone % breaks the text
    bad = 0
    for k in sorted(set(re.findall(r'Tr\.t\("([a-z0-9_.]+)",', every))):
        if P + k in lang and "%s" not in lang[P + k]:
            print("NO %s  ", P + k, "is given values but has no %s")
            bad += 1
    for k, v in lang.items():
        if k.startswith(P) and re.search(r"%(?!s)", v):
            print("BAD %   ", k, repr(v), "(write %% for a percent sign)")
            bad += 1
    for k in unused:
        print("unused ", P + k)
    print(f"{len(needed)} keys used, {len(missing)} missing, {len(unused)} unused")
    return 1 if missing or bad else 0


if __name__ == "__main__":
    sys.exit(main())
