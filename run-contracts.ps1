# Runs the harness's own checks, then compiles the shared harness plus each contract package and runs its
# harness/Run. Exit code is the sum of every failure. Usage: .\run-contracts.ps1 [slug ...]
# run-contracts.sh does exactly the same; myceliumVerify is the build gate (it also collects each harness's report).
param([string[]]$Slugs)
Set-Location $PSScriptRoot
$jdk = if ($env:MYCELIUM_JDK) { $env:MYCELIUM_JDK } else { $env:JAVA_HOME }
$javac = if ($jdk) { Join-Path $jdk "bin\javac.exe" } else { "javac" }
$java = if ($jdk) { Join-Path $jdk "bin\java.exe" } else { "java" }

# The shared harness, as harness/sources.txt defines it (one definition for every runner).
$dirs = Get-Content "harness\sources.txt" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith("#") }
$harness = @($dirs | ForEach-Object { Get-ChildItem -Recurse -Filter *.java $_.Trim() } | ForEach-Object FullName)

if (-not $Slugs -or $Slugs.Count -eq 0) {
    $Slugs = Get-ChildItem contracts -Directory | Where-Object { Test-Path (Join-Path $_.FullName "src") } | ForEach-Object { $_.Name }
}

$total = 0
# The harness's own checks (harness/test): each class is a main that exits 0 when it holds.
$checks = "harness\out-checks"
if (Test-Path $checks) { Remove-Item -Recurse -Force $checks }
New-Item -ItemType Directory -Force $checks | Out-Null
$tests = @(Get-ChildItem -Recurse -Filter *.java "harness\test" | Sort-Object FullName)
& $javac --release 17 -d $checks @(($harness + @($tests | ForEach-Object FullName)) | Sort-Object -Unique)
if ($LASTEXITCODE -eq 0) {
    $testRoot = (Resolve-Path "harness\test").Path
    foreach ($t in $tests) {
        $main = ($t.FullName.Substring($testRoot.Length + 1) -replace "\\", ".") -replace "\.java$", ""
        $log = & $java -cp $checks $main 2>&1
        $name = $main.Substring($main.LastIndexOf(".") + 1)
        if ($LASTEXITCODE -eq 0) { Write-Host "[harness] $name holds" }
        else { $log | ForEach-Object { Write-Host $_ }; Write-Host "[harness] $name DOES NOT HOLD"; $total += 1 }
    }
} else {
    Write-Host "[harness] COMPILE FAILED"; $total += 1
}
Remove-Item -Recurse -Force $checks
Write-Host ""

foreach ($slug in $Slugs) {
    $pkg = Join-Path "contracts" $slug
    $src = Join-Path $pkg "src"
    if (-not (Test-Path $src)) { Write-Host "[$slug] NO PACKAGE (no $src)"; $total += 1; continue }
    # the main every runner starts: exactly one harness/Run.java (CONTRACT-PACKAGE.md)
    $runs = @(Get-ChildItem -Recurse -Filter Run.java $src | Where-Object { $_.Directory.Name -eq "harness" })
    if ($runs.Count -ne 1) { Write-Host "[$slug] expected exactly one harness/Run.java, found $($runs.Count)"; $total += 1; continue }
    $out = Join-Path $pkg "out"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force $out | Out-Null
    # the models of the contracts this one requires (registry.json "requires", transitively): nothing else of theirs
    $rows = (Get-Content "contracts\registry.json" -Raw | ConvertFrom-Json).contracts
    $mine = $rows | Where-Object { $_.path -eq $slug }
    $todo = New-Object System.Collections.Queue
    @($mine.requires) | Where-Object { $_ } | ForEach-Object { $todo.Enqueue($_) }
    $seen = @{}
    $required = @()
    while ($todo.Count -gt 0) {
        $id = $todo.Dequeue()
        if ($seen.ContainsKey($id)) { continue }
        $seen[$id] = $true
        $row = $rows | Where-Object { $_.id -eq $id }
        if (-not $row) { continue }
        $model = Join-Path "contracts\$($row.path)\src" (Join-Path ($row.package -replace "\.", "\") "model")
        $required += @(Get-ChildItem -Recurse -Filter *.java $model | ForEach-Object FullName)
        @($row.requires) | Where-Object { $_ } | ForEach-Object { $todo.Enqueue($_) }
    }
    $files = @(($harness + $required + @(Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object FullName)) | Sort-Object -Unique)
    & $javac --release 17 -d $out $files
    if ($LASTEXITCODE -ne 0) { Write-Host "[$slug] COMPILE FAILED"; $total += 1; continue }
    $rel = $runs[0].FullName.Substring((Resolve-Path $src).Path.Length + 1)
    $main = ($rel -replace "\\", ".") -replace "\.java$", ""
    & $java -cp $out $main
    $code = $LASTEXITCODE
    Write-Host "[$slug] exit=$code"; Write-Host ""
    $total += $code
}
Write-Host "TOTAL exit=$total"
exit $total
