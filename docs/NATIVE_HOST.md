# Native Host

The Pop Can native host is the JVM process behind the browser extension. It provides:

- Native messaging over stdio to the extension.
- The loopback OpenAI-compatible HTTP server on `127.0.0.1:8080`.
- The single local Runtime owner for Memory and server controls.

Chrome starts the host through `chrome.runtime.connectNative("com.just3bowls.pop_can")`. Closing the
last connected extension page ends the native port and shuts down the host.

## User Installation

Release packages are the recommended path for non-developers. Download the single complete archive
for your platform from the repository's **Releases** page; it contains both the extension and the
native host.

### 1. Run setup

The registration manifest must be outside the extension because Chrome only reads native host
manifests from operating-system locations. Setup uses the fixed production extension ID, registers
the host, stages the latest signed Provider Pack, copies the extension path, and opens
`chrome://extensions`.

Linux:

```bash
./host.sh setup
```

macOS:

```bash
./host.sh setup
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\host.ps1 setup
```

Provider Pack staging is best-effort and never blocks host registration. Use
`--provider-pack <file>` on Unix/macOS or `-ProviderPack <file>` on Windows to stage a local signed
Pack. The extension still verifies signature, schema and extension compatibility before applying it.

### 2. Load the extension

1. Enable **Developer mode** in `chrome://extensions`.
2. Choose **Load unpacked** and select the copied `extension/` path.
3. Restart the browser.

The manifest public key fixes the extension ID, so moving or upgrading the archive does not change
the extension storage identity.

### 3. Unregister

To remove the native messaging registration:

```bash
./host.sh unregister
```

```powershell
powershell -ExecutionPolicy Bypass -File .\host.ps1 unregister
```

Source-checkout development uses the platform scripts under
`server/scripts/` instead; Unix uses `.sh` and Windows uses `.ps1`.

### 4. Start the local API

Open the Pop Can extension page:

1. Open **Config**.
2. Go to **API Keys**.
3. Turn on **Local API**.

The host remains alive while a Pop Can extension page is connected. **Local API** controls only the
HTTP face; turning it off does not revoke API keys or erase Memory.

## Registration Locations

Chrome reads native host manifests from:

- macOS: `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/<name>.json`
- Linux: `~/.config/google-chrome/NativeMessagingHosts/<name>.json`
- Chromium: `~/.config/chromium/NativeMessagingHosts/<name>.json`
- Windows: `HKCU\Software\Google\Chrome\NativeMessagingHosts\<name>` points to the manifest JSON.

The default host name is `com.just3bowls.pop_can`.

If the browser is started with a custom `--user-data-dir`, Linux may look for the manifest inside
that profile. Pass `--profile <user-data-dir>` to the release `host.sh setup`, or to
`server/scripts/pop-can.sh register` in a source checkout.

## Troubleshooting

### Native host is not registered

Run `host setup` again, then fully restart the browser.

### `Access to the specified native messaging host is forbidden`

The manifest points to a different extension ID. Run `host setup` again.

### `Port in use`

Another Pop Can host or another process already owns `127.0.0.1:8080`. Close the other Pop Can
extension page first. If no other panel is active, remove a stale process:

```bash
pkill -f com.just3bowls.server.bootstrap.ServerMain
```

### The host starts but the panel disconnects

Check the host log:

- Linux: `${XDG_STATE_HOME:-~/.local/state}/pop-can/logs/host.log`
- macOS: `~/Library/Logs/PopCan/host.log`
- Windows: `%LOCALAPPDATA%\PopCan\logs\host.log`

The data directory, used for Memory rather than logs, is:

- Linux: `${XDG_DATA_HOME:-~/.local/share}/pop-can`
- macOS: `~/Library/Application Support/PopCan`
- Windows: `%LOCALAPPDATA%\PopCan`

The launcher can also redirect logs with:

```text
--java-options "-Dpop-can.log.dir=<directory>"
```

`POP_CAN_LOG_DIR` can override the platform log directory.

### Windows Smart App Control blocks the host

An unsigned jpackage launcher can be blocked before the JVM starts. The extension then reports the
generic native-messaging error even though the registry and manifest are valid.

Check Smart App Control policy:

```powershell
Get-ItemProperty `
  "HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy" `
  -Name VerifiedAndReputablePolicyState
```

`1` means enforcement. Also check Code Integrity events:

```powershell
Get-WinEvent `
  -LogName "Microsoft-Windows-CodeIntegrity/Operational" `
  -MaxEvents 50 |
Where-Object { $_.Id -in 3033, 3077 } |
Select-Object TimeCreated, Id, Message |
Format-List
```

If the blocked path is `PopCanHost.exe` and
`Get-AuthenticodeSignature` reports `NotSigned`, Windows security is the cause. The release fix is
Authenticode signing with a trusted certificate and timestamp. Use a test VM with Smart App Control
disabled only as a development workaround; ordinary users should not be asked to disable it.

## Development Host

Generate the classpath launcher metadata:

```bash
sbt --client 'server/writeNativeHostClasspath'
```

Compile server changes:

```bash
sbt --client 'server/compile'
```

Linux:

```bash
server/scripts/pop-can.sh register --classpath
```

macOS:

```bash
server/scripts/pop-can.sh register --classpath
```

Reconnect or restart the extension page after server changes so the native port starts the fresh
classpath.

## Packaged Host

### Source checkout setup

The source CLI provides one repeatable setup command for model preparation, app-image build, and
native messaging registration.

Linux/macOS:

```bash
server/scripts/pop-can.sh setup
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 setup
```

The default setup requires a valid ONNX Memory model and stops if preparation fails. Use
`--skip-model` / `-SkipModel` only when intentionally accepting BM25 plus hash fallback.
Registration options such as browser, profile, host name, and manifest directory are accepted by
`setup` on the respective platform.

### Linux

The lower-level commands remain available for debugging:

```bash
sbt --client 'server/assembly'
server/scripts/pop-can.sh build
server/scripts/pop-can.sh register
```

### macOS

```bash
sbt --client 'server/assembly'
server/scripts/pop-can.sh build
server/scripts/pop-can.sh register
```

### Windows

Build the platform-independent jar on Linux:

```bash
sbt --client 'server/assembly'
```

Run the jpackage build on Windows from the shared checkout:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 build
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 register
```

Do not run sbt or Node against the shared checkout from Windows. Scala and Vite build caches contain
machine-specific paths and concurrent builds can corrupt each other.

## Memory Model

The optional local Memory embedding model is not stored in Git. Prepare it before packaging when you
want ONNX embedding enabled:

```bash
Linux:

```bash
bash server/scripts/pop-can.sh model
```

macOS:

```bash
bash server/scripts/pop-can.sh model
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 model
```

If the model is missing, Memory falls back to BM25 plus a deterministic hash embedding.
The default model cache is `server/.cache/models/multilingual-e5-small`.
Set `POP_CAN_CACHE_DIR` to override the project cache root.

## Packaging

Build the native-host app image:

```bash
server/scripts/pop-can.sh build
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 build
```

Create complete platform archives with checksums:

```bash
release/package.sh
```

```powershell
powershell -ExecutionPolicy Bypass -File release\package.ps1
```

Each archive contains `extension/`, `native-host/`, the platform registration scripts, and the
user-facing documentation. Internal module build directories and `server/scripts/` are never
included.

See [`release/README.md`](../release/README.md) for packaging and
[`server/README.md`](../server/README.md) for native-host tooling.

macOS release archives are unsigned. Public distribution requires code signing and notarization.
Windows archives are unsigned and may be blocked by Smart App Control. Native installers
(`deb`, `rpm`, `pkg`, `dmg`, `msi`, or `exe`) are not implemented in the current archive-only
release flow.

## Runtime Model

The first host to acquire the user data directory's runtime lock is the Runtime leader. Other hosts
connect to that leader through an authenticated loopback endpoint and proxy Memory or server
controls.

The leader verifies the previous process identity before replacing a stale endpoint. If ownership
cannot be proven, the host reports an error instead of terminating an unrelated process.

Only one HTTP server may bind `127.0.0.1:8080` on a machine. The **Local API** switch controls that
HTTP service without affecting the Runtime leader or Memory.

## Files

- `client-extension/dev-extension/`: Vite/CRX development extension.
- `client-extension/dist/`: Vite/CRX production extension.
- `server/target/native-host/{linux,macos}/classpath/`: source classpath launchers.
- `server/target/native-host/{linux,windows,macos}/app-image/`: jpackage app images.
- `server/.cache/models/`: optional local embedding model cache.
- `server/.cache/tools/`: pinned build tools such as Syft.
- Platform log directories: native-host logs, outside the repository.

Generated directories, logs, model files, and app images are ignored by Git.

## Security

The host can drive a signed-in browser through the extension. Keep the HTTP server on loopback, use
a trusted browser profile, and review `SECURITY.md` before deployment.
