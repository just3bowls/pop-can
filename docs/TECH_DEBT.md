# Technical Debt

This file tracks deferred engineering work that materially affects release quality. It is not a
feature roadmap.

## Browser End-to-End Tests

- **Problem:** CI builds the extension but never runs the full extension -> native messaging ->
  host -> CDP -> local server path.
- **Target:** run Playwright Chromium in headed mode under Xvfb against a local mock provider,
  with temporary profiles and collected traces on failure.
- **Status:** deferred.

## Application Update

- **Problem:** extension and native host are installed independently, without a protocol handshake
  or an updater that installs both as one application version.
- **Target:** add explicit native protocol compatibility, stage signed extension/host updates, switch
  a stable install path atomically, and reload the extension after the update.
- **Status:** deferred.

## Installer And Optional Registration

- **Problem:** portable archives still require running `host setup` once and loading the extension
  manually.
- **Target:** provide signed platform installers that install the native host manifest or registry
  entry automatically while retaining `host setup` as the portable fallback.
- **Status:** deferred because the current release scope is archive-only.

## Supply-Chain Hardening

- **Resolved:** Actions are commit-SHA pinned, actionlint is version-pinned, workflow permissions are
  split by job, Memory downloads use committed SHA256 values, published releases cannot be clobbered,
  archives have manifests/SBOMs/provenance, and GitHub release jobs are source-clean.
- **Remaining:** release binaries are unsigned and lack Windows code signing and macOS
  signing/notarization.
- **Status:** remaining work deferred to the public-release hardening pass.
