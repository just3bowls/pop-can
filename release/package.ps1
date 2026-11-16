<#
.SYNOPSIS
  Build a complete Windows release bundle containing the extension and native host.

.PARAMETER SkipBuild
  Reuse the existing server\target\native-host\windows\app-image app image.

.PARAMETER PrepareModel
  Download the Memory embedding model before rebuilding the native host.
#>
[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$PrepareModel
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$version = (Get-Content -Raw (Join-Path $repo "VERSION")).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "invalid VERSION: $version" }
& node (Join-Path $repo "release\check-extension-id.mjs")
if ($LASTEXITCODE -ne 0) { throw "fixed extension ID validation failed" }
if ($env:POP_CAN_SKIP_BUILD -eq "1") { $SkipBuild = $true }

if ($PrepareModel -and $SkipBuild) {
  Write-Host "-PrepareModel requires rebuilding the native host; it cannot be combined with -SkipBuild" -ForegroundColor Red
  exit 2
}

if ($PrepareModel) {
  & (Join-Path $repo "server\scripts\pop-can.ps1") model
  $env:POP_CAN_REQUIRE_MEMORY_MODEL = "1"
}

if (-not $SkipBuild) {
  & (Join-Path $repo "server\scripts\pop-can.ps1") build -Version $version
}

$extensionDir = Join-Path $repo "client-extension\dist"
$hostDir = Join-Path $repo "server\target\native-host\windows\app-image\PopCanHost"
if (-not (Test-Path (Join-Path $extensionDir "manifest.json"))) {
  throw "extension build not found: $extensionDir\manifest.json"
}
if ((Test-Path (Join-Path $extensionDir "target")) -or (Test-Path (Join-Path $extensionDir "vendor"))) {
  throw "production extension directory contains development artifacts: $extensionDir"
}
& node (Join-Path $repo "client-extension\scripts\check-provider-pack-exclusion.mjs") --dist $extensionDir
if ($LASTEXITCODE -ne 0) { throw "production extension contains bundled provider rules" }
if (-not (Test-Path (Join-Path $hostDir "PopCanHost.exe"))) {
  throw "native host build not found: $hostDir\PopCanHost.exe"
}

$bundleName = "pop-can-v$version-windows"
$releaseDir = $env:POP_CAN_RELEASE_DIR
if ([string]::IsNullOrWhiteSpace($releaseDir)) { $releaseDir = Join-Path $repo "release\artifacts" }
$asset = Join-Path $releaseDir "$bundleName.zip"
$checksum = "$asset.sha256"
$archiveManifest = "$asset.manifest.json"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pop-can-release-windows-" + [System.Guid]::NewGuid())
$bundle = Join-Path $stageRoot $bundleName
New-Item -ItemType Directory -Force -Path (Join-Path $bundle "native-host") | Out-Null

try {
  Copy-Item -Recurse -Path $extensionDir -Destination (Join-Path $bundle "extension")
  Copy-Item -Recurse -Path $hostDir -Destination (Join-Path $bundle "native-host\PopCanHost")
  New-Item -ItemType Directory -Force -Path (Join-Path $bundle ".pop-can") | Out-Null
  Copy-Item -Path (Join-Path $PSScriptRoot "host.ps1") -Destination (Join-Path $bundle "host.ps1")
  Copy-Item -Path (Join-Path $repo "server\scripts\pop-can.ps1") -Destination (Join-Path $bundle ".pop-can\pop-can.ps1")
  Copy-Item -Path (Join-Path $repo "client-extension\keys\extension-id.txt") -Destination (Join-Path $bundle ".pop-can\extension-id.txt")
  Copy-Item -Path (Join-Path $PSScriptRoot "provider-pack-url.txt") -Destination (Join-Path $bundle ".pop-can\provider-pack-url.txt")
  Copy-Item -Path (Join-Path $PSScriptRoot "BUNDLE_README.md") -Destination (Join-Path $bundle "README.md")
  Copy-Item -Path (Join-Path $repo "docs\NATIVE_HOST.md") -Destination (Join-Path $bundle "NATIVE_HOST.md")
  Copy-Item -Path (Join-Path $repo "docs\PROVIDER_PACK.md") -Destination (Join-Path $bundle "PROVIDER_PACK.md")
  Copy-Item -Path (Join-Path $repo "LICENSE") -Destination $bundle
  Copy-Item -Path (Join-Path $repo "SECURITY.md") -Destination $bundle
  Copy-Item -Path (Join-Path $repo "VERSION") -Destination $bundle

  $commit = (& git -C $repo rev-parse HEAD).Trim()
  $gitStatus = (& git -C $repo status --porcelain --untracked-files=normal) -join "`n"
  $sourceClean = [string]::IsNullOrWhiteSpace($gitStatus)
  if ($env:POP_CAN_REQUIRE_CLEAN_SOURCE -eq "1" -and -not $sourceClean) {
    throw "release requires a clean tracked and untracked worktree"
  }
  $sourceDateEpoch = if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
    (& git -C $repo show -s --format=%ct HEAD).Trim()
  } else {
    $env:SOURCE_DATE_EPOCH
  }
  $commitTime = (& node -e "process.stdout.write(new Date(Number('$sourceDateEpoch') * 1000).toISOString())").Trim()

  $syftBin = $env:POP_CAN_SYFT_BIN
  if ([string]::IsNullOrWhiteSpace($syftBin)) {
    $syftBin = (& node (Join-Path $repo "release\install-syft.mjs")).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Syft installation failed" }
  }
  if (-not (Test-Path $syftBin)) { throw "Syft executable not found: $syftBin" }
  $rawSbom = Join-Path $stageRoot "sbom.raw.json"
  & $syftBin "dir:$bundle" --source-name $bundleName --source-version $version "-o" "cyclonedx-json=$rawSbom"
  if ($LASTEXITCODE -ne 0) { throw "Syft scan failed" }
  & node (Join-Path $repo "release\normalize-sbom.mjs") `
    --input $rawSbom `
    --output (Join-Path $bundle "SBOM.cdx.json") `
    --commit $commit `
    --platform windows `
    --timestamp $commitTime
  if ($LASTEXITCODE -ne 0) { throw "SBOM normalization failed" }
  & node (Join-Path $repo "release\archive-metadata.mjs") prepare `
    --root $bundle `
    --platform windows `
    --version $version `
    --archive-name "$bundleName.zip" `
    --commit $commit `
    --commit-time $commitTime `
    --source-date-epoch $sourceDateEpoch `
    --source-clean $sourceClean.ToString().ToLowerInvariant()
  if ($LASTEXITCODE -ne 0) { throw "archive metadata generation failed" }
  & node (Join-Path $repo "release\archive-metadata.mjs") touch --root $bundle --epoch $sourceDateEpoch
  if ($LASTEXITCODE -ne 0) { throw "archive mtime normalization failed" }

  New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
  Remove-Item -Force -ErrorAction SilentlyContinue $asset, $checksum, $archiveManifest
  Compress-Archive -Path $bundle -DestinationPath $asset

  $verifyRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pop-can-release-verify-windows-" + [System.Guid]::NewGuid())
  try {
    Expand-Archive -LiteralPath $asset -DestinationPath $verifyRoot -Force
    & node (Join-Path $repo "release\archive-metadata.mjs") verify `
      --root (Join-Path $verifyRoot $bundleName) `
      --platform windows `
      --extension-version $version
    if ($LASTEXITCODE -ne 0) { throw "archive extraction verification failed" }
  } finally {
    if (Test-Path $verifyRoot) { Remove-Item -Recurse -Force $verifyRoot }
  }
  & node (Join-Path $repo "release\archive-metadata.mjs") finalize --archive $asset --root $bundle
  if ($LASTEXITCODE -ne 0) { throw "archive manifest generation failed" }
  $hash = (Get-FileHash -Algorithm SHA256 $asset).Hash.ToLowerInvariant()
  Set-Content -Path $checksum -Value "$hash  $bundleName.zip" -Encoding ascii
} catch {
  Remove-Item -Force -ErrorAction SilentlyContinue $asset, $checksum, $archiveManifest
  throw
} finally {
  if (Test-Path $stageRoot) { Remove-Item -Recurse -Force $stageRoot }
}

Write-Host "packaged: $asset"
Write-Host "checksum: $checksum"
Write-Host "manifest: $archiveManifest"
