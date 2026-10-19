#!/usr/bin/env python3
"""Run initializer phase/store witnesses in fresh JVMs, optionally comparing C1 diagnostics."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess


ENTRIES = (
    "clearOnInitUnsafe", "bindOnInitSafe", "failedClearUnsafe",
    "failedBindSafe", "erroneousRetrySafe", "erroneousRetryUnsafe",
)


def run(args):
    if args.analyzer_classpath:
        for entry in args.analyzer_classpath.split(os.pathsep):
            if not Path(entry).exists():
                raise ValueError(f"Analyzer classpath entry does not exist (expand wildcards explicitly): {entry}")
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = Path(__file__).resolve().parent / "fixtures" / "initialization-phases"
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    api, classes, runtime = (output / name for name in ("api", "classes", "runtime"))
    for name, destination, classpath in (("Api", api, None), ("Cases", classes, str(api)),
                                          ("Runner", runtime, os.pathsep.join(map(str, (api, classes))))):
        command = [args.javac, "--release", "8", "-g", "-Xlint:-options", "-d", str(destination)]
        if classpath:
            command += ["-cp", classpath]
        subprocess.run(command + [str(fixture / (name + ".java"))], check=True)
    report = {"schema": 1, "bytecodeRelease": 8, "complete": False,
              "scope": "Finite runtime witnesses; diagnostic candidates are not safety proofs",
              "sources": {p.name: digest(p) for p in sorted(fixture.glob("*.java"))},
              "classes": {p.relative_to(output).as_posix(): digest(p) for p in sorted(output.rglob("*.class"))},
              "runs": []}
    for java in args.java:
        version = subprocess.run([java, "-version"], check=True, capture_output=True, text=True)
        observations = []
        for entry in ENTRIES:
            execution = subprocess.run([java, "-cp", os.pathsep.join(map(str, (api, classes, runtime))),
                                        "Runner", entry], check=True, capture_output=True, text=True)
            expected = f"{entry}\t{str(entry.endswith('Unsafe')).lower()}\t1"
            if execution.stdout.strip() != expected:
                raise ValueError(f"Unexpected witness output: {execution.stdout!r}")
            observations.append({"entry": entry, "nullDereference": entry.endswith("Unsafe"), "callbackCalls": 1})
        report["runs"].append({"java": java, "version": (version.stdout + version.stderr).strip(), "observations": observations})
    if args.analyzer_classpath:
        analysis_file = output / "analysis.json"
        with (output / "analysis.log").open("w", encoding="utf-8") as log:
            subprocess.run([args.analysis_java, "-cp", args.analyzer_classpath,
                            "dev.woflo.mycelium.gradle.BehaviorLab", "--worker", classes.as_uri(), "8", analysis_file.as_uri()],
                           check=True, stdout=log, stderr=subprocess.STDOUT)
        analysis = json.loads(analysis_file.read_text(encoding="utf-8"))
        if not analysis["complete"] or analysis["analysisErrors"]:
            raise ValueError("Incomplete analyzer run; see analysis.json and analysis.log")
        roots = {(root["owner"], root["name"], root["desc"]) for root in analysis["roots"]}
        required = {("initprobe/Cases", entry, "()V") for entry in ENTRIES}
        if not required.issubset(roots):
            raise ValueError(f"Analyzer omitted initializer probe roots: {sorted(required - roots)}")
        version = subprocess.run([args.analysis_java, "-version"], check=True, capture_output=True, text=True)
        location = subprocess.run([args.analysis_java, "-cp", str(runtime), "Runner", "--java-home"],
                                  check=True, capture_output=True, text=True)
        java_home = Path(base64.b64decode(location.stdout.strip(), validate=True).decode("utf-8"))
        platform = {name: digest(java_home / "lib" / name) if (java_home / "lib" / name).is_file() else None
                    for name in ("modules", "ct.sym")}
        report["analysis"] = {"fullReportSha256": digest(analysis_file), "platformHierarchy": analysis["platformHierarchy"],
                              "java": args.analysis_java,
                              "version": (version.stdout + version.stderr).strip(),
                              "platformInputs": {"javaHome": str(java_home), "sha256": platform},
                              "checkedRoots": [owner + "#" + name + descriptor for owner, name, descriptor in sorted(required)],
                              "classpath": {entry: digest(Path(entry)) if Path(entry).is_file() else
                                            {p.relative_to(entry).as_posix(): digest(p) for p in sorted(Path(entry).rglob("*.class"))}
                                            for entry in args.analyzer_classpath.split(os.pathsep)},
                              "entryCandidates": {entry: sum(f["root"]["owner"] == "initprobe/Cases" and f["root"]["name"] == entry
                                                             for f in analysis["findings"]) for entry in ENTRIES}}
    report["complete"] = True
    (output / "receipt.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Passed {len(ENTRIES) * len(args.java)} fresh-process runtime witnesses: {output / 'receipt.json'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--javac", required=True, help="JDK compiler supporting --release 8")
    parser.add_argument("--java", action="append", required=True, help="Runtime executable; repeat for each JVM")
    parser.add_argument("--output", required=True, help="New directory for compiled fixtures and evidence")
    parser.add_argument("--analyzer-classpath", help="Compiled plugin main/test classes, ASM and Gson")
    parser.add_argument("--analysis-java", help="Java 17+ runtime for the optional analyzer")
    args = parser.parse_args()
    if bool(args.analyzer_classpath) != bool(args.analysis_java):
        parser.error("--analyzer-classpath and --analysis-java must be supplied together")
    run(args)


if __name__ == "__main__":
    main()
