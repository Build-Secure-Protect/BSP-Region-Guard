#!/bin/sh
# Prepares the local Mohist test server folder (server/, gitignored) with the current mod jars.
# Usage: tools/setup_test_server.sh            (after ./gradlew build)
# Then:  put the Mohist 1.20.1 server jar in server/ as mohist.jar, accept the EULA in server/eula.txt,
#        drop LuckPerms-Bukkit-*.jar into server/plugins/, and run server/start.sh.
set -e
cd "$(dirname "$0")/.."
mkdir -p server/mods server/plugins
rm -f server/mods/bsp-region-guard-*.jar server/mods/bsp-core-*.jar
cp build/libs/bsp-region-guard-*.jar server/mods/
# the slim jar: Mohist ships its own MySQL driver and the Java module system refuses a second copy from the full jar
core_version=$(sed -n 's/^bsp_core_version=//p' gradle.properties)
cp "../BSP-Core/build/libs/bsp-core-${core_version}-slim.jar" server/mods/
ls -la server/mods
