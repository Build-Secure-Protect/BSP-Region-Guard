#!/usr/bin/env python3
"""Builds a LuckPerms Forge jar that runs in the development environment, for the GameTests that exercise the
LuckPerms bridge. Never shipped; libs/ is gitignored.

Why: the released LuckPerms-Forge jar is a loader with the real mod nested inside as luckperms-forge.jarinjar, and
that inner jar uses obfuscated (SRG) Minecraft names. Dev runs use Mojang names, so the inner jar must be remapped,
which ForgeGradle does for a dependency declared with fg.deobf. The steps:

The version is luckperms_dev_version in gradle.properties (V below); jars of other versions in libs/ are ignored.

  1. Put LuckPerms-Forge-V.jar in libs/ and extract its inner jar:
       unzip -p libs/LuckPerms-Forge-V.jar luckperms-forge.jarinjar > libs/LuckPerms-Forge-inner-V.jar
  2. ./gradlew dependencies --configuration runtimeClasspath   (build.gradle deobfs the inner jar while it exists)
  3. python3 tools/make_luckperms_dev_jar.py   -> libs/LuckPerms-Forge-dev-V.jar, and removes the inner jar
     so only the dev jar stays on the dev classpath.
"""
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION = next(line.split("=", 1)[1].strip() for line in (ROOT / "gradle.properties").read_text().splitlines()
               if line.startswith("luckperms_dev_version="))
LIBS = ROOT / "libs"
OUTER = LIBS / f"LuckPerms-Forge-{VERSION}.jar"
INNER = LIBS / f"LuckPerms-Forge-inner-{VERSION}.jar"
MAPPED = Path.home() / ".gradle/caches/forge_gradle/deobf_dependencies/blank/LuckPerms-Forge-inner" / f"{VERSION}_mapped_official_1.20.1" / f"LuckPerms-Forge-inner-{VERSION}_mapped_official_1.20.1.jar"
DEV = LIBS / f"LuckPerms-Forge-dev-{VERSION}.jar"


def main():
    for f in (OUTER, MAPPED):
        if not f.is_file():
            sys.exit(f"missing {f}")
    with zipfile.ZipFile(OUTER) as src, zipfile.ZipFile(DEV, "w", zipfile.ZIP_DEFLATED) as out:
        for item in src.infolist():
            if item.filename == "luckperms-forge.jarinjar":
                out.write(MAPPED, item.filename, compress_type=zipfile.ZIP_STORED)
            else:
                out.writestr(item, src.read(item.filename))
    if INNER.is_file():
        INNER.unlink()
    print(f"{DEV.relative_to(ROOT)} ({DEV.stat().st_size} bytes); inner jar removed")


if __name__ == "__main__":
    main()
