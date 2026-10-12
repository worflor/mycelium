#!/usr/bin/env bash
# Runs the harness's own checks, then compiles the shared harness plus each contract package and runs its
# harness/Run. Exit code is the sum of every failure. Usage: ./run-contracts.sh [slug ...]
# run-contracts.ps1 does exactly the same; myceliumVerify is the build gate (it also collects each harness's report).
set -u
cd "$(dirname "$0")"
JDK="${MYCELIUM_JDK:-${JAVA_HOME:-}}"
if [ -n "$JDK" ] && [ -x "$JDK/bin/javac" ]; then JAVAC="$JDK/bin/javac"; JAVA="$JDK/bin/java";
elif [ -n "$JDK" ] && [ -x "$JDK/bin/javac.exe" ]; then JAVAC="$JDK/bin/javac.exe"; JAVA="$JDK/bin/java.exe";
else JAVAC=javac; JAVA=java; fi

# The shared harness, as harness/sources.txt defines it (one definition for every runner).
harness_files() { tr -d '\r' < harness/sources.txt | grep -v '^\s*#' | grep -v '^\s*$' | while read -r d; do find "$d" -name '*.java'; done; }

slugs=("$@")
if [ ${#slugs[@]} -eq 0 ]; then
  for d in contracts/*/; do [ -d "$d/src" ] && slugs+=("$(basename "$d")"); done
fi

total=0
# The harness's own checks (harness/test): each class is a main that exits 0 when it holds.
checks="harness/out-checks"; rm -rf "$checks"; mkdir -p "$checks"
if "$JAVAC" --release 17 -d "$checks" $( { harness_files; find harness/test -name '*.java'; } | sort -u); then
  for f in $(find harness/test -name '*.java' | sort); do
    main=$(echo "${f#harness/test/}" | sed -e 's#/#.#g' -e 's#\.java$##')
    if "$JAVA" -cp "$checks" "$main" > "$checks.log" 2>&1; then echo "[harness] ${main##*.} holds";
    else cat "$checks.log"; echo "[harness] ${main##*.} DOES NOT HOLD"; total=$((total+1)); fi
  done
else
  echo "[harness] COMPILE FAILED"; total=$((total+1))
fi
rm -rf "$checks" "$checks.log"
echo

for slug in "${slugs[@]}"; do
  pkg="contracts/$slug"
  if [ ! -d "$pkg/src" ]; then echo "[$slug] NO PACKAGE (no $pkg/src)"; total=$((total+1)); continue; fi
  # the main every runner starts: exactly one harness/Run.java (CONTRACT-PACKAGE.md)
  runs=$(find "$pkg/src" -path '*/harness/Run.java')
  count=$(printf '%s' "$runs" | grep -c .)
  if [ "$count" -ne 1 ]; then echo "[$slug] expected exactly one harness/Run.java, found $count"; total=$((total+1)); continue; fi
  out="$pkg/out"
  rm -rf "$out"; mkdir -p "$out"
  # the models this package requires, from the registry (docs/decisions/0014): their model packages only
  required=$("$JAVA" harness/tools/RequiredModels.java "$slug" | tr -d '\015')
  files=$( { harness_files; find "$pkg/src" -name '*.java'; for d in $required; do find "$d" -name '*.java'; done; } | sort -u)
  if ! "$JAVAC" --release 17 -d "$out" $files; then
    echo "[$slug] COMPILE FAILED"; total=$((total+1)); continue
  fi
  main=$(echo "${runs#$pkg/src/}" | sed -e 's#/#.#g' -e 's#\.java$##')
  "$JAVA" -cp "$out" "$main"; code=$?
  echo "[$slug] exit=$code"; echo
  total=$((total+code))
done
echo "TOTAL exit=$total"
exit $total
