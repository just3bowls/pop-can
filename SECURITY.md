# Security Policy

## Supported Versions

Pop Can 1.x security fixes are applied to the latest `master` commit and the newest published
release. Older snapshots are not maintained.

## Reporting a Vulnerability

Do not open a public GitHub issue for a suspected vulnerability.

Use GitHub's private vulnerability reporting for this repository:

1. Open the repository's **Security** tab.
2. Select **Report a vulnerability**.
3. Include affected versions, reproduction steps, impact, and any relevant logs.
4. Remove API keys, cookies, account identifiers, Memory contents, and other
   sensitive data before attaching logs.

Reports are handled on a best-effort basis. Please allow time for reproduction
and a coordinated fix before publishing details.

## Security Model

Pop Can is a local automation tool, not a sandbox.

- The browser extension uses powerful permissions including `debugger`,
  `scripting`, `tabs`, `tabGroups`, and `nativeMessaging`.
- Optional host access may cover arbitrary HTTP and HTTPS sites.
- The extension operates web AI pages through the user's existing signed-in
  browser sessions.
- API keys are stored unencrypted in the extension's `chrome.storage.local`.
- Memory data is stored unencrypted in a local SQLite database.
- Network access to web AI providers uses the same trust and account controls as
  the provider pages themselves.
- Windows Smart App Control or organizational Code Integrity policy may block
  unsigned native-host executables before Pop Can can start.
- The local OpenAI-compatible server has no TLS. It is intended for loopback use
  on a trusted machine.

Anyone with access to the browser profile, operating-system account, extension
storage, local server API key, or Memory database can access the associated
data. Use an appropriately secured OS account and browser profile.

## Safe Deployment

- Keep the local server bound to `127.0.0.1`.
- Do not expose port `8080` to a LAN or the public internet without an
  authenticated TLS reverse proxy and a separate security review.
- Do not share generated API keys or reuse them across unrelated clients.
- Treat provider rules and imported configuration as executable security
  configuration: they can influence request rewriting, URLs, selectors, and
  prompt delivery.
- Provider Pack downloads are optional and require access to
  `just3bowls.github.io`; only packs with a valid ECDSA signature from the
  embedded public key and a monotonically increasing version are accepted.
- Portable release archives include a build manifest, sorted file hashes, a
  CycloneDX SBOM, and GitHub Artifact Attestation. Verify the `.sha256` and
  provenance before overriding Gatekeeper or SmartScreen warnings.
- Authenticode signing and timestamping are required for frictionless Windows
  distribution. Disabling Smart App Control is not a supported installation
  requirement.
- The fixed extension private key is stored outside the repository. Losing it
  prevents future signed CRX updates for the same identity; leaking it allows
  impersonating that publisher identity.
- Install native-host builds only from a source you trust.
- Review provider terms of service and account risk before automating a site.
- Use a dedicated browser profile when mixing trusted and untrusted extensions
  or testing third-party provider configurations.

## Out of Scope

- Vulnerabilities in third-party provider websites or AI models.
- Account suspension or service termination caused by provider automation.
- Attacks that already require control of the user's OS account, browser
  profile, extension installation, or generated API key.
- Data copied into Memory by the user or exposed through a compromised provider
  account.

## Third-Party Dependencies

Dependencies and bundled parser assets retain their own licenses. Report a
vulnerability in third-party code to the relevant upstream project as well as to
Pop Can when the issue affects this project's integration.
