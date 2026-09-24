#!/usr/bin/env bash
# Compiles the shared harness plus every contract package and runs each package's Run main.
# Exit code is the sum of all package exit codes. Usage: ./run-contracts.sh [slug ...]
set -u
cd "$(dirname "$0")"
JDK="${MYCELIUM_JDK:-${JAVA_HOME:-}}"
if [ -n "$JDK" ] && [ -x "$JDK/bin/javac" ]; then JAVAC="$JDK/bin/javac"; JAVA="$JDK/bin/java";
elif [ -n "$JDK" ] && [ -x "$JDK/bin/javac.exe" ]; then JAVAC="$JDK/bin/javac.exe"; JAVA="$JDK/bin/java.exe";
else JAVAC=javac; JAVA=java; fi

slugs=("$@")
if [ ${#slugs[@]} -eq 0 ]; then
  for d in contracts/*/; do [ -d "$d/src" ] && slugs+=("$(basename "$d")"); done
fi

total=0
for slug in "${slugs[@]}"; do
  pkg="contracts/$slug"
  out="$pkg/out"
  rm -rf "$out"; mkdir -p "$out"
  files=$(find harness/src "$pkg/src" -name '*.java')
  if ! "$JAVAC" --release 17 -d "$out" $files; then
    echo "[$slug] COMPILE FAILED"; total=$((total+1)); continue
  fi
  main=$(grep -rl 'public static void main' "$pkg/src" | head -1 | sed -e "s#$pkg/src/##" -e 's#/#.#g' -e 's#\.java$##')
  "$JAVA" -cp "$out" "$main"; code=$?
  echo "[$slug] exit=$code"; echo
  total=$((total+code))
done
echo "TOTAL exit=$total"
exit $total
