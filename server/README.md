# Pop Can Server

The Pop Can server is the JVM native host and a thin OpenAI-compatible adapter. It forwards HTTP
requests to the browser extension through native messaging and returns the extension's JSON or SSE
response.

The server does not call AI providers directly. Authentication, provider routing, prompt
construction, response parsing, and CDP automation are handled by the extension.

## Data Path

```text
OpenAI-compatible client
        |
        | HTTP / SSE
        v
127.0.0.1:8080
        |
        | native messaging over stdio
        v
Pop Can extension
        |
        | Chrome DevTools Protocol
        v
Provider web page
```

The native host is started by Chrome through `chrome.runtime.connectNative`. Closing the last
connected extension page ends the native port and shuts down the host.

## HTTP API

The HTTP face is enabled from **Config -> API Keys -> Local API** in the extension.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/models` | Returns the model list provided by the extension |
| `POST` | `/v1/chat/completions` | Forwards the request body and Authorization header to the extension |

The default binding is `127.0.0.1:8080`. `--bind`, `--port`, `POP_CAN_BIND`, and `POP_CAN_PORT`
can override the defaults.

The request body limit is 20 MB. Streaming responses use the single OpenAI SSE encoder in the
extension:

- Every non-terminal event is encoded as `data: <json>\n\n`.
- The stream ends with `data: [DONE]\n\n`.
- The server does not parse or reconstruct the OpenAI event payload.

The server intentionally returns an error if the browser worker is unavailable or if no response
head arrives within 11 minutes.

## Native Messaging

The native messaging wire format is:

```text
4-byte little-endian length
UTF-8 JSON payload
```

Stdout is reserved for framed messages. All logs go to stderr or the configured log file.

Server-to-extension messages include:

- `client_request`
- `client_request_chunk`
- `server_ready`
- `server_stopped`
- `server_fatal`
- `runtime_retry`
- `runtime_fatal`
- `memory_response`

Extension-to-server messages include:

- `client_response_head`
- `client_response_chunk`
- `client_response_end`
- `client_cancel`
- `memory_request`
- `memory_extract_result`
- `server_start`
- `server_stop`
- `runtime_probe`

Request bodies are inline in `client_request` when they fit in one frame. Larger bodies are sent
through ordered `client_request_chunk` messages.

## Runtime Ownership

Only one native-host JVM owns the local Runtime and Memory files for a user data directory. The
leader publishes a private loopback endpoint and requires an authenticated
`runtime_probe`/`runtime_ready` handshake before another host can proxy Memory or server controls.

The HTTP service is a child service of the native host. A port bind failure marks the Local API as
unavailable but does not destroy the Memory runtime or kill an otherwise healthy host.

Memory is not exposed through the 8080 HTTP API. External OpenAI clients never trigger Memory recall
or extraction.

## Package Layout

```text
server/bootstrap/   Application entry, options, logging, and lifecycle assembly
server/http/        OpenAI routes, proxying, and HTTP child-service control
server/native/      Native messaging frames, bridge, stdio transport, and fatal notices
server/runtime/     Runtime endpoint, leader, IPC server, proxy, and command handling
server/../memory/   SQLite-backed Memory domain and local retrieval runtime
```

The Memory package remains a separate JVM domain package rather than an HTTP transport concern.

## Native Host Scripts

The source-checkout management CLI lives at `server/scripts/pop-can.sh` on Unix/macOS and
`server/scripts/pop-can.ps1` on Windows. `register` reads the fixed extension ID from
`client-extension/keys/extension-id.txt` (or the copied `.pop-can/extension-id.txt` in a release
bundle); manual ID overrides are only for development troubleshooting. Its normal outputs are:

```text
target/native-host/{platform}/classpath/   source classpath launcher
target/native-host/{platform}/app-image/   packaged app image
.cache/models/                      Memory embedding model cache
.cache/tools/                       pinned build tools such as Syft
```

Native-host logs use the platform log directory:

```text
Linux:   ${XDG_STATE_HOME:-~/.local/state}/pop-can/logs/host.log
macOS:   ~/Library/Logs/PopCan/host.log
Windows: %LOCALAPPDATA%\PopCan\logs\host.log
```

Prepare the Memory model, build the app image, and register the host with one repeatable command.

Linux/macOS:

```bash
server/scripts/pop-can.sh setup
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 setup
```

`setup` validates the fixed extension ID and requires a valid ONNX Memory model by default. Cached
model files are reused only when their SHA256 hashes match. Use `--skip-model` / `-SkipModel` only
when intentionally accepting BM25 plus hash fallback. Windows setup requires the
platform-independent JAR to exist already; build it on Linux with
`sbt --client 'server/assembly'`.

Debug browser:

```bash
server/scripts/pop-can.sh debug
```

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 debug
```

### Linux

The lower-level commands remain available for development and debugging:

```bash
# Classpath launcher
sbt --client 'server/writeNativeHostClasspath'
sbt --client 'server/compile'
server/scripts/pop-can.sh register --classpath

# App-image host
sbt --client 'server/assembly'
server/scripts/pop-can.sh build
server/scripts/pop-can.sh register

# Model and registration cleanup
server/scripts/pop-can.sh model
server/scripts/pop-can.sh unregister
```

### macOS

```bash
sbt --client 'server/writeNativeHostClasspath'
sbt --client 'server/compile'
server/scripts/pop-can.sh register --classpath

sbt --client 'server/assembly'
server/scripts/pop-can.sh build
server/scripts/pop-can.sh register

server/scripts/pop-can.sh model
server/scripts/pop-can.sh unregister
```

### Windows

Build the JAR on Linux first, then run the Windows commands from Windows. Do not run sbt or Node
against a shared checkout from Windows.

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 setup

# Lower-level commands
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 build
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 register
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 model
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 unregister
```

To test a non-standard unpacked build, pass the ID positionally on Unix/macOS or use
`-ExtensionId` on Windows. Standard development and release builds should not need either override.

For complete platform archives, use `release/{linux,macos,windows}`. See
[`NATIVE_HOST.md`](../docs/NATIVE_HOST.md) for registration layouts, logging, and troubleshooting.

## Build and Test

Run the server tests:

```bash
sbt --client 'server/test'
```

Build the development native host classpath:

```bash
sbt --client 'server/writeNativeHostClasspath'
```

The classpath is written to `target/native-host/<platform>/classpath/classpath.txt`.

Build the fat jar:

```bash
sbt --client 'server/assembly'
```

See [NATIVE_HOST.md](../docs/NATIVE_HOST.md) for host registration and platform packaging.

## Security

- Keep the HTTP server bound to loopback.
- Do not expose `127.0.0.1:8080` to an untrusted network.
- API keys, browser sessions, and Memory are local trust boundaries.
- Review [SECURITY.md](../SECURITY.md) before deploying or exposing the server.
