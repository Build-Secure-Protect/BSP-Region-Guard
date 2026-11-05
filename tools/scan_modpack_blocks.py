#!/usr/bin/env python3
"""Scan a modpack's jars and write a catalogue of every block and item.

Reads each jar as a zip (no game needed):
  assets/<ns>/blockstates/*.json   -> block ids
  assets/<ns>/models/item/*.json   -> item ids (block items included)
  assets/<ns>/lang/en_us.json      -> display names, plus ids with no model file
  data/<ns>/tags/{blocks,items}/   -> existing tags
  META-INF/mods.toml               -> mod id and name
Jar-in-jar (META-INF/jarjar/*.jar) is read one level deep.

Writes into --out:
  catalogue.csv   one row per id: mod, id, type, name, kinds, tags
  tags.csv        every block/item tag, merged across jars
  summary.md      counts by mod and by kind, plus the runtime gaps

Kinds are guessed from the id, the name and the tags. They are a starting
point for picking bans, not a rule.

Usage:
  python3 -I tools/scan_modpack_blocks.py --mods <mods dir> --out <dir>
      [--extra-jar <vanilla client jar>] [--extra-jar <forge universal jar>]
      [--scripts <instance scripts dir>]
"""
import argparse
import csv
import io
import json
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

# ---------------------------------------------------------------- kinds
# Each kind: id/name keywords (matched on word parts), and tags that imply it.
KINDS = {
    "explosive": {
        "words": ["tnt", "explosive", "explosives", "bomb", "bombs", "dynamite", "nuke", "nuclear_bomb",
                  "c4", "grenade", "landmine", "detonator", "re:_charge$", "powder_charge",
                  "shell", "missile", "rocket", "warhead", "antimatter", "incendiary", "firework"],
        "tags": [],
    },
    "fire": {
        "words": ["fire", "flint_and_steel", "fire_charge", "lighter", "igniter", "flamethrower",
                  "campfire", "lava", "magma", "blaze_burner", "napalm"],
        "tags": ["minecraft:fire", "minecraft:campfires"],
    },
    "liquid": {
        "words": ["bucket", "fluid", "liquid", "flowing", "molten", "pump", "spout", "sprinkler",
                  "fluid_tank", "tank", "drain", "faucet", "hose", "water", "oil", "fuel", "acid"],
        "tags": ["forge:buckets", "minecraft:fluid"],
    },
    "chunk_loader": {
        "words": ["chunk_loader", "chunkloader", "chunk_anchor", "world_anchor", "anchor_chunk",
                  "spot_loader", "dimensional_anchor", "personal_anchor", "chunkloading",
                  "upgrade_anchor", "spatial_anchor"],
        "tags": [],
    },
    "teleporter": {
        "words": ["teleport", "teleporter", "teleportation", "warp", "waystone", "waystones", "portal",
                  "re:(^|_)ender_pearl$", "wormhole", "travel_anchor", "travel_staff", "telepad",
                  "matter_transmitter", "matter_receiver", "rift", "pocket_dimension", "item_tp",
                  "quantum_portal", "dimension_builder"],
        "tags": [],
    },
    "storage": {
        "words": ["chest", "barrel", "crate", "drawer", "drawers", "backpack", "shulker", "storage",
                  "vault", "safe", "cabinet", "shelf", "locker", "dank", "satchel", "pouch", "bag",
                  "tank", "cell", "drive", "disk"],
        "tags": ["forge:chests", "c:chests", "forge:barrels", "minecraft:shulker_boxes",
                 "functionalstorage:drawer"],
    },
    "machine": {
        "words": ["machine", "generator", "crusher", "grinder", "press", "smelter", "furnace",
                  "pulverizer", "quarry", "miner", "drill", "assembler", "fabricator", "factory",
                  "reactor", "turbine", "engine", "motor", "dynamo", "centrifuge", "electrolyzer",
                  "compressor", "enricher", "infuser", "crafter", "mixer", "saw", "harvester",
                  "planter", "spawner", "duplicator", "replicator", "laser", "boiler", "refinery",
                  "controller", "interface", "processor", "charger", "battery", "solar", "panel",
                  "capacitor", "cable", "pipe", "conduit", "conveyor", "belt", "deployer", "crank",
                  "shaft", "gearbox", "cogwheel", "fan", "breaker", "placer", "builder", "excavator"],
        "tags": [],
    },
    "flying": {
        "words": ["jetpack", "elytra", "wings", "glider", "flight", "angel_ring", "aircraft", "airship",
                  "biplane", "gyrodyne", "hover", "hover_pack", "levitation"],
        "tags": ["curios:wings"],
    },
    "redstone": {
        "words": ["piston", "observer", "dispenser", "dropper", "hopper", "redstone", "comparator",
                  "repeater", "lever", "button", "pressure_plate", "tripwire", "daylight_detector",
                  "target", "sculk_sensor", "note_block", "rail", "minecart"],
        "tags": ["minecraft:buttons", "minecraft:pressure_plates", "minecraft:rails"],
    },
    "weapon": {
        "words": ["sword", "bow", "crossbow", "gun", "rifle", "pistol", "shotgun", "cannon", "turret",
                  "trident", "axe", "mace", "spear", "dagger", "blaster", "railgun", "flail",
                  "katana", "scythe", "hammer", "ammo", "bullet", "arrow", "sentry"],
        "tags": ["minecraft:swords", "forge:tools/swords", "forge:tools/bows"],
    },
    "mob": {
        "words": ["spawn_egg", "spawner", "mob", "soul", "trap", "grinder", "crusher", "masher",
                  "lure", "capture", "net", "cage", "mob_imprisonment", "safari"],
        "tags": [],
    },
    "terrain_tool": {
        "words": ["wand", "gadget", "builder", "destruction", "exchanger", "excavator", "hammer",
                  "terraform", "mining_gadget", "paxel", "ore_finder", "sickle", "world_edit"],
        "tags": [],
    },
    "light": {
        "words": ["lamp", "lantern", "torch", "light", "glowstone", "beacon"],
        "tags": [],
    },
    "ore_resource": {
        "words": ["ore", "raw", "ingot", "nugget", "dust", "gem", "crystal", "shard", "plate", "gear",
                  "rod", "wire"],
        "tags": ["forge:ores", "forge:ingots", "forge:nuggets", "forge:dusts", "forge:gems",
                 "forge:raw_materials", "forge:storage_blocks"],
    },
    "plant_farm": {
        "words": ["sapling", "seeds", "seed", "crop", "crops", "leaves", "log", "flower", "mushroom",
                  "pot", "farmland", "fertilizer", "bonemeal", "cloche", "vine"],
        "tags": ["minecraft:saplings", "minecraft:crops", "minecraft:flowers", "minecraft:leaves",
                 "minecraft:logs"],
    },
    "bed_respawn": {
        "words": ["bed", "respawn_anchor", "sleeping_bag", "grave"],
        "tags": ["minecraft:beds"],
    },
}
# Parts of ids and names removed before matching, so "light_gray" is not a light
# and "fire_coral" is not fire.
NOISE = ["light_gray", "light_blue", "lightgray", "lightblue", "light_weighted", "lightning_rod",
         "fire_coral", "music_disc", "firecontrol", "fire_control", "lava_immunity", "_fire_immunity",
         "dead_bush"]
# Words that look like a keyword but aren't (checked on the id path and name).
FALSE_HITS = {
    "storage": ["storage_block", "block_of_"],
    "machine": ["machine_casing", "casing"],
    "liquid": ["watering_can"],
    "explosive": ["nautilus", "shulker", "crab_shell", "turtle", "mould_shell", "spawner_core",
                  "charge_distribution", "nuclear_bombs_book", "logo"],
    "teleporter": ["powdered_ender_pearl", "ender_pearl_dust"],
}

TOML_MODID = re.compile(r'^\s*modId\s*=\s*"([^"]+)"', re.M)
TOML_NAME = re.compile(r'^\s*displayName\s*=\s*"([^"]+)"', re.M)

RUNTIME_GAP_MODS = {
    "jaopca": "JAOPCA makes ores, dusts, ingots and blocks for every material it finds while the game loads.",
    "alltheores": "All the Ores registers some ores and materials from config while the game loads.",
    "almostunified": "Almost Unified merges duplicate items (ingots, dusts) at load; some ids here are hidden or swapped in game.",
    "crafttweaker": "CraftTweaker scripts can add items and change tags at load.",
    "chiselsandbits": "Chisels & Bits makes chiseled block variants at run time; only its base blocks are listed.",
    "framedblocks": "FramedBlocks copies the look of other blocks at run time; only its frame blocks are listed.",
    "kubejs": "KubeJS scripts can add blocks and items at load.",
}


def words_of(text):
    return set(re.split(r"[^a-z0-9]+", text.lower())) - {""}


def guess_kinds(rid, name, tags):
    path = rid.split(":", 1)[1]
    lname = (name or "").lower().replace(" ", "_")
    clean_path, clean_name = path, lname
    for n in NOISE:
        clean_path = clean_path.replace(n, " ")
        clean_name = clean_name.replace(n, " ")
    hay = clean_path + " " + clean_name
    parts = words_of(clean_path) | words_of(clean_name)
    found = []
    for kind, rule in KINDS.items():
        hit = False
        for w in rule["words"]:
            if w.startswith("re:"):
                if re.search(w[3:], clean_path):
                    hit = True
                    break
            elif "_" in w:
                if w in hay:
                    hit = True
                    break
            elif w in parts:
                hit = True
                break
        if not hit and rule["tags"]:
            for t in tags:
                for want in rule["tags"]:
                    if t == want or t.startswith(want + "/"):
                        hit = True
        if hit and any(f in path or f in lname for f in FALSE_HITS.get(kind, [])):
            hit = False
        if hit:
            found.append(kind)
    return found


# ---------------------------------------------------------------- jar reading
class Pack:
    def __init__(self):
        self.mods = {}                          # jar name -> {"ids": [...], "names": {...}}
        self.ns_owner = {}                      # namespace -> (mod id, mod name, jar)
        self.blocks = defaultdict(set)          # id -> set of jar names
        self.items = defaultdict(set)
        self.names = {}                         # id -> display name
        self.tags = {"blocks": defaultdict(lambda: {"values": [], "replace": False, "jars": set()}),
                     "items": defaultdict(lambda: {"values": [], "replace": False, "jars": set()})}
        self.problems = []
        self.content_jars = set()                # top-level jars that gave at least one id

    def read_jar(self, data, jar_name, depth=0):
        try:
            zf = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            self.problems.append(f"{jar_name}: not a zip")
            return
        names = zf.namelist()
        mod_ids, mod_names = [], []
        if "META-INF/mods.toml" in names:
            toml = zf.read("META-INF/mods.toml").decode("utf-8", "replace")
            # Only [[mods]] sections; [[dependencies...]] also use modId.
            section = ""
            for line in toml.splitlines():
                st = line.split("#", 1)[0].strip()
                if st.startswith("["):
                    section = st.replace(" ", "")
                    if section == "[[mods]]":
                        mod_ids.append("")
                        mod_names.append("")
                    continue
                if section != "[[mods]]":
                    continue
                m = TOML_MODID.match(line)
                if m:
                    mod_ids[-1] = m.group(1)
                m = TOML_NAME.match(line)
                if m:
                    mod_names[-1] = m.group(1)
            mod_names = [n or i for i, n in zip(mod_ids, mod_names)]
        elif "fabric.mod.json" in names:
            try:
                fj = json.loads(zf.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
                mod_ids = [fj.get("id", "")]
                mod_names = [fj.get("name", "")]
            except ValueError:
                pass
        self.mods[jar_name] = {"ids": mod_ids, "names": mod_names}

        lang_by_ns = {}
        nested_items = []
        for n in names:
            parts = n.split("/")
            if len(parts) < 3 or not n.endswith(".json"):
                continue
            top, ns = parts[0], parts[1]
            if top == "assets":
                # Ids can contain "/" (Fantasy Furniture: bone/skeleton/bed_double).
                if len(parts) >= 4 and parts[2] == "blockstates":
                    self.blocks[f"{ns}:{'/'.join(parts[3:])[:-5]}"].add(jar_name)
                    self.claim_ns(ns, mod_ids, mod_names, jar_name)
                elif len(parts) == 5 and parts[2] == "models" and parts[3] == "item":
                    self.items[f"{ns}:{parts[4][:-5]}"].add(jar_name)
                    self.claim_ns(ns, mod_ids, mod_names, jar_name)
                elif len(parts) > 5 and parts[2] == "models" and parts[3] == "item":
                    # Item models in subfolders are mostly render parts (Tinkers' tool
                    # parts, Create held models). Keep only those that match a block.
                    nested_items.append(f"{ns}:{'/'.join(parts[4:])[:-5]}")
                elif len(parts) == 4 and parts[2] == "lang" and parts[3] == "en_us.json":
                    lang_by_ns[ns] = n
            elif top == "data" and len(parts) >= 5 and parts[2] == "tags" and parts[3] in ("blocks", "items"):
                kind = parts[3]
                tag_id = f"{ns}:{'/'.join(parts[4:])[:-5]}"
                try:
                    body = json.loads(zf.read(n).decode("utf-8-sig", "replace"), strict=False)
                except ValueError as e:
                    self.problems.append(f"{jar_name}: bad tag json {n}: {e}")
                    continue
                if not isinstance(body, dict):
                    continue
                t = self.tags[kind][tag_id]
                if body.get("replace"):
                    t["values"] = []
                    t["replace"] = True
                for v in body.get("values", []):
                    if isinstance(v, dict):
                        v = v.get("id", "")
                    if isinstance(v, str) and v and v not in t["values"]:
                        t["values"].append(v)
                t["jars"].add(jar_name)

        for rid in nested_items:
            if rid in self.blocks:
                self.items[rid].add(jar_name)

        for ns, path in lang_by_ns.items():
            try:
                lang = json.loads(zf.read(path).decode("utf-8-sig", "replace"), strict=False)
            except ValueError as e:
                self.problems.append(f"{jar_name}: bad lang json {path}: {e}")
                continue
            if not isinstance(lang, dict):
                continue
            for key, val in lang.items():
                kp = key.split(".")
                if len(kp) != 3 or kp[1] != ns or kp[0] not in ("block", "item"):
                    continue
                rid = f"{ns}:{kp[2]}"
                if not re.fullmatch(r"[a-z0-9_./-]+", kp[2]):
                    continue
                if isinstance(val, str):
                    # block name wins over item name only if no name yet
                    if kp[0] == "block" or rid not in self.names:
                        self.names[rid] = val
                # Ids that only appear in lang (no model / blockstate): keep, marked later.
                target = self.blocks if kp[0] == "block" else self.items
                if rid not in self.blocks and rid not in self.items:
                    target[rid].add(jar_name + " (lang only)")
                    self.claim_ns(ns, mod_ids, mod_names, jar_name)

        if depth == 0:
            for n in names:
                if n.startswith("META-INF/jarjar/") and n.endswith(".jar"):
                    self.read_jar(zf.read(n), f"{jar_name} > {n.rsplit('/', 1)[1]}", depth + 1)

    def claim_ns(self, ns, mod_ids, mod_names, jar_name):
        self.content_jars.add(jar_name.split(" > ")[0])
        if ns in self.ns_owner and self.ns_owner[ns][0] == ns:
            return
        if ns in mod_ids:
            i = mod_ids.index(ns)
            self.ns_owner[ns] = (ns, mod_names[i] if i < len(mod_names) else ns, jar_name)
        elif ns not in self.ns_owner:
            self.ns_owner[ns] = (mod_ids[0] if mod_ids else ns,
                                 mod_names[0] if mod_names else ns, jar_name)

    # ------------------------------------------------------------ tag membership
    def expand_tags(self):
        """For each block/item id, the tags it ends up in (following nested tags)."""
        member = {"blocks": defaultdict(set), "items": defaultdict(set)}
        for kind in ("blocks", "items"):
            tags = self.tags[kind]
            cache = {}

            def resolve(tag_id, seen):
                if tag_id in cache:
                    return cache[tag_id]
                if tag_id in seen or tag_id not in tags:
                    return set()
                seen = seen | {tag_id}
                out = set()
                for v in tags[tag_id]["values"]:
                    if v.startswith("#"):
                        out |= resolve(v[1:], seen)
                    else:
                        out.add(v)
                cache[tag_id] = out
                return out

            for tag_id in tags:
                for rid in resolve(tag_id, set()):
                    member[kind][rid].add(tag_id)
            self.tag_members = getattr(self, "tag_members", {})
            self.tag_members[kind] = {t: resolve(t, set()) for t in tags}
        return member


# ---------------------------------------------------------------- output
def cap(text, limit=30000):
    """Keep CSV cells under the spreadsheet cell limit (32767 characters)."""
    return text if len(text) <= limit else text[:limit] + ";...(cut)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mods", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--extra-jar", action="append", default=[], type=Path,
                    help="vanilla client jar, Forge universal jar, ...")
    ap.add_argument("--scripts", type=Path, help="instance scripts/ folder (CraftTweaker)")
    a = ap.parse_args()

    pack = Pack()
    jars = sorted(a.mods.glob("*.jar"))
    for p in a.extra_jar:
        pack.read_jar(p.read_bytes(), p.name)
    for p in jars:
        pack.read_jar(p.read_bytes(), p.name)

    member = pack.expand_tags()
    a.out.mkdir(parents=True, exist_ok=True)

    ids = sorted(set(pack.blocks) | set(pack.items))
    rows = []
    kind_count = defaultdict(int)
    mod_count = defaultdict(lambda: [0, 0, 0])   # blocks, items-only, total
    for rid in ids:
        ns = rid.split(":", 1)[0]
        owner = pack.ns_owner.get(ns, (ns, ns, ""))
        if ns == "minecraft":
            owner = ("minecraft", "Minecraft", owner[2])
        is_b, is_i = rid in pack.blocks, rid in pack.items
        typ = "block+item" if is_b and is_i else ("block" if is_b else "item")
        lang_only = all(j.endswith("(lang only)") for j in pack.blocks.get(rid, set()) | pack.items.get(rid, set()))
        tags = sorted(member["blocks"].get(rid, set()) | member["items"].get(rid, set()))
        kinds = guess_kinds(rid, pack.names.get(rid, ""), tags)
        for k in kinds:
            kind_count[k] += 1
        if not kinds:
            kind_count["other"] += 1
        mc = mod_count[(owner[0], owner[1])]
        mc[0 if is_b else 1] += 1
        mc[2] += 1
        rows.append({
            "mod_id": owner[0], "mod_name": owner[1], "namespace": ns, "id": rid, "type": typ,
            "name": pack.names.get(rid, ""), "kinds": ";".join(kinds) or "other",
            "source": ("JAOPCA template (real ids are made at load, check in JEI)" if ns == "jaopca"
                       else "lang only (no model file)" if lang_only else "files"),
            "tag_count": len(tags), "tags": ";".join(tags[:25]) + (";..." if len(tags) > 25 else ""),
            "jar": owner[2],
        })

    with open(a.out / "catalogue.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    with open(a.out / "tags.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["tag", "type", "direct_values", "resolved_ids", "replace", "values", "jars"])
        for kind in ("items", "blocks"):
            for tag_id in sorted(pack.tags[kind]):
                t = pack.tags[kind][tag_id]
                w.writerow(["#" + tag_id, kind, len(t["values"]), len(pack.tag_members[kind][tag_id]),
                            t["replace"], cap(";".join(t["values"])), cap(";".join(sorted(t["jars"])))])

    # runtime gaps
    present_mods = {i for m in pack.mods.values() for i in m["ids"]}
    gaps = [(m, why) for m, why in RUNTIME_GAP_MODS.items() if m in present_mods]
    scripts = sorted(a.scripts.glob("**/*.zs")) if a.scripts and a.scripts.exists() else []

    with open(a.out / "summary.md", "w", encoding="utf-8") as f:
        f.write(f"# Modpack block and item catalogue\n\n")
        f.write(f"- Mods folder: `{a.mods}` ({len(jars)} jars)\n")
        for p in a.extra_jar:
            f.write(f"- Also read: `{p.name}`\n")
        f.write(f"- Ids: {len(ids)} ({sum(1 for r in rows if r['type'] != 'item')} blocks, "
                f"{sum(1 for r in rows if r['type'] == 'item')} items with no block)\n")
        f.write(f"- Lang-only ids (no model or blockstate file, check in JEI): "
                f"{sum(1 for r in rows if r['source'] != 'files')}\n")
        f.write(f"- Tags: {len(pack.tags['items'])} item tags, {len(pack.tags['blocks'])} block tags\n\n")
        f.write("## By kind (guessed; one id can have several)\n\n| Kind | Ids |\n|---|---|\n")
        for k, n in sorted(kind_count.items(), key=lambda x: -x[1]):
            f.write(f"| {k} | {n} |\n")
        f.write("\n## By mod\n\n| Mod | Mod id | Blocks | Items only | Total |\n|---|---|---|---|---|\n")
        for (mid, mname), (b, i, t) in sorted(mod_count.items(), key=lambda x: -x[1][2]):
            f.write(f"| {mname} | `{mid}` | {b} | {i} | {t} |\n")
        f.write("\n## Made while the game runs (may exist, check in JEI)\n\n")
        for m, why in gaps:
            f.write(f"- **{m}**: {why}\n")
        for s in scripts:
            f.write(f"- CraftTweaker script `{s.name}`\n")
        no_content = [j for j in pack.mods if ">" not in j and j not in pack.content_jars]
        f.write(f"\n## Jars that add no blocks or items ({len(no_content)})\n\n")
        f.write(", ".join(sorted(no_content)) + "\n")
        if pack.problems:
            f.write("\n## Read problems\n\n" + "\n".join(f"- {p}" for p in pack.problems) + "\n")

    print(f"{len(jars)} jars, {len(ids)} ids, {len(pack.tags['items'])} item tags, "
          f"{len(pack.tags['blocks'])} block tags, {len(pack.problems)} read problems")
    print(f"wrote {a.out}/catalogue.csv, tags.csv, summary.md")


if __name__ == "__main__":
    sys.exit(main())
