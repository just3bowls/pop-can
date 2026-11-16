<#
.SYNOPSIS
  Pop Can source-checkout management CLI for Windows.

.DESCRIPTION
  ASCII only, on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
  Windows development uses a jpackage app-image; classpath-based dev hosts are Unix/macOS only.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [ValidateSet("setup", "build", "model", "debug", "register", "unregister", "help")]
  [string]$Command = "help",
  [switch]$SkipModel,
  [switch]$Classpath,
  [string]$Jar = "",
  [string]$Dest = "",
  [string]$Version = "",
  [string]$Base = "https://hf-mirror.com/Xenova/multilingual-e5-small/resolve/main",
  [ValidateSet("", "Chrome", "Edge", "Chromium")]
  [string]$Browser = "",
  [string]$Profile = "",
  [int]$Port = 9222,
  [string]$ExtraArgs = "",
  [string]$ExtensionId = "",
  [string]$Name = "com.just3bowls.pop_can",
  [string]$Launcher = "",
  [string]$ManifestDir = ""
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$hostName = "com.just3bowls.pop_can"
$scriptInvocation = "powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`""

if ($Classpath) {
  throw "classpath native host is supported on Linux/macOS only; use app-image on Windows"
}

function Get-CacheRoot {
  if (-not [string]::IsNullOrWhiteSpace($env:POP_CAN_CACHE_DIR)) {
    return $env:POP_CAN_CACHE_DIR
  }
  Join-Path $repo "server\.cache"
}

function Show-Usage {
  Write-Host @"
Usage:
  pop-can.ps1 setup [-SkipModel] [-Browser Chrome|Edge|Chromium]
                    [-Name HOST] [-ManifestDir PATH]
  pop-can.ps1 build [-Jar PATH] [-Dest DIR] [-Version VERSION]
  pop-can.ps1 model [-Dest DIR] [-Base URL]
  pop-can.ps1 debug [-Browser Chrome|Edge|Chromium] [-Profile DIR] [-Port 9222] [-ExtraArgs "..."]
  pop-can.ps1 register [-ExtensionId ID] [-Browser Chrome|Edge|Chromium]
                       [-Name HOST] [-Launcher PATH] [-ManifestDir PATH]
  pop-can.ps1 unregister [-Browser Chrome|Edge|Chromium] [-Name HOST] [-ManifestDir PATH]
  pop-can.ps1 help
"@
}

function Fail-Usage([string]$Message) {
  Write-Host $Message -ForegroundColor Red
  Show-Usage
  exit 2
}

function Get-DefaultExtensionId {
  if (-not [string]::IsNullOrWhiteSpace($env:POP_CAN_EXTENSION_ID)) {
    return $env:POP_CAN_EXTENSION_ID.Trim()
  }
  $sourceId = Join-Path $repo "client-extension\keys\extension-id.txt"
  if (Test-Path $sourceId) { return (Get-Content -Raw $sourceId).Trim() }
  $localId = Join-Path $PSScriptRoot "extension-id.txt"
  if (Test-Path $localId) { return (Get-Content -Raw $localId).Trim() }
  return ""
}

function Get-RegistryPath([string]$SelectedBrowser, [string]$SelectedName) {
  $vendorKey = switch ($SelectedBrowser) {
    "Chrome"   { "Software\Google\Chrome\NativeMessagingHosts" }
    "Edge"     { "Software\Microsoft\Edge\NativeMessagingHosts" }
    "Chromium" { "Software\Chromium\NativeMessagingHosts" }
    default    { throw "unsupported browser: $SelectedBrowser" }
  }
  "HKCU:\$vendorKey\$SelectedName"
}

function Invoke-Build {
  if ([string]::IsNullOrWhiteSpace($Jar)) {
    $Jar = Join-Path $repo "server\target\scala-3.7.1\pop-can-server.jar"
  }
  if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path $repo "server\target\native-host\windows\app-image"
  }
  if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = (Get-Content -Raw (Join-Path $repo "VERSION")).Trim()
  }
  if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "invalid VERSION: $Version"
  }
  if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Install JDK 21+ (it ships jpackage) and make sure its bin folder is on PATH."
  }
  if (-not (Test-Path $Jar)) {
    throw "fat jar not found: $Jar`nBuild it on the Linux side first: sbt --client 'server/assembly'"
  }

  $jarName = Split-Path -Leaf $Jar
  $packageInput = Join-Path ([System.IO.Path]::GetTempPath()) ("pop-can-windows-input-" + [System.Guid]::NewGuid())
  New-Item -ItemType Directory -Force -Path $packageInput | Out-Null
  Copy-Item -Path $Jar -Destination (Join-Path $packageInput $jarName)
  New-Item -ItemType Directory -Force -Path $Dest | Out-Null

  $appImage = Join-Path $Dest "PopCanHost"
  if (Test-Path $appImage) {
    Write-Host "removing previous app-image: $appImage"
    Remove-Item -Path $appImage -Recurse -Force
  }

  Write-Host "==> jpackage --type app-image"
  Write-Host "    jar  : $Jar"
  Write-Host "    dest : $Dest"
  try {
    & jpackage `
      --type app-image `
      --name PopCanHost `
      --app-version $Version `
      --vendor "Pop Can" `
      --description "Pop Can local server (native messaging host)" `
      --input $packageInput `
      --main-jar $jarName `
      --main-class com.just3bowls.server.bootstrap.ServerMain `
      --arguments "--native-host" `
      --java-options "-Dpop-can.native-host=true" `
      --dest $Dest
  } finally {
    if (Test-Path $packageInput) { Remove-Item -Recurse -Force $packageInput }
  }
  if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

  $cfg = Get-ChildItem -Path $Dest -Recurse -Filter "PopCanHost.cfg" -File | Select-Object -First 1
  if ($null -eq $cfg) { throw "built, but PopCanHost.cfg not found under $Dest" }
  $cfgText = Get-Content -Raw -Path $cfg.FullName
  if ($cfgText -notmatch "native-host=true") {
    throw "the built cfg does not carry -Dpop-can.native-host=true; the host would start in HTTP-only mode"
  }
  if (($cfgText -match "pop-can\.log\.dir") -or $cfgText.Contains($repo)) {
    throw "the built cfg leaks a workspace log path; packaged hosts must use the runtime user-data log directory"
  }

  $exe = Get-ChildItem -Path $Dest -Recurse -Filter "PopCanHost.exe" -File | Select-Object -First 1
  if ($null -eq $exe) { throw "built, but PopCanHost.exe not found under $Dest" }
  $hostJar = Get-ChildItem -Path $exe.Directory -Recurse -Filter "pop-can-server.jar" -File | Select-Object -First 1

  $modelDir = Join-Path (Get-CacheRoot) "models\multilingual-e5-small"
  if ($null -ne $hostJar) {
    if ((Test-Path (Join-Path $modelDir "model.onnx")) -and (Test-Path (Join-Path $modelDir "tokenizer.json"))) {
      $targetModels = Join-Path $hostJar.Directory "models"
      New-Item -ItemType Directory -Force -Path $targetModels | Out-Null
      $target = Join-Path $targetModels "multilingual-e5-small"
      if (Test-Path $target) { Remove-Item -Recurse -Force $target }
      Copy-Item -Recurse -Path $modelDir -Destination $target
      Write-Host "copied ONNX memory model -> $target"
    } elseif ($env:POP_CAN_REQUIRE_MEMORY_MODEL -eq "1") {
      throw "memory model missing; run: $scriptInvocation model"
    } else {
      Write-Warning "memory model missing; host will use BM25 + hash fallback"
    }
  }

  Write-Host ""
  Write-Host "done -> $($exe.FullName)"
  if ($null -ne $hostJar) { Write-Host "jar  -> $($hostJar.FullName)   (dev: replace this file, see README)" }
  Write-Host ""
  Write-Host "next: $scriptInvocation register"
}

function Invoke-Model([string]$ModelDest = "") {
  if ([string]::IsNullOrWhiteSpace($ModelDest)) {
    $ModelDest = Join-Path (Get-CacheRoot) "models\multilingual-e5-small"
  }
  New-Item -ItemType Directory -Force -Path $ModelDest | Out-Null
  $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
  $expectedPath = Join-Path $repo "server\memory-model.sha256"
  if (-not (Test-Path $expectedPath)) { throw "trusted memory model checksum file is missing: $expectedPath" }
  $expected = @{}
  foreach ($line in Get-Content -Path $expectedPath) {
    if ($line -match '^([0-9a-f]{64})\s+(.+)$') {
      $expected[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant()
    }
  }
  if ($expected.Count -ne 3) { throw "invalid memory model checksum file: $expectedPath" }

  function Get-ModelFile([string]$Remote, [string]$FileName) {
    $target = Join-Path $ModelDest $FileName
    if ((Test-Path $target) -and ((Get-Item $target).Length -gt 0)) {
      $existingHash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
      if ($existingHash -eq $expected[$FileName]) {
        Write-Host "keep $target"
        return
      }
      Remove-Item -Force $target
      Write-Warning "removed invalid cached model file: $target"
    }
    Write-Host "download $Remote"
    if ($null -ne $curl) {
      if ($env:POP_CAN_MODEL_TRUST_ENV -eq "1") {
        & $curl.Source --fail --location --retry 4 --retry-all-errors --output "$target.part" "$Base/$Remote"
      } else {
        & $curl.Source --noproxy '*' --fail --location --retry 4 --retry-all-errors --output "$target.part" "$Base/$Remote"
      }
      if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE" }
    } else {
      Invoke-WebRequest -Uri "$Base/$Remote" -OutFile "$target.part"
    }
    Move-Item -Force "$target.part" $target
    $actualHash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
    if ($actualHash -ne $expected[$FileName]) {
      Remove-Item -Force $target
      throw "checksum verification failed for $FileName"
    }
  }

  Get-ModelFile "onnx/model_quantized.onnx" "model.onnx"
  Get-ModelFile "tokenizer.json" "tokenizer.json"
  Get-ModelFile "config.json" "config.json"

  $lines = @()
  foreach ($fileName in @("model.onnx", "tokenizer.json", "config.json")) {
    $hash = (Get-FileHash -Algorithm SHA256 (Join-Path $ModelDest $fileName)).Hash.ToLowerInvariant()
    $lines += "$hash  $fileName"
  }
  Set-Content -Path (Join-Path $ModelDest "SHA256SUMS") -Value $lines -Encoding ascii
  Write-Host "done -> $ModelDest"
  Write-Host "next: $scriptInvocation build"
}

function Invoke-Setup {
  $selectedExtensionId = if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
    Get-DefaultExtensionId
  } else {
    $ExtensionId
  }
  if ([string]::IsNullOrWhiteSpace($selectedExtensionId)) {
    Fail-Usage "setup requires the fixed extension ID file"
  }
  if ($selectedExtensionId -notmatch '^[a-p]{32}$') {
    throw "invalid fixed extension ID: $selectedExtensionId"
  }

  $selectedJar = if ([string]::IsNullOrWhiteSpace($Jar)) {
    Join-Path $repo "server\target\scala-3.7.1\pop-can-server.jar"
  } else {
    $Jar
  }
  if (-not (Test-Path $selectedJar)) {
    throw "fat jar not found: $selectedJar`nBuild it on the Linux side first: sbt --client 'server/assembly'"
  }
  if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Install JDK 21+ and make sure its bin folder is on PATH."
  }

  $selectedDest = if ([string]::IsNullOrWhiteSpace($Dest)) {
    Join-Path $repo "server\target\native-host\windows\app-image"
  } else {
    $Dest
  }
  $appImage = Join-Path $selectedDest "PopCanHost"
  $appPrefix = [System.IO.Path]::GetFullPath($appImage).TrimEnd("\") + "\"
  $running = @(
    Get-Process -Name "PopCanHost" -ErrorAction SilentlyContinue |
      Where-Object {
        try {
          $path = [System.IO.Path]::GetFullPath($_.Path)
          $_.Path -and $path.StartsWith($appPrefix, [System.StringComparison]::OrdinalIgnoreCase)
        } catch {
          $false
        }
      }
  )
  if ($running.Count -gt 0) {
    throw "PopCanHost is running from $appImage. Close Pop Can pages or stop Local API, then retry setup."
  }

  if ($SkipModel) {
    Write-Warning "model preparation skipped; existing ONNX cache will be used if present, otherwise Memory will use BM25 + hash fallback"
    $env:POP_CAN_REQUIRE_MEMORY_MODEL = "0"
  } else {
    Invoke-Model
    $env:POP_CAN_REQUIRE_MEMORY_MODEL = "1"
  }
  Invoke-Build
  Invoke-Register

  Write-Host ""
  Write-Host "setup complete"
  Write-Host "extension id: $selectedExtensionId"
  Write-Host "reload the extension page or restart the browser before using the new host"
}

function Invoke-Debug {
  $candidates = @(
    @{ Name = "Chrome"; Paths = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
      ); RegKey = "Software\Google\Chrome\NativeMessagingHosts" },
    @{ Name = "Edge"; Paths = @(
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
      ); RegKey = "Software\Microsoft\Edge\NativeMessagingHosts" },
    @{ Name = "Chromium"; Paths = @(
        "$env:ProgramFiles\Chromium\Application\chrome.exe",
        "$env:LOCALAPPDATA\Chromium\Application\chrome.exe"
      ); RegKey = "Software\Chromium\NativeMessagingHosts" }
  )

  $picked = $null
  foreach ($candidate in $candidates) {
    if ($Browser -and $candidate.Name -ne $Browser) { continue }
    foreach ($candidatePath in $candidate.Paths) {
      if ($candidatePath -and (Test-Path $candidatePath)) {
        $picked = @{ Exe = $candidatePath; RegKey = $candidate.RegKey; Name = $candidate.Name }
        break
      }
    }
    if ($picked) { break }
  }
  if (-not $picked) {
    throw "No Chrome/Edge/Chromium found. Pass -Browser Chrome|Edge|Chromium or install one."
  }

  $regPath = "HKCU:\$($picked.RegKey)\$hostName"
  $manifest = $null
  if (Test-Path $regPath) {
    try { $manifest = (Get-Item -Path $regPath).GetValue("") } catch { $manifest = $null }
  }
  if (-not $manifest -or -not (Test-Path $manifest)) {
    Write-Host "Note: the native host is not installed for $($picked.Name). Install it once:" -ForegroundColor Yellow
    Write-Host "      $scriptInvocation register"
    if ($picked.Name -ne "Chrome") {
      Write-Host "      add: -Browser $($picked.Name)"
    }
  } else {
    Write-Host "native host manifest: $manifest"
  }

  Write-Host "browser : $($picked.Exe)"
  if ([string]::IsNullOrWhiteSpace($Profile)) {
    $profileBase = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($profileBase)) { $profileBase = $env:USERPROFILE }
    $Profile = Join-Path $profileBase "pop-can\chrome-debug-windows"
  }
  New-Item -ItemType Directory -Force -Path $Profile | Out-Null

  Write-Host "profile : $Profile"
  Write-Host "cdp     : http://127.0.0.1:$Port"
  Write-Host "note    : first run on this profile needs the extension loaded from client-extension\dev-extension"
  Write-Host "note    : quit $($picked.Name) completely before running this"

  $userDataArg = "--user-data-dir=$Profile"
  if ($Profile -match '\s') { $userDataArg = "--user-data-dir=`"$Profile`"" }
  $argList = @("--remote-debugging-port=$Port", $userDataArg, "--no-first-run", "--no-default-browser-check")
  if (-not [string]::IsNullOrWhiteSpace($ExtraArgs)) {
    $argList += $ExtraArgs.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
  }
  Start-Process -FilePath $picked.Exe -ArgumentList $argList

  $up = $false
  for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Milliseconds 500
    try {
      $browserVersion = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/version" -TimeoutSec 2
      if ($browserVersion) {
        $up = $true
        Write-Host "OK: CDP is up -> $($browserVersion.Browser)" -ForegroundColor Green
        break
      }
    } catch { }
  }
  if (-not $up) {
    Write-Host "FAILED: nothing is listening on 127.0.0.1:$Port." -ForegroundColor Red
    Write-Host "  1) quit all windows and background processes for $($picked.Name)"
    Write-Host "  2) make sure the profile is on a local disk"
    Write-Host "  3) inspect: Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue"
    exit 1
  }
}

function Invoke-Register {
  if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
    $ExtensionId = Get-DefaultExtensionId
  }
  if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
    Fail-Usage "register requires -ExtensionId or a fixed ID file"
  }
  $selectedBrowser = if ([string]::IsNullOrWhiteSpace($Browser)) { "Chrome" } else { $Browser }
  $selectedName = if ([string]::IsNullOrWhiteSpace($Name)) { $hostName } else { $Name }
  if ($ExtensionId -notmatch '^[a-p]{32}$') {
    Write-Warning "extension id '$ExtensionId' does not look like a Chrome extension id"
  }
  if ([string]::IsNullOrWhiteSpace($ManifestDir)) {
    $ManifestDir = Join-Path $repo "server\target\native-host\windows\manifests"
  }
  if ([string]::IsNullOrWhiteSpace($Launcher)) {
    $launcherRoot = Join-Path $repo "server\target\native-host\windows\app-image"
    $found = Get-ChildItem -Path $launcherRoot -Recurse -Filter "PopCanHost.exe" -File -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($null -eq $found) {
      throw "launcher not found under $launcherRoot - build it first: $scriptInvocation build"
    }
    $Launcher = $found.FullName
  }
  if (-not (Test-Path $Launcher)) {
    throw "native host launcher not found: $Launcher"
  }

  New-Item -ItemType Directory -Force -Path $ManifestDir | Out-Null
  $manifest = Join-Path $ManifestDir "$selectedName.json"
  $payload = [ordered]@{
    name = $selectedName
    description = "Pop Can local server (native messaging host)"
    path = $Launcher
    type = "stdio"
    allowed_origins = @("chrome-extension://$ExtensionId/")
  }
  $json = $payload | ConvertTo-Json -Depth 4
  [System.IO.File]::WriteAllText($manifest, $json, (New-Object System.Text.UTF8Encoding($false)))
  $regPath = Get-RegistryPath $selectedBrowser $selectedName
  New-Item -Path $regPath -Force | Out-Null
  Set-Item -Path $regPath -Value $manifest
  Write-Host "registered: $manifest -> $Launcher"
  Write-Host "registry: $regPath"
}

function Invoke-Unregister {
  $selectedBrowser = if ([string]::IsNullOrWhiteSpace($Browser)) { "Chrome" } else { $Browser }
  $selectedName = if ([string]::IsNullOrWhiteSpace($Name)) { $hostName } else { $Name }
  if ([string]::IsNullOrWhiteSpace($ManifestDir)) {
    $ManifestDir = Join-Path $repo "server\target\native-host\windows\manifests"
  }
  $regPath = Get-RegistryPath $selectedBrowser $selectedName
  if (Test-Path $regPath) {
    Remove-Item -Path $regPath -Force
    Write-Host "removed registry key: $regPath"
  } else {
    Write-Host "registry key not present: $regPath"
  }
  $manifest = Join-Path $ManifestDir "$selectedName.json"
  if (Test-Path $manifest) {
    Remove-Item -Path $manifest -Force
    Write-Host "removed manifest: $manifest"
  } else {
    Write-Host "manifest not present: $manifest"
  }
  Write-Host "restart $selectedBrowser to apply."
}

switch ($Command.ToLowerInvariant()) {
  "setup" { Invoke-Setup }
  "build" { Invoke-Build }
  "model" { Invoke-Model $Dest }
  "debug" { Invoke-Debug }
  "register" { Invoke-Register }
  "unregister" { Invoke-Unregister }
  "help" { Show-Usage }
}
