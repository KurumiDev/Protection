# RuGuard full build, protect, test, and redteam pipeline.
# Compiles all modules (annotations, runtime, CLI, bootstrap, samples, redteam),
# packages samples.jar, protects it, runs the golden test, and executes redteam checks.

$ErrorActionPreference = 'Stop'

# Ensure JDK bin is in PATH
$env:PATH = "C:\Program Files\Java\jdk-21.0.10\bin;$env:PATH"
$env:RUGUARD_DEV_MODE = "1"

$root   = 'D:\Protection\RuGuard'
$libs   = 'D:\Protection\tools\lib'
$out    = "$root\out\build"
$jarOut = "$root\out"

# --- Clean --------------------------------------------------------------------
Write-Host ''
Write-Host '=== Cleaning build directory ===' -ForegroundColor Yellow
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
if (-not (Test-Path $jarOut)) {
    New-Item -ItemType Directory -Force -Path $jarOut | Out-Null
}
if (Test-Path "$jarOut\ruguard_native.dll") { Remove-Item "$jarOut\ruguard_native.dll" -Force }
if (Test-Path "$root\ruguard_native.dll") { Remove-Item "$root\ruguard_native.dll" -Force }

# --- Classpath for external libraries -----------------------------------------
$cp_libs = (Get-ChildItem "$libs\*.jar" | ForEach-Object { $_.FullName }) -join ';'

# --- Generate Seed and Master Secret ------------------------------------------
Write-Host '=== Generating Build Keys ===' -ForegroundColor Yellow
$seedBytes = New-Object byte[] 8
$secretBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($seedBytes)
$rng.GetBytes($secretBytes)
$seedHex = ($seedBytes | ForEach-Object { "{0:x2}" -f $_ }) -join ""
$secretHex = ($secretBytes | ForEach-Object { "{0:x2}" -f $_ }) -join ""
Write-Host "  Seed:   $seedHex"
Write-Host "  Secret: $secretHex"

# Write master secret to secret.rs for Cargo compilation
$rustSecret = "pub const MASTER_SECRET: [u8; 32] = [" + ($secretBytes -join ", ") + "];`r`n"
Set-Content -Path "$root\native\barrier\src\secret.rs" -Value $rustSecret -Encoding Ascii

# --- Step 0: Compile Native Rust Barrier --------------------------------------
Write-Host '=== Step 0: Compile Native Rust Barrier ===' -ForegroundColor Cyan
$oldCwd = Get-Location
try {
    Set-Location "$root\native\barrier"
    $env:RUSTUP_HOME = "D:\Protection\tools\rustup"
    $env:CARGO_HOME = "D:\Protection\tools\cargo"
    $env:RUSTFLAGS = "-L D:\Protection\tools\libgcc_eh"
    $env:PATH = "D:\Protection\tools\cargo\bin;D:\MinGW\bin;$env:PATH"

    & cargo build --release
    if ($LASTEXITCODE -ne 0) { throw 'Rust native barrier compilation failed' }

    # Copy DLL to output locations
    $dllSrc = "$root\native\barrier\target\release\ruguard_native.dll"
    Copy-Item $dllSrc -Destination "$jarOut\ruguard_native.dll" -Force
    Copy-Item $dllSrc -Destination "$root\ruguard_native.dll" -Force

    Write-Host '  OK' -ForegroundColor Green
} finally {
    Set-Location $oldCwd
}


# --- Helper: compile a module -------------------------------------------------
function Compile-Module {
    param(
        [string]$StepName,
        [string]$SourcePath,
        [string]$Pattern = '*.java',
        [switch]$Recurse
    )
    Write-Host "=== $StepName ===" -ForegroundColor Cyan

    $srcFiles = if ($Recurse) {
        Get-ChildItem $SourcePath -Recurse -Filter $Pattern | ForEach-Object { $_.FullName }
    } else {
        Get-ChildItem $SourcePath -Filter $Pattern | ForEach-Object { $_.FullName }
    }

    if (-not $srcFiles -or $srcFiles.Count -eq 0) {
        throw "$StepName - no source files found in $SourcePath"
    }

    Write-Host "  Compiling $($srcFiles.Count) file(s)..."
    & javac -d $out -cp "$out;$cp_libs" $srcFiles
    if ($LASTEXITCODE -ne 0) { throw "$StepName compilation failed (exit code $LASTEXITCODE)" }
    Write-Host "  OK" -ForegroundColor Green
}

# --- Step 1: Compile annotations ----------------------------------------------
Compile-Module -StepName 'Step 1: Compile annotations' `
               -SourcePath "$root\cli\src\io\ruguard\annotation"

# --- Step 2: Compile runtime --------------------------------------------------
Compile-Module -StepName 'Step 2: Compile runtime' `
               -SourcePath "$root\runtime\src" -Recurse

# --- Step 3: Compile CLI ------------------------------------------------------
Compile-Module -StepName 'Step 3: Compile CLI' `
               -SourcePath "$root\cli\src\io\ruguard\cli"

# --- Step 4: Compile bootstrap ------------------------------------------------
Compile-Module -StepName 'Step 4: Compile bootstrap' `
               -SourcePath "$root\bootstrap\src" -Recurse

# --- Step 5: Compile samples -------------------------------------------------
Compile-Module -StepName 'Step 5: Compile samples' `
               -SourcePath "$root\samples\src" -Recurse

# --- Step 6: Compile redteam -------------------------------------------------
Compile-Module -StepName 'Step 6: Compile redteam' `
               -SourcePath "$root\redteam\src" -Recurse

# --- Step 7: Package samples.jar ---------------------------------------------
Write-Host '=== Step 7: Package samples.jar ===' -ForegroundColor Cyan
$manifestFile = "$jarOut\MANIFEST.MF"
# Write manifest with Main-Class and a trailing newline (required by jar spec)
Set-Content -Path $manifestFile -Value "Main-Class: io.ruguard.samples.SampleMod`n" -NoNewline
& jar cfm "$jarOut\samples.jar" $manifestFile -C $out io/ruguard/samples/ -C $out io/ruguard/annotation/
if ($LASTEXITCODE -ne 0) { throw 'Packaging samples.jar failed' }
Write-Host '  OK' -ForegroundColor Green

# --- Step 8: Run original (unprotected) ---------------------------------------
Write-Host '=== Step 8: Run original ===' -ForegroundColor Cyan
$origOutput = & java -jar "$jarOut\samples.jar" 2>&1 | Out-String
Write-Host $origOutput

# --- Step 9: Protect samples.jar ---------------------------------------------
Write-Host '=== Step 9: Protect samples.jar ===' -ForegroundColor Cyan
& java -cp "$out;$cp_libs" io.ruguard.cli.ProtectTool "$jarOut\samples.jar" "$jarOut\protected-samples.jar" --seed $seedHex --secret $secretHex
if ($LASTEXITCODE -ne 0) { throw 'Protection failed' }

# --- Step 10: Run protected jar as standalone ---------------------------------
Write-Host '=== Step 10: Run protected ===' -ForegroundColor Cyan
$protOutput = & java -jar "$jarOut\protected-samples.jar" 2>&1 | Out-String
Write-Host $protOutput

# --- Step 11: Golden test - compare outputs -----------------------------------
Write-Host '=== Step 11: Golden test ===' -ForegroundColor Cyan
# --- Filter out diagnostic output for strict comparison ---
$origClean = ($origOutput -split "`r?`n" | Where-Object { -not $_.StartsWith("[RuGuard") -and -not $_.StartsWith("[LibraryLoader") }) -join "`n"
$protClean = ($protOutput -split "`r?`n" | Where-Object { -not $_.StartsWith("[RuGuard") -and -not $_.StartsWith("[LibraryLoader") }) -join "`n"

if ($origClean.Trim() -eq $protClean.Trim()) {
    Write-Host 'GOLDEN TEST PASSED - outputs match!' -ForegroundColor Green
} else {
    Write-Host 'GOLDEN TEST FAILED - outputs differ!' -ForegroundColor Red
    Write-Host "--- Original ---"
    Write-Host $origOutput
    Write-Host "--- Protected ---"
    Write-Host $protOutput
    exit 1
}

# --- Step 12: Redteam ---------------------------------------------------------
Write-Host '=== Step 12: Redteam ===' -ForegroundColor Cyan
& java -cp "$out;$cp_libs" io.ruguard.redteam.RedteamRunner "$jarOut\protected-samples.jar"
if ($LASTEXITCODE -ne 0) { throw 'Redteam detected protection failure!' }

# --- Done ---------------------------------------------------------------------
Write-Host ''
Write-Host '========================================' -ForegroundColor Green
Write-Host '  ALL DONE - RuGuard build successful'   -ForegroundColor Green
Write-Host '========================================' -ForegroundColor Green
