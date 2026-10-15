#!/bin/sh
# Prepares the local Mohist test server folder (server/, gitignored) with the current mod jars.
# Usage: tools/setup_test_server.sh            (after ./gradlew build)
#        CORE_JAR=full tools/setup_test_server.sh  (BSP Core's full jar, which ships its MySQL driver relocated since RG-C4)
# Then:  put the Mohist 1.20.1 server jar in server/ as mohist.jar, accept the EULA in server/eula.txt,
#        drop LuckPerms-Bukkit-*.jar into server/plugins/, and run server/start.sh.
set -e
cd "$(dirname "$0")/.."
mkdir -p server/mods server/plugins
rm -f server/mods/bsp-region-guard-*.jar server/mods/bsp-core-*.jar
cp build/libs/bsp-region-guard-*.jar server/mods/
# the slim jar by default; the full jar used to clash with Mohist's own MySQL driver (RG-C4, fixed in BSP Core 2026-10-15)
core_version=$(sed -n 's/^bsp_core_version=//p' gradle.properties)
if [ "${CORE_JAR:-slim}" = "full" ]; then
  cp "../BSP-Core/build/libs/bsp-core-${core_version}.jar" server/mods/
else
  cp "../BSP-Core/build/libs/bsp-core-${core_version}-slim.jar" server/mods/
fi
ls -la server/mods
