param([switch]$DebugOnly, [string]$JdkPath = 'D:\dev\jdk-17')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath $JdkPath) { $env:JAVA_HOME = $JdkPath }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
# Use an ASCII drive path: Gradle test workers on Windows can misread non-ASCII classpaths.
$mappedDrive = $null
$buildRoot = $projectRoot
if ($projectRoot -match '[^\x00-\x7F]') {
    foreach ($letter in @('T','U','V','W','X','Y','Z')) {
        if (-not (Test-Path "${letter}:\")) {
            & subst "${letter}:" $projectRoot
            if ($LASTEXITCODE -ne 0) { throw 'Could not map project directory.' }
            $mappedDrive = "${letter}:"
            $buildRoot = "${letter}:\"
            break
        }
    }
    if (-not $mappedDrive) { throw 'No free drive letter for the build.' }
}
try {
    if (-not $DebugOnly -and -not (Test-Path (Join-Path $projectRoot 'signing.properties'))) {
        throw 'Release signing is not configured. Follow README.md; do not replace an existing signing key.'
    }
    $tasks = if ($DebugOnly) { @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug') }
        else { @(':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease') }
    & (Join-Path $projectRoot 'gradlew.bat') -p $buildRoot @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build or validation failed.' }
    if (-not $DebugOnly) {
        $dist = Join-Path $projectRoot 'dist'
        New-Item -ItemType Directory -Force $dist | Out-Null
        $metadata = Get-Content -Raw (Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json') | ConvertFrom-Json
        $version = $metadata.elements[0].versionName
        Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk') -Destination (Join-Path $dist "countdown-timer-$version.apk")
    }
} finally {
    if ($mappedDrive) { & subst $mappedDrive /D }
}
