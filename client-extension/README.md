# Pop Can Extension

The Pop Can browser extension provides the plugin UI, provider automation, native-messaging client,
CDP sessions, and the local OpenAI-compatible request path.

## Architecture

```text
background --+
content -----+--> model --> facade
ui ----------+
```

- `background`: service worker, provider tabs, CDP automation, native messaging, request rewriting,
  Memory bridge, and browser tools.
- `content`: provider-page content scripts and the session capsule.
- `ui`: Laminar panel and content-script views.
- `model`: chat, provider, Memory, diagnostics, and local API domain behavior.
- `facade`: typed Scala.js declarations for browser APIs and JavaScript libraries.

Run the dependency boundary check with:

```bash
node scripts/check-architecture.mjs
```

## Development

Install package dependencies:

```bash
npm ci
```

Generate the Scala.js development output:

```bash
sbt --client 'extension/fastLinkJS'
```

Start Vite:

```bash
npm run dev
```

Load `dev-extension` from `chrome://extensions` with **Load unpacked**.

The production manifest embeds a fixed public key, so development and production builds keep a
stable extension ID even when loaded from different directories. `release/check-extension-id.mjs`
verifies that the public key and committed extension ID match.

## Debug Browser

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

The scripts expose CDP on port `9222` by default. Recent Chrome versions ignore remote debugging
when launched with the default user-data directory on Windows, so the Windows script uses a
dedicated local profile.

The debugging port allows local programs to control the browser. Close the debug browser when it is
no longer needed.

## Production Build

Generate the production Scala.js output:

```bash
sbt --client 'extension/fullLinkJS'
```

Build the extension:

```bash
npm run build
```

The resulting unpacked extension is written to `dist`.

## Provider Pack

Provider presets are JSONC files under `presets/providers`.

After editing a preset:

1. Increment `presets/VERSION`.
2. Rebuild the bundled catalog:

```bash
node scripts/build-presets.mjs
```

3. Verify the committed bundle matches the JSONC sources:

```bash
npm run presets:check
```

`npm run build` runs this check through the `prebuild` lifecycle. Use
`npm run presets:check:strict` when auditing fixed-array-index warnings.

4. Run parser fixture replay:

```bash
node scripts/replay-fixtures.mjs
```

`replay-fixtures.mjs` executes the real parser engine through the loaded extension. It does not
replace strict marker validation or browser end-to-end testing.

Provider rules do not ship inside `dist`. The Pages workflow signs the generated pack with
`PROVIDER_PACK_PRIVATE_KEY`; local key material must never be committed:

```bash
PROVIDER_PACK_PRIVATE_KEY="$(cat /path/to/provider-pack-private-key.pem)" \
  npm run provider-pack:build -- --out ../release/artifacts/provider-packs
```

The generated signed envelope can be imported from **Config -> Providers -> Import…** for offline
testing.

## Permissions

The extension requires privileged browser permissions, including `debugger`, `tabs`, `tabGroups`,
`scripting`, and `nativeMessaging`. Optional HTTP/HTTPS host access is requested only for provider
pages that need it.

Review [SECURITY.md](../SECURITY.md) before using the extension with sensitive accounts or data.

## Documentation

- [User guide](../README.md)
- [Development workflow](../docs/DEVELOPMENT.md)
- [Server contract](../server/README.md)
- [Native host and packaging](../docs/NATIVE_HOST.md)
