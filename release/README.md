# Release

This directory owns repository-level release checks, platform archive assembly, and generated
artifacts.

```text
release/
├── check-version.mjs
├── BUNDLE_README.md
├── host.sh
├── host.ps1
├── package.sh
├── package.ps1
├── provider-pack-url.txt
└── artifacts/                generated archives and checksums (ignored by Git)
```

Native-host build, development, registration, browser debugging, and model preparation scripts live
under `server/scripts/`. See [`../server/README.md`](../server/README.md).

`host.sh` and `host.ps1` are the user-facing native messaging entry points copied into platform
archives. Their primary command is no-argument `setup`, which registers the fixed extension ID,
best-effort stages the latest signed Provider Pack, copies the extension path and opens
`chrome://extensions`; `register` / `unregister` remain advanced entry points. Package orchestration
stays in `package.*`.

## Version Check

```bash
node release/check-version.mjs
node release/check-version.mjs --tag v1.2.1
```

## Build Release Bundles

Each platform script combines:

```text
client-extension/dist
server/target/native-host/{platform}
```

into one complete user archive. Provider rules are deliberately excluded from the archive: setup
downloads the signed GitHub Pages latest Pack into the extension bootstrap directory, and the
extension verifies it before installation.

```bash
release/package.sh [--skip-build] [--prepare-model]
```

```powershell
powershell -ExecutionPolicy Bypass -File release\package.ps1 [-SkipBuild] [-PrepareModel]
```

Use `--skip-build` or `-SkipBuild` to reuse the existing app image. Use
`--prepare-model` or `-PrepareModel` to download the Memory model and require it while rebuilding
the native host. Model preparation cannot be combined with skipping the host build.

Generated files are written to `release/artifacts/`:

```text
pop-can-v*-linux.tar.gz
pop-can-v*-linux.tar.gz.sha256
pop-can-v*-linux.tar.gz.manifest.json
pop-can-v*-windows.zip
pop-can-v*-windows.zip.sha256
pop-can-v*-windows.zip.manifest.json
pop-can-v*-macos.tar.gz
pop-can-v*-macos.tar.gz.sha256
pop-can-v*-macos.tar.gz.manifest.json
```

Each archive contains `BUILD-MANIFEST.json`, `FILES.sha256`, and `SBOM.cdx.json`. The external
manifest binds the archive hash to the internal manifest and SBOM. Linux, macOS, and Windows
archives are not signed; macOS notarization and Windows Authenticode signing are not implemented.

Syft `1.57.1` is downloaded once into `server/.cache/tools/` and verified against a pinned SHA256
before generating the CycloneDX SBOM. Set `POP_CAN_SYFT_BIN` to use an existing executable.

GitHub release builds require a clean source tree. Local dirty builds are recorded with
`sourceClean=false` and must not be published.

## Environment Variables

- `POP_CAN_CACHE_DIR`: override the project cache root instead of `server/.cache/`.
- `POP_CAN_MEMORY_MODEL`: custom Memory model output directory.
- `POP_CAN_MODEL_BASE_URL`: custom model download base URL.
- `POP_CAN_MODEL_TRUST_ENV`: set to `1` to allow proxy environment variables during model download.
- `POP_CAN_REQUIRE_MEMORY_MODEL`: set to `1` to fail packaging when the model is missing.
- `POP_CAN_SKIP_ASSEMBLY`: set to `1` to reuse an existing server fat jar.
- `POP_CAN_SKIP_BUILD`: set to `1` to reuse an existing native host app image.
- `POP_CAN_RELEASE_DIR`: override the output directory instead of `release/artifacts/`.
- `POP_CAN_REQUIRE_CLEAN_SOURCE`: set to `1` to reject tracked or untracked source changes.
- `POP_CAN_SYFT_BIN`: use an existing Syft executable instead of the pinned downloader.
- `POP_CAN_PROVIDER_PACK_URL`: override the Provider Pack URL used by release `host setup`.
- `POP_CAN_SKIP_PROVIDER_PACK`: set to `1` to skip the setup-time Provider Pack download.
- `SOURCE_DATE_EPOCH`: override the deterministic commit timestamp used by manifests.

## Integrity Verification

```bash
sha256sum -c pop-can-v1.2.1-linux.tar.gz.sha256
tar -tzf pop-can-v1.2.1-linux.tar.gz | rg 'BUILD-MANIFEST|FILES.sha256|SBOM.cdx.json'
gh attestation verify pop-can-v1.2.1-linux.tar.gz --repo just3bowls/pop-can
```

The archive itself contains the complete file hash inventory. The external `.manifest.json`
connects that inventory to the final archive SHA256.
