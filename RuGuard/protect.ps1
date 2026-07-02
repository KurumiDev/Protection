<#
.SYNOPSIS
    Convenience wrapper to protect a JAR file with RuGuard.
.DESCRIPTION
    Invokes io.ruguard.cli.ProtectTool with the compiled classes and library jars
    on the classpath. Requires that build-all.ps1 has been run first.
.PARAMETER Input
    Path to the input JAR file to protect.
.PARAMETER Output
    Path for the protected output JAR file.
.PARAMETER Seed
    Optional 16-character hex string (8 bytes) for reproducible protection.
.PARAMETER Secret
    Optional 64-character hex string (32 bytes) for reproducible encryption.
.EXAMPLE
    .\protect.ps1 -Input .\out\samples.jar -Output .\out\protected-samples.jar
.EXAMPLE
    .\protect.ps1 -Input .\out\samples.jar -Output .\out\protected-samples.jar -Seed 0123456789abcdef -Secret 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Input,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$Seed,

    [string]$Secret
)

$ErrorActionPreference = 'Stop'

$root = 'D:\Protection\RuGuard'
$libs = 'D:\Protection\tools\lib'
$buildDir = "$root\out\build"

# Verify build output exists
if (-not (Test-Path $buildDir)) {
    Write-Error "Build directory not found at $buildDir — run build-all.ps1 first."
    exit 1
}

# Assemble classpath: build output + all library jars
$libJars = (Get-ChildItem "$libs\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$cp = "$buildDir;$libJars"

# Build command arguments
$cmdArgs = @($Input, $Output)
if ($Seed)   { $cmdArgs += '--seed',   $Seed }
if ($Secret) { $cmdArgs += '--secret', $Secret }

Write-Host "RuGuard Protect Wrapper" -ForegroundColor Cyan
Write-Host "  Input:  $Input"
Write-Host "  Output: $Output"
Write-Host ""

& java -cp $cp io.ruguard.cli.ProtectTool @cmdArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Protection failed (exit code $LASTEXITCODE)"
    exit $LASTEXITCODE
}
