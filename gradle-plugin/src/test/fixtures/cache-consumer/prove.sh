#!/usr/bin/env bash
# Proves the configuration cache does not freeze the Mycelium checkout's content (red-team review, 2026-10).
# Usage: bash gradle-plugin/src/test/fixtures/cache-consumer/prove.sh      (from anywhere; one Gradle build at a time)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../../.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/contracts"
cp -r "$repo/bridges" "$repo/eras" "$scratch/"
cp "$repo/contracts/registry.json" "$scratch/contracts/"
bridge="$scratch/bridges/item-carrier/custom-data/src/mycelium/bridges/item_carrier/ItemCarrier.java"
imported="$here/build/generated/sources/mycelium/bridges/java/fixture/cache/mycelium/bridges/item_carrier/ItemCarrier.java"
gradle() { "$repo/gradlew" -p "$here" myceliumBridgeImport --console=plain -PmyceliumScratch="$scratch" "$@"; }

rm -rf "$here/build" "$here/.gradle/configuration-cache"
first=$(gradle 2>&1) || { echo "$first"; exit 1; }
echo "$first" | grep -q "Calculating task graph\|Configuration cache entry stored" || { echo "$first"; echo "FAIL: the first build did not store a configuration cache entry"; exit 1; }
grep -q "edited-bridge-marker" "$imported" && { echo "FAIL: the marker was imported before it was written"; exit 1; }

printf '\n// edited-bridge-marker\n' >> "$bridge"
second=$(gradle 2>&1) || { echo "$second"; exit 1; }
echo "$second" | grep -q "Reusing configuration cache" || { echo "$second"; echo "FAIL: the second build did not reuse the configuration cache (something is read from the checkout while the build is configured, so Gradle discarded the entry: correct, but slow)"; exit 1; }
echo "$second" | grep -q "myceliumBridgeImport UP-TO-DATE" && { echo "$second"; echo "FAIL: myceliumBridgeImport was UP-TO-DATE on an edited bridge"; exit 1; }
grep -q "edited-bridge-marker" "$imported" || { echo "FAIL: the edited bridge was not imported"; exit 1; }

third=$(gradle 2>&1) || { echo "$third"; exit 1; }
echo "$third" | grep -q "myceliumBridgeImport UP-TO-DATE" || { echo "$third"; echo "FAIL: an unchanged bridge was imported again"; exit 1; }
echo "PASS: with the configuration cache on, an edited bridge is imported again, and an unchanged one is not"
