# Provider Packs

Pop Can keeps provider rules separate from the application package. This allows selector and parser
fixes to ship without replacing the extension or native host.

## Online Install

The release `host setup` command normally stages `provider-pack-latest.popcan.json` under the
extension's `bootstrap/` directory. On the next extension startup, the background worker verifies
the ECDSA signature, schema, extension compatibility, SHA256 and install policy before applying it.
This bootstrap does not request the GitHub Pages permission and does not enable automatic updates.

If bootstrap staging is unavailable, install online manually:

1. Open **Config -> Providers**.
2. Click **Import**, then **Download rules**.
3. Approve access to `https://just3bowls.github.io/*`.
4. Sign in to a provider, enable it, and grant access to that provider's origin.

After the first successful online install, Pop Can checks for signed Provider Pack updates about
once every 24 hours. Auto-update can be disabled from the Providers panel.

## Offline Install

On a connected machine, download a signed release asset named:

```text
provider-pack-v<N>.popcan.json
```

Transfer it to the offline machine and use **Config -> Providers -> Import…**. Pop Can verifies the
ECDSA signature, pack schema, extension compatibility and version before installing it.

The release setup scripts can also stage it directly:

```bash
./host.sh setup --provider-pack provider-pack-vN.popcan.json
```

```powershell
powershell -ExecutionPolicy Bypass -File .\host.ps1 setup -ProviderPack .\provider-pack-vN.popcan.json
```

The same import dialog also accepts unsigned provider JSON/JSONC. Unsigned content is treated as
user-owned configuration and is never presented as an official update.

## Backup

- Per-provider **Copy** copies one effective provider document, including local modifications.
- User-owned configurations can be re-imported as JSON/JSONC.

## Updates And Conflicts

- Untouched providers update automatically from a newer signed pack.
- Locally modified or user-imported providers are retained and marked `modified`.
- Deleted providers remain deleted until **Restore removed** is used.
- Downgrades, same-version content replacement, invalid signatures and incompatible schemas are
  rejected without replacing the current pack.

## Network And Trust

Provider Pack access is an optional host permission requested only after a user action. The
application contains the signing public key; the private key remains outside the repository and is
used only by the release workflow.

## Publishing

The public key is `client-extension/keys/provider-pack-public-key.spki.base64`. The matching private
key must be stored outside the repository and configured as GitHub Secret
`PROVIDER_PACK_PRIVATE_KEY`.

```bash
openssl genpkey -algorithm EC \
  -pkeyopt ec_paramgen_curve:P-256 \
  -out ~/.config/pop-can/provider-pack-private-key.pem
chmod 600 ~/.config/pop-can/provider-pack-private-key.pem
gh secret set PROVIDER_PACK_PRIVATE_KEY \
  < ~/.config/pop-can/provider-pack-private-key.pem
```

Enable GitHub Pages for the repository and create a protected GitHub Environment named
`provider-signing` with required reviewers. The signing workflow is manual-only so production private
key access always has an approval boundary:

- Require pull requests and passing CI for `master`.
- Restrict who can modify workflows and Provider Pack signing scripts.
- Configure at least one required reviewer on `provider-signing`.

```bash
npm run presets:build --prefix client-extension
npm run presets:check --prefix client-extension
```

Any content change must increment `client-extension/presets/VERSION`. The publication workflow
compares the new content hash with the currently deployed index and rejects an unchanged version
when content differs.

Run **Provider Pack** from GitHub Actions after the changes pass CI. It publishes signed files under:

```text
https://just3bowls.github.io/pop-can/provider-packs/
```

The workflow publishes both the versioned Pack and `provider-pack-latest.popcan.json`. The fixed
alias must be byte-identical to the Pack referenced by the current signed index.
