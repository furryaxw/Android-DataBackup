# Build rustic CLI binary for Android
# Requires: Rust toolchain, Android NDK 25.2.9519653, cargo targets

param(
    [string]$NdkDir = "D:\Android\sdk\ndk\25.2.9519653",
    [string]$Arch = "all"
)

$ErrorActionPreference = "Stop"
$RusticDir = "$PSScriptRoot\..\source\native\src\main\jni\rustic"
$BuildOutDir = "$PSScriptRoot\..\source\native\build_bin\built_in"

$archMap = @{
    "aarch64-linux-android" = "arm64-v8a"
    "x86_64-linux-android"   = "x86_64"
    "armv7-linux-androideabi" = "armeabi-v7a"
    "i686-linux-android"      = "x86"
}

$targets = if ($Arch -eq "all") { $archMap.Keys } else { @($Arch) }
$toolchain = "$NdkDir\toolchains\llvm\prebuilt\windows-x86_64\bin"
$strip = "$toolchain\llvm-strip.exe"
$api = "28"

Write-Host "NDK toolchain: $toolchain"

foreach ($target in $targets) {
    if (-not (Test-Path "$toolchain")) {
        Write-Error "NDK toolchain not found: $toolchain"
        exit 1
    }

    Write-Host ""
    Write-Host "=== Building for $target ==="

    $clangName = "$target$api-clang.cmd"
    $clangPath = "$toolchain\$clangName"

    if (-not (Test-Path $clangPath)) {
        Write-Warning "Clang not found: $clangPath, skipping"
        continue
    }

    $envVarPrefix = $target.Replace("-", "_").Replace(".", "_")
    Set-Item -Path "env:CC_$envVarPrefix" -Value $clangPath
    Set-Item -Path "env:AR_$envVarPrefix" -Value "$toolchain\llvm-ar.exe"
    Set-Item -Path "env:CARGO_TARGET_$($envVarPrefix.ToUpper())_LINKER" -Value $clangPath

    cargo build --release --target $target --manifest-path "$RusticDir\Cargo.toml" 2>&1 | Select-Object -Last 3

    $srcBin = "$RusticDir\target\$target\release\rustic"
    if (Test-Path $srcBin) {
        & $strip $srcBin 2>$null
        $size = (Get-Item $srcBin).Length
        $abiName = $archMap[$target]
        $outDir = "$BuildOutDir\$abiName"
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
        Copy-Item $srcBin "$outDir\rustic" -Force
        Write-Host "  $target -> $abiName ($size bytes)"
    } else {
        Write-Error "Build output not found: $srcBin"
    }
}

Write-Host ""
Write-Host "=== Build complete ==="
Write-Host "Binaries: $BuildOutDir"
