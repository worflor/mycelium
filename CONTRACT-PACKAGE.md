# Contract Package Layout

Every Mycelium contract package is a self-contained, dependency-free Java 17 directory that compiles with
`javac` alone (plus `harness/src`). It is design-time only: it never imports Minecraft or a loader.

```
contracts/<slug>/
├── SPEC.md                         normative: scope, types, outcomes, laws, positive obligations,
│                                   edge semantics, traits, fixtures table, rejection matrix, versioning,
│                                   explicit non-claims  (mirror contracts/flow/SPEC.md)
└── src/mycelium/<slug>/v1/
    ├── model/                      the contract: interfaces, records, sealed outcomes, the reference
    │                               executor/algorithm if the contract has one
    ├── fixtures/                   the in-memory reference implementation AND every deliberately
    │                               incorrect implementation named in the rejection matrix
    └── harness/
        ├── <Name>ContractTests.java   fixtures T1..Tn against an implementation, labels "T3:label"
        └── Run.java                   main(): mycelium.harness.ContractRun.run(id, reference, matrix)
```

Rules:


Run one package:

```powershell
$h = Get-ChildItem -Recurse -Filter *.java ..\..\harness\src | % FullName
$s = Get-ChildItem -Recurse -Filter *.java src | % FullName
& "$env:JAVA_HOME\bin\javac.exe" --release 17 -d out ($h + $s)
& "$env:JAVA_HOME\bin\java.exe" -cp out mycelium.<slug>.v1.harness.Run
```

Run all packages: `./run-contracts.sh` or `./run-contracts.ps1` at the Mycelium root.
