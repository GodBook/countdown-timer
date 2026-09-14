param([string]$NotesFile = 'release-notes.md')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$metadata = Get-Content -Raw (Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json') | ConvertFrom-Json
$version = $metadata.elements[0].versionName
$code = [long]$metadata.elements[0].versionCode
$apkName = "countdown-timer-$version.apk"
$apkPath = Join-Path $projectRoot "dist\$apkName"
if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the signed release first.' }
$notes = Get-Content -Raw -Encoding utf8 (Join-Path $projectRoot $NotesFile)
$manifest = [ordered]@{
    schemaVersion = 1
    versionCode = $code
    versionName = $version
    minSdk = 26
    apkUrl = "https://github.com/GodBook/countdown-timer/releases/download/v$version/$apkName"
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash.ToLowerInvariant()
    size = (Get-Item -LiteralPath $apkPath).Length
    notes = $notes.Trim()
}
$manifest | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $projectRoot 'dist\update.json')
Write-Output "Prepared update.json for v$version (versionCode $code)."
