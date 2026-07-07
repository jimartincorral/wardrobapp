<#
.SYNOPSIS
    Build the Android APK with Gradle, self-healing a broken/stale JAVA_HOME.

.DESCRIPTION
    The recurring "JAVA_HOME is set to an invalid directory" error happens because
    Adoptium installs the JDK into a version-stamped folder (e.g. jdk-17.0.18.8),
    and a later patch update creates a new folder (jdk-17.0.19.10) while JAVA_HOME
    keeps pointing at the deleted one.

    This script resolves a WORKING JDK at build time — independent of the exact
    patch version — so the build never depends on a stale env var. Resolution order:
      1. $env:JAVA_HOME, if it actually contains bin\java.exe
      2. `java` on PATH (resolve back to its JDK home)
      3. Newest jdk-17* under "C:\Program Files\Eclipse Adoptium"
      4. Android Studio's bundled JBR

.PARAMETER Task
    Gradle task to run. Defaults to assembleRelease (self-contained, installable APK).
    Use assembleDebug for a debug build.

.EXAMPLE
    npm run apk
    powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1
    powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1 -Task assembleDebug
#>
[CmdletBinding()]
param(
    [string]$Task = 'assembleRelease'
)

$ErrorActionPreference = 'Stop'

function Test-Jdk([string]$jdkHome) {
    return $jdkHome -and (Test-Path (Join-Path $jdkHome 'bin\java.exe'))
}

function Resolve-JavaHome {
    # 1. Existing JAVA_HOME, if valid
    if (Test-Jdk $env:JAVA_HOME) { return $env:JAVA_HOME }

    # 2. `java` on PATH -> <jdk>\bin\java.exe, so home is two levels up
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $jdkHome = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
        if (Test-Jdk $jdkHome) { return $jdkHome }
    }

    # 3. Newest Adoptium JDK 17
    $adoptium = 'C:\Program Files\Eclipse Adoptium'
    if (Test-Path $adoptium) {
        $jdk = Get-ChildItem $adoptium -Directory -Filter 'jdk-17*' |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($jdk -and (Test-Jdk $jdk.FullName)) { return $jdk.FullName }
    }

    # 4. Android Studio bundled JBR
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Jdk $jbr) { return $jbr }

    return $null
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $repoRoot 'android'
$gradlew = Join-Path $androidDir 'gradlew.bat'

if (-not (Test-Path $gradlew)) {
    throw "Gradle wrapper not found at $gradlew. Run `npx expo prebuild` first to generate the android/ project."
}

$javaHome = Resolve-JavaHome
if (-not $javaHome) {
    throw "No valid JDK found. Install a JDK 17 (e.g. Eclipse Adoptium) or set JAVA_HOME to a valid JDK."
}

Write-Host "Using JDK: $javaHome" -ForegroundColor Cyan
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"
# Silences the "NODE_ENV environment variable is required" warning during bundling.
if (-not $env:NODE_ENV) { $env:NODE_ENV = 'production' }

Write-Host "Running: gradlew $Task" -ForegroundColor Cyan
& $gradlew -p $androidDir $Task --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE."
}

$apk = Get-ChildItem (Join-Path $androidDir 'app\build\outputs\apk') -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($apk) {
    Write-Host ""
    Write-Host "APK built: $($apk.FullName)" -ForegroundColor Green
    Write-Host ("Size: {0} MB" -f [math]::Round($apk.Length / 1MB, 1)) -ForegroundColor Green
} else {
    Write-Host "Build finished, but no APK was found under android/app/build/outputs/apk." -ForegroundColor Yellow
}
