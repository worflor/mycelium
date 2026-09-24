# Compiles the shared harness plus every contract package and runs each package's Run main.
# Exit code is the sum of all package exit codes. Usage: .\run-contracts.ps1 [slug ...]
param([string[]]$Slugs)
Set-Location $PSScriptRoot
$jdk = if ($env:MYCELIUM_JDK) { $env:MYCELIUM_JDK } else { $env:JAVA_HOME }
$javac = if ($jdk) { Join-Path $jdk "bin\javac.exe" } else { "javac" }
$java = if ($jdk) { Join-Path $jdk "bin\java.exe" } else { "java" }

if (-not $Slugs -or $Slugs.Count -eq 0) {
    $Slugs = Get-ChildItem contracts -Directory | Where-Object { Test-Path (Join-Path $_.FullName "src") } | ForEach-Object { $_.Name }
}

$total = 0
foreach ($slug in $Slugs) {
    $pkg = Join-Path "contracts" $slug
    $out = Join-Path $pkg "out"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force $out | Out-Null
    # the shared harness, as harness/sources.txt defines it, then the package; each file once
    $dirs = Get-Content "harness\sources.txt" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith("#") }
    $files = @($dirs | ForEach-Object { Get-ChildItem -Recurse -Filter *.java $_.Trim() } | ForEach-Object FullName)
    $files += @(Get-ChildItem -Recurse -Filter *.java (Join-Path $pkg "src") | ForEach-Object FullName)
    $files = @($files | Sort-Object -Unique)
    & $javac --release 17 -d $out $files
    if ($LASTEXITCODE -ne 0) { Write-Host "[$slug] COMPILE FAILED"; $total += 1; continue }
    $mainFile = Get-ChildItem -Recurse -Filter Run.java (Join-Path $pkg "src") | Select-Object -First 1
    $rel = $mainFile.FullName.Substring((Resolve-Path (Join-Path $pkg "src")).Path.Length + 1)
    $main = ($rel -replace "\\", ".") -replace "\.java$", ""
    & $java -cp $out $main
    $code = $LASTEXITCODE
    Write-Host "[$slug] exit=$code"; Write-Host ""
    $total += $code
}
Write-Host "TOTAL exit=$total"
exit $total
