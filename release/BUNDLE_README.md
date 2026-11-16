# Pop Can

This archive contains both the browser extension and the native host.

`BUILD-MANIFEST.json` records the source commit and stable input hashes. `FILES.sha256` covers the
payload and `SBOM.cdx.json` contains the CycloneDX software inventory. The Release page also
provides an external `.manifest.json`, `.sha256`, and GitHub provenance attestation.

## Install

1. Run setup:
   - Linux/macOS: `./host.sh setup`
   - Windows PowerShell: `powershell -ExecutionPolicy Bypass -File .\host.ps1 setup`
2. Setup registers the host, stages the latest signed Provider Pack, opens `chrome://extensions`,
   and copies the extension path.
3. Enable **Developer mode**, choose **Load unpacked**, and select the copied `extension/` path.
4. Restart the browser.
5. Open the Pop Can extension page.
6. Open **Config -> API Keys** and enable **Local API**.
7. Open **Config -> Providers**. The staged Provider Pack is verified and installed on first
   extension startup. If staging failed, use **Import** to download or import rules manually.
8. Sign in to a provider, enable it, and create an API key under **Config -> API Keys**.

Use `./host.sh unregister` or `powershell -ExecutionPolicy Bypass -File .\host.ps1 unregister`
to remove the native messaging registration.

Provider rules are not bundled with this application archive. Setup stages a separately distributed
signed Pack. For an offline machine, pass it with:

```text
host.sh setup --provider-pack provider-pack-vN.popcan.json
host.ps1 setup -ProviderPack provider-pack-vN.popcan.json
```

You can also use **Import…** under **Config -> Providers**.

See `NATIVE_HOST.md` for platform-specific details and troubleshooting.
See `PROVIDER_PACK.md` for online, offline, update, and copy workflows.
