param([string]$NotesFile = 'release-notes.md')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (git status --porcelain --untracked-files=normal) { throw 'Commit the reviewed source before publishing.' }
    & (Join-Path $PSScriptRoot 'build.ps1')
    & python (Join-Path $PSScriptRoot 'package-source.py')
    if ($LASTEXITCODE -ne 0) { throw 'Source packaging failed.' }
    & (Join-Path $PSScriptRoot 'prepare-update.ps1') -NotesFile $NotesFile
    $manifest = Get-Content -Raw dist\update.json | ConvertFrom-Json
    $version = $manifest.versionName
    $tag = "v$version"
    $commit = git rev-parse HEAD
    & gh release view $tag --repo GodBook/countdown-timer *> $null
    if ($LASTEXITCODE -eq 0) { throw 'This release already exists. Never overwrite a published updater manifest or APK.' }
    & gh release create $tag "dist/countdown-timer-$version.apk" "dist/countdown-timer-source-$version.zip" dist/update.json dist/SHA256SUMS.txt --repo GodBook/countdown-timer --target $commit --title "计时器 $version" --notes-file $NotesFile --draft
    if ($LASTEXITCODE -ne 0) { throw 'Draft creation/upload failed; inspect GitHub before retrying.' }
    & gh release edit $tag --repo GodBook/countdown-timer --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Publish failed; uploaded assets remain in a draft.' }
} finally { Pop-Location }
