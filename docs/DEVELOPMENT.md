# Development

This guide covers local development, debugging, verification, and release builds for Pop Can.

For end-user installation, see the root [README](../README.md). For native host installation and
packaging details, see [NATIVE_HOST.md](NATIVE_HOST.md).

## Prerequisites

- JDK 21
- sbt 1.11+
- Node.js 20+ and npm
- Google Chrome, Chromium, or Microsoft Edge
- A supported provider account signed in inside the browser profile used for development

## Repository Layout

```text
api/                  Shared JVM/JavaScript protocol and pure logic
server/               Local server, native messaging, runtime IPC, and JVM Memory
client-extension/     Browser extension and plugin UI
docs/NATIVE_HOST.md   Native host installation, registration, and packaging
release/              Release checks, packaging scripts, and generated artifacts/
server/scripts/       Native-host build, registration, browser debug, and model scripts
docs/                 Public development documentation
```

The extension source is organized by responsibility:

```text
background --+
content -----+--> model --> facade
ui ----------+
```

- `background`: service worker, provider automation, CDP, native messaging, and runtime state.
- `content`: provider-page content scripts.
- `ui`: Laminar views and panel components.
- `model`: shared domain behavior and state.
- `facade`: typed declarations for browser and JavaScript APIs.

The load-bearing boundary is enforced by:

```bash
node client-extension/scripts/check-architecture.mjs
```

## Standard Checks

Run these before committing:

```bash
sbt --client 'server/test'
sbt --client 'extension/test'
sbt --client 'extension/fastLinkJS'
sbt --client 'scalafmtCheckAll'
node client-extension/scripts/check-architecture.mjs
node client-extension/scripts/check-linked-js.mjs
git diff --check
```

Do not use a new sbt server when an existing sbt client service is already available.

## Branch Workflow

- `master` is the stable release branch. Only release PRs from `develop` merge into it.
- `develop` is the protected integration branch for ordinary development.
- Work branches use `<type>/<kebab-case-summary>`, such as `feat/provider-routing`,
  `fix/session-rename`, `refactor/json-envelope`, or `ci/audit-runtime-dependencies`.
- Open pull requests against `develop`. Pull-request checks must pass before merging.
- Use rebase-and-merge to keep `develop` linear while preserving clean logical commits.
- Merged work branches are deleted automatically and must not remain as long-lived branches.
- Dependabot targets `develop`; major toolchain upgrades are tracked separately from routine
  minor/patch updates.
- Published tags are immutable. A new build after publication uses a new version and tag.

## Extension Development

Install dependencies:

```bash
cd client-extension
npm ci
```

Start the Vite development server:

```bash
npm run dev
```

Generate the Scala.js development output:

```bash
sbt --client 'extension/fastLinkJS'
```

Load the Vite-generated `client-extension/dev-extension` directory from `chrome://extensions` using
**Load unpacked**.

### Fixed Extension Identity

The manifest key is committed as:

```text
client-extension/keys/extension-public-key.base64
client-extension/keys/extension-id.txt
```

The matching private key lives outside the repository at
`~/.config/pop-can/extension-private-key.pem`. Back it up securely and never commit or rotate it
without an explicit extension-ID migration plan. CI runs:

```bash
node release/check-extension-id.mjs
```

This ID is stable across development/production builds and archive paths. Host registration reads it
by default, so developers run `server/scripts/pop-can.sh register --classpath` without copying an ID.
The first load after introducing the fixed key is one final identity change; storage created under
the old path-derived development ID is intentionally not migrated before the public release.

### Debug Browser

Linux:

```bash
bash server/scripts/pop-can.sh debug
```

macOS:

```bash
bash server/scripts/pop-can.sh debug
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 debug
```

The debug browser scripts expose CDP on port `9222` by default. The Windows script uses a dedicated
local profile because recent Chrome versions ignore remote debugging on the default profile.
Fully quit the target browser before starting a debug instance.

The debugging port provides powerful local control over the browser. Close the debug browser when
you are finished.

## Native Host Development

Generate the native-host classpath metadata once after dependency changes:

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

Windows uses the packaged app-image and replaces its server jar for development; there is no
classpath-based Windows host launcher.

After changing server code, restart the Pop Can host by closing and reopening the extension page or
by using **Stop** and **Start** in **Config -> API Keys**.

## Production-style Builds

These commands populate each module's own build directory. End-user archives are assembled
separately by the platform `release/{linux,macos,windows}` package scripts described below.

### Native Host Setup

From a source checkout, prepare the model, build the app image, and register the native host with
one repeatable command:

Linux/macOS:

```bash
server/scripts/pop-can.sh setup
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 setup
```

`setup` validates the fixed extension ID, requires a valid ONNX Memory model, builds the host, and
registers it. Re-running it is safe: valid model files are reused, the app image is rebuilt, and
the native messaging registration is updated.

Use `--skip-model` on Linux/macOS or `-SkipModel` on Windows only for development or offline
work. The host will use the existing ONNX cache if present; otherwise Memory falls back to BM25
plus hash embeddings. `unregister` remains a separate command.

### Extension

```bash
sbt --client 'extension/fullLinkJS'
cd client-extension
npm run build
```

The production Vite output is written to `client-extension/dist`.

### Native Host

Linux:

```bash
bash server/scripts/pop-can.sh build
```

macOS:

```bash
bash server/scripts/pop-can.sh build
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File server\scripts\pop-can.ps1 build
```

Windows setup uses the existing platform-independent JAR. Build it on Linux first with
`sbt --client 'server/assembly'`; do not run sbt against the shared checkout from Windows.

The native host is a self-contained jpackage app image with a bundled Java runtime. The optional
Memory embedding model is not stored in Git:

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

See [NATIVE_HOST.md](NATIVE_HOST.md) for package formats, registration, and platform
constraints.

### Complete Release Bundle

The release scripts combine the already-built extension and native host into one flat platform
bundle:

```bash
release/package.sh
```

```powershell
powershell -ExecutionPolicy Bypass -File release\package.ps1
```

The archive contains `extension/`, `native-host/`, registration scripts, and user documentation
without exposing internal module build directories, `server/scripts/`, or `docs/`.

Packaging also writes `BUILD-MANIFEST.json`, `FILES.sha256`, `SBOM.cdx.json`, an external
`.manifest.json`, and a `.sha256` sidecar. The script extracts the final archive again and verifies
the extension/native-host layout, file hashes, SBOM and Provider-rule exclusion before success.

## Provider Pack

Provider presets live in `client-extension/presets/providers/*.jsonc`.

Provider rule schema v1 accepts literal `equals`, `contains`, `startsWith`, and `notStartsWith`
match conditions. A single `appendContent` action may be written as handler-level
`content: "$.path"`. These are runtime shorthands shared by built-in JSONC, fixture replay, and
local Import; they do not change the serialized schema version.

Stable single-line JSON SSE handlers can use `transport: "sse-json-line"` instead of repeating
`rawRegex` and `jsonGroup`. The shorthand is normalized to the existing raw matcher; it does not
provide event-name filtering, multi-line data assembly, or full SSE event-boundary semantics.

Boolean phases can be declared under `parserRule.phases`. A handler with `startsPhase: true`
automatically sets the phase memory key before its actions, while a normal handler in the same phase
automatically requires that key to match the declared value. Phase handlers cannot also declare
explicit memory gates. This is limited to fixed-string Boolean-style gates; complex alternative
gates remain explicit.

Effective-rule inspection also runs through the loaded extension so it uses the same parser as
production:

```bash
npm run presets:explain --prefix client-extension -- --provider deepseek
npm run presets:diff --prefix client-extension -- --provider deepseek --from HEAD~1
npm run presets:bench --prefix client-extension
```

Strict preset validation requires every handler that can emit content/delta to be pinned by at least
one response fixture. The raw matcher groups handlers sharing the same `(rawRegex, jsonGroup)` into
one extractor, while preserving independent handler cursors and same-frame memory snapshots.

After changing a preset:

1. Increment `client-extension/presets/VERSION`.
2. Rebuild the provider pack payload source:

```bash
node client-extension/scripts/build-presets.mjs
```

Both `npm run dev --prefix client-extension` and
`npm run build --prefix client-extension` rebuild this payload through their pre-lifecycle
scripts. During `npm run dev`, provider JSONC and `VERSION` edits are debounced and automatically
rebuilt into `presets/bundle.json`. Re-import that bundle to apply it; Vite does not update the
installed provider configuration automatically. The explicit command remains useful when preparing
changes without starting Vite.

3. Verify that the committed bundle is current without rewriting it:

```bash
npm run presets:check --prefix client-extension
```

CI and release checks still run this verification before building, so a stale committed bundle is
not hidden by the local `prebuild` generation step.

4. Run fixture replay with the extension loaded and CDP available:

```bash
node client-extension/scripts/replay-fixtures.mjs
```

Before committing captured fixtures, sanitize identifiers and verify the privacy gate:

```bash
npm run fixtures:sanitize --prefix client-extension -- --write
npm run fixtures:privacy --prefix client-extension
```

Fixture replay executes the real parser engine inside the extension. It does not replace strict
marker validation or end-to-end browser testing.

Provider rules are not bundled into application archives. To build signed distribution assets:

```bash
PROVIDER_PACK_PRIVATE_KEY="$(cat /path/to/provider-pack-private-key.pem)" \
  npm run provider-pack:build --prefix client-extension \
  -- --out release/artifacts/provider-packs
```

The public key is committed at `client-extension/keys/provider-pack-public-key.spki.base64`.
Production signing uses GitHub Secret `PROVIDER_PACK_PRIVATE_KEY`; `.github/workflows/provider-pack.yml`
is manual-only and requires approval from the protected `provider-signing` Environment. It publishes
`provider-index.popcan.json`, `provider-pack-v*.popcan.json`, and the fixed
`provider-pack-latest.popcan.json` bootstrap alias through GitHub Pages independently of application
tags.

## Runtime Boundaries

- The server binds `127.0.0.1:8080` by default and exposes only the OpenAI-compatible HTTP face.
- The extension communicates with the native host through framed native messaging on stdio.
- The extension owns authentication, provider routing, prompt construction, parser rules, and CDP
  automation.
- Memory is served by the single native-host runtime leader and is not exposed as a public HTTP
  endpoint.
- The extension API key, browser profile, and Memory database are local trust boundaries.

## Documentation Ownership

- `README.md`: public product overview and installation.
- `docs/DEVELOPMENT.md`: contributor workflow.
- `server/README.md`: server and HTTP/native messaging contract.
- `client-extension/README.md`: extension development details.
- `docs/NATIVE_HOST.md`: installation, packaging, and troubleshooting.
- `AGENTS.md`: internal maintenance instructions; not a user manual.
