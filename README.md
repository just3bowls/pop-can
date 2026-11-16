<p align="center">
  <img src="client-extension/assets/icons/pop-can-minimal-128.png" width="96" alt="Pop Can icon">
</p>

<h1 align="center">Pop Can</h1>

<p align="center">
  Use the AI websites you are already signed in to from your own tools through one local,
  OpenAI-compatible API.
</p>

Pop Can is a browser extension plus local native host. It drives supported web AI providers
through Chrome DevTools Protocol and exposes a loopback-only OpenAI-compatible endpoint to
clients such as opencode, agent frameworks, or any standard OpenAI SDK.

Pop Can is not affiliated with any AI provider. It automates the provider's web interface
using your existing browser session, not the provider's official API.

## Features

- Chat with supported web AI providers from the Pop Can extension page.
- Bind a provider-page conversation to a Pop Can session and continue it from either side.
- Expose `/v1/models` and `/v1/chat/completions` on `127.0.0.1:8080`.
- Stream OpenAI-compatible SSE responses, including reasoning and tool calls.
- Configure multiple API keys, provider routing, weighted selection, and failover.
- Use the page AI's own tools or explicitly enable Pop Can's built-in browser tools.
- Keep conversations and optional local Memory on your machine.

## Architecture

```text
OpenAI-compatible client
        |
        | HTTP / SSE on 127.0.0.1:8080
        v
Pop Can local server
        |
        | native messaging over stdio
        v
Pop Can browser extension (background + UI)
        |
        | Chrome DevTools Protocol
        v
Provider web page running in your signed-in browser
```

The local server only forwards requests. Authentication, provider routing, prompt construction,
response parsing, and browser automation are handled by the extension.

## Requirements

- Google Chrome, Chromium, or Microsoft Edge.
- A supported provider account already signed in inside that browser.
- Linux, Windows, or macOS for the documented release workflow.
- Source builds require JDK 21, sbt, Node.js 20+, and npm.

## Quick Start

### 1. Download and extract the platform bundle

Download the complete Linux, Windows, or macOS archive from the repository's **Releases** page and
extract it. The bundle contains both `extension/` and `native-host/`.

### 2. Run setup

Setup registers the native host with the fixed extension ID, stages the latest signed Provider Pack,
copies the extension path, and opens `chrome://extensions`. Provider staging is best-effort: a
network failure does not stop setup.

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

To stage a signed Provider Pack from a local file instead:

```bash
./host.sh setup --provider-pack /path/to/provider-pack-v22.popcan.json
```

```powershell
powershell -ExecutionPolicy Bypass -File .\host.ps1 setup -ProviderPack .\provider-pack-v22.popcan.json
```

### 3. Load the extension

1. In `chrome://extensions`, enable **Developer mode**.
2. Choose **Load unpacked** and select the copied `extension/` path.
3. Restart the browser.

The production manifest contains a fixed public key, so the unpacked extension keeps the same ID
when the archive is moved or upgraded.

To unregister later:

```bash
./host.sh unregister
```

macOS release archives are unsigned. Gatekeeper may require manual approval until signing and
notarization are added.

Windows archives are also unsigned. Smart App Control may block the native host until release
binaries are Authenticode-signed; see **Troubleshooting** before changing Windows security settings.

### 4. Start the local API

Open the Pop Can extension page:

1. Open **Config**.
2. Go to **API Keys**.
3. Turn on **Local API**.

The server listens on `http://127.0.0.1:8080/v1` while it is enabled. Closing the last Pop Can
extension page also shuts down the native host.

### 5. Enable a provider

1. Open **Config -> Providers**. Setup normally installs and verifies the staged Provider Pack when
   the extension starts.
2. If no rules are installed, click **Import** and choose **Download rules** or import an offline
   `.popcan.json` file.
3. Sign in to a provider website in the same browser.
4. Enable the provider, then grant access to its origin.
5. Open **Config -> API Keys** and create an API key with the provider in its routing list.

Provider rules are distributed separately from the application. Setup stages the latest signed Pack
without granting online update permission; automatic updates remain opt-in. Offline or air-gapped
installations can use the `--provider-pack` setup option or **Import…**.

You can now use the Chat page or send requests from an external OpenAI-compatible client.

## Use an External Client

Create an API key in **Config -> API Keys**, then configure your client with:

```text
Base URL: http://127.0.0.1:8080/v1
API key:  <key created in Config -> API Keys>
Model:    pop-can
```

Streaming request example:

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <POP_CAN_API_KEY>" \
  -d '{
    "model": "pop-can",
    "stream": true,
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

## Supported Providers

The downloadable Provider Pack currently includes DeepSeek, Doubao, GML, Kimi, LongCat, Mimo,
Qianwen, Wenxin, and Yuanbao. The application archive does not contain these integrations.

Provider integrations depend on website structure and network payloads. A provider may break after
an upstream UI or API change. Presets are local configuration files and can be updated without
changing the core application.

See [`docs/PROVIDER_PACK.md`](docs/PROVIDER_PACK.md) for online, offline, copy, and update details.

## Security and Limitations

Pop Can controls highly privileged browser sessions. Read [`SECURITY.md`](SECURITY.md) before use.

- The extension requests `debugger`, `tabs`, `tabGroups`, `scripting`, and `nativeMessaging`
  permissions.
- Provider Pack access to `just3bowls.github.io` is optional and requested only when the user clicks
  download or enables automatic updates.
- **Local API**, generated keys, and Memory data are stored unencrypted on your machine.
- The local HTTP endpoint has no TLS and is intended for loopback use only.
- Anyone with access to your OS account, browser profile, extension storage, API key, or Memory
  database can access the associated data.
- Provider terms of service and account automation policies still apply. Use your own accounts
  and review provider rules before automating a site.
- Pop Can is not a sandbox and does not bypass provider authentication, quotas, or account
  restrictions.

## Troubleshooting

- **Native host is not registered**: Re-run the matching setup command, then restart the browser:
  `host.sh setup` / `host.ps1 setup` for a release bundle, or `pop-can.sh setup` /
  `pop-can.ps1 setup` for a source checkout.
- **No provider rules installed**: Re-run `host setup` with network access, pass `--provider-pack`
  / `-ProviderPack` with a signed file, or use **Config -> Providers -> Import**.
- **`Access to the specified native messaging host is forbidden`**: The host manifest's
  `allowed_origins` contains a different extension ID.
- **Windows reports `Error when communicating with the native messaging host`**: check whether
  Smart App Control or Code Integrity blocked the unsigned `PopCanHost.exe`. See
  [`docs/NATIVE_HOST.md`](docs/NATIVE_HOST.md#windows-smart-app-control-blocks-the-host). Do not
  disable Smart App Control solely to run an unsigned development build.
- **`Port in use`**: Another Pop Can host or process is already using `127.0.0.1:8080`.
- **Provider page no longer works**: Update Pop Can or inspect the provider preset and parser
  fixture for a changed page structure.
- **External client times out**: Confirm the extension page is open, **Local API** is enabled, the
  provider is enabled, and its browser session is signed in.

Detailed platform and packaging guidance is in [`docs/NATIVE_HOST.md`](docs/NATIVE_HOST.md).

## Development

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the full development workflow.
Release and packaging reference: [`release/README.md`](release/README.md).

Prepare the Memory model, build the native host, and register it from a source checkout with one
repeatable command.

Linux/macOS:

```bash
server/scripts/pop-can.sh setup
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 setup
```

`setup` requires a valid ONNX Memory model by default. Use `--skip-model` / `-SkipModel` only when
intentionally accepting BM25 plus hash fallback. `unregister` remains a separate command.

Quick checks:

```bash
sbt --client 'server/test'
sbt --client 'extension/test'
sbt --client 'extension/fastLinkJS'
sbt --client 'scalafmtCheckAll'
npm run presets:check --prefix client-extension
node client-extension/scripts/check-architecture.mjs
node client-extension/scripts/check-linked-js.mjs
```

## License

Pop Can is released under the [MIT License](LICENSE).

Vulnerability reporting and the security model are documented in [`SECURITY.md`](SECURITY.md).
