<#
.SYNOPSIS
  Pop Can release-bundle native messaging registration CLI for Windows.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [string]$Action = "help",
  [string]$ExtensionId = "",
  [ValidateSet("Chrome", "Edge", "Chromium")]
  [string]$Browser = "Chrome",
  [string]$Name = "com.just3bowls.pop_can",
  [string]$ProviderPack = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$core = Join-Path $root ".pop-can\pop-can.ps1"
$launcher = Join-Path $root "native-host\PopCanHost\PopCanHost.exe"
$manifestDir = Join-Path $root "native-host\manifests"

function Show-Usage {
  Write-Host @"
Usage:
  .\host.ps1 setup [-ProviderPack PATH] [-Browser Chrome|Edge|Chromium] [-Name HOST]
  .\host.ps1 register [-ExtensionId ID] [-Browser Chrome|Edge|Chromium] [-Name HOST]
  .\host.ps1 unregister [-Browser Chrome|Edge|Chromium] [-Name HOST]
  .\host.ps1 help
"@
}

function Fail-Usage([string]$Message) {
  Write-Host $Message -ForegroundColor Red
  Show-Usage
  exit 2
}

function Get-FixedExtensionId {
  if (-not [string]::IsNullOrWhiteSpace($env:POP_CAN_EXTENSION_ID)) {
    return $env:POP_CAN_EXTENSION_ID.Trim()
  }
  $path = Join-Path $root ".pop-can\extension-id.txt"
  if (Test-Path $path) { return (Get-Content -Raw $path).Trim() }
  return ""
}

function Open-BrowserExtensions {
  if ($env:POP_CAN_SKIP_BROWSER_OPEN -eq "1") {
    Write-Host "Browser launch skipped (POP_CAN_SKIP_BROWSER_OPEN=1)."
    return
  }
  $paths = switch ($Browser) {
    "Edge" { @("$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe", "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe") }
    "Chromium" { @("$env:ProgramFiles\Chromium\Application\chrome.exe", "$env:LOCALAPPDATA\Chromium\Application\chrome.exe") }
    default { @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe", "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") }
  }
  $exe = $paths | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
  if ($exe) {
    Start-Process -FilePath $exe -ArgumentList "chrome://extensions"
    Write-Host "Opened browser extensions page."
  } else {
    Write-Host "Native host registered. Open chrome://extensions manually and choose Load unpacked." -ForegroundColor Yellow
  }
}

function Show-SetupInstructions {
  $extensionPath = Join-Path $root "extension"
  try {
    Set-Clipboard -Value $extensionPath
    Write-Host "Extension path copied to clipboard: $extensionPath"
  } catch {
    Write-Host "Extension path: $extensionPath"
  }
  Open-BrowserExtensions
  Write-Host "In chrome://extensions, enable Developer mode, choose Load unpacked, and select the copied path."
  Write-Host "The fixed extension ID is: $(Get-FixedExtensionId)"
}

function Install-ProviderPackBootstrap {
  if ($env:POP_CAN_SKIP_PROVIDER_PACK -eq "1") {
    Write-Host "Provider Pack staging skipped (POP_CAN_SKIP_PROVIDER_PACK=1)."
    return
  }

  $bootstrapDir = Join-Path $root "extension\bootstrap"
  $target = Join-Path $bootstrapDir "provider-pack.popcan.json"
  $checksum = Join-Path $bootstrapDir "provider-pack.sha256"
  $packPart = "$target.part"
  $checksumPart = "$checksum.part"
  try {
    New-Item -ItemType Directory -Force -Path $bootstrapDir | Out-Null
    Remove-Item -Force -ErrorAction SilentlyContinue $packPart, $checksumPart

    if (-not [string]::IsNullOrWhiteSpace($ProviderPack)) {
      if (-not (Test-Path -LiteralPath $ProviderPack -PathType Leaf)) {
        throw "file not found: $ProviderPack"
      }
      Copy-Item -LiteralPath $ProviderPack -Destination $packPart
    } else {
      $url = $env:POP_CAN_PROVIDER_PACK_URL
      if ([string]::IsNullOrWhiteSpace($url)) {
        $urlFile = Join-Path $root ".pop-can\provider-pack-url.txt"
        if (Test-Path -LiteralPath $urlFile) {
          $url = (Get-Content -Raw -LiteralPath $urlFile).Trim()
        }
      }
      if ([string]::IsNullOrWhiteSpace($url)) {
        throw "download URL is missing"
      }
      Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Uri $url -OutFile $packPart
    }

    $file = Get-Item -LiteralPath $packPart
    if ($file.Length -le 0) { throw "downloaded file is empty" }
    if ($file.Length -gt 2097152) { throw "file exceeds 2 MB" }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $packPart).Hash.ToLowerInvariant()
    Set-Content -LiteralPath $checksumPart -Value $hash -Encoding ascii
    Move-Item -Force -LiteralPath $packPart -Destination $target
    Move-Item -Force -LiteralPath $checksumPart -Destination $checksum
    Write-Host "Provider Pack staged. It will be verified when the extension starts."
  } catch {
    Remove-Item -Force -ErrorAction SilentlyContinue $packPart, $checksumPart
    Write-Host "Provider Pack not staged: $($_.Exception.Message). Setup will continue; use Config -> Providers -> Import instead." -ForegroundColor Yellow
  }
}

switch ($Action.ToLowerInvariant()) {
  "help" {
    Show-Usage
  }
  "setup" {
    $id = Get-FixedExtensionId
    if ([string]::IsNullOrWhiteSpace($id)) { Fail-Usage "fixed extension ID file is missing" }
    & $core register -ExtensionId $id -Browser $Browser -Name $Name -Launcher $launcher -ManifestDir $manifestDir
    Install-ProviderPackBootstrap
    Show-SetupInstructions
  }
  "register" {
    if (-not [string]::IsNullOrWhiteSpace($ProviderPack)) {
      Fail-Usage "-ProviderPack is only valid with setup"
    }
    if ([string]::IsNullOrWhiteSpace($ExtensionId)) {
      $ExtensionId = Get-FixedExtensionId
    }
    if ([string]::IsNullOrWhiteSpace($ExtensionId)) { Fail-Usage "register requires -ExtensionId or a fixed ID file" }
    & $core register -ExtensionId $ExtensionId -Browser $Browser -Name $Name -Launcher $launcher -ManifestDir $manifestDir
  }
  "unregister" {
    if (-not [string]::IsNullOrWhiteSpace($ProviderPack)) {
      Fail-Usage "-ProviderPack is only valid with setup"
    }
    & $core unregister -Browser $Browser -Name $Name -ManifestDir $manifestDir
  }
  default {
    Fail-Usage "unknown command: $Action"
  }
}
