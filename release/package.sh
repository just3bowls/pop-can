#!/usr/bin/env bash
# Build a complete Linux or macOS release bundle containing extension and native host.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$(uname -s)" in
  Linux) platform="linux" ;;
  Darwin) platform="macos" ;;
  *)
    echo "unsupported release OS: $(uname -s) (use release/package.ps1 on Windows)" >&2
    exit 1
    ;;
esac

version="$(tr -d '[:space:]' < "$repo_root/VERSION")"
node "$repo_root/release/check-extension-id.mjs"
release_dir="${POP_CAN_RELEASE_DIR:-$repo_root/release/artifacts}"
bundle_name="pop-can-v${version}-${platform}"
asset="$bundle_name.tar.gz"
skip_build="${POP_CAN_SKIP_BUILD:-0}"
prepare_model=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) skip_build=1; shift ;;
    --prepare-model) prepare_model=1; shift ;;
    -h|--help)
      echo "usage: release/package.sh [--skip-build] [--prepare-model]"
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ "$prepare_model" == "1" && "$skip_build" == "1" ]]; then
  echo "--prepare-model requires rebuilding the native host; it cannot be combined with --skip-build" >&2
  exit 2
fi

if [[ "$prepare_model" == "1" ]]; then
  "$repo_root/server/scripts/pop-can.sh" model
  export POP_CAN_REQUIRE_MEMORY_MODEL=1
fi

if [[ "$skip_build" != "1" ]]; then
  "$repo_root/server/scripts/pop-can.sh" build
fi

extension_dir="$repo_root/client-extension/dist"
if [[ "$platform" == "linux" ]]; then
  host_dir="$repo_root/server/target/native-host/linux/app-image/PopCanHost"
  host_name="PopCanHost"
  launcher="$host_dir/bin/PopCanHost"
else
  host_dir="$repo_root/server/target/native-host/macos/app-image/PopCanHost.app"
  host_name="PopCanHost.app"
  launcher="$host_dir/Contents/MacOS/PopCanHost"
fi
if [[ ! -f "$extension_dir/manifest.json" ]]; then
  echo "extension build not found: $extension_dir/manifest.json" >&2
  exit 1
fi
if [[ -e "$extension_dir/target" || -e "$extension_dir/vendor" ]]; then
  echo "production extension directory contains development artifacts: $extension_dir" >&2
  exit 1
fi
node "$repo_root/client-extension/scripts/check-provider-pack-exclusion.mjs" --dist "$extension_dir"
if [[ ! -x "$launcher" ]]; then
  echo "native host build not found: $launcher" >&2
  exit 1
fi

stage_root="$(mktemp -d "${TMPDIR:-/tmp}/pop-can-release-$platform.XXXXXX")"
verify_root=""
cleanup() {
  rm -rf -- "$stage_root"
  if [[ -n "$verify_root" ]]; then rm -rf -- "$verify_root"; fi
}
trap cleanup EXIT
bundle="$stage_root/$bundle_name"
mkdir -p "$bundle/native-host"
cp -R "$extension_dir" "$bundle/extension"
cp -R "$host_dir" "$bundle/native-host/$host_name"
cp "$repo_root/release/host.sh" "$bundle/host.sh"
mkdir -p "$bundle/.pop-can"
cp "$repo_root/server/scripts/pop-can.sh" "$bundle/.pop-can/pop-can.sh"
cp "$repo_root/client-extension/keys/extension-id.txt" "$bundle/.pop-can/extension-id.txt"
cp "$repo_root/release/provider-pack-url.txt" "$bundle/.pop-can/provider-pack-url.txt"
cp "$repo_root/release/BUNDLE_README.md" "$bundle/README.md"
cp "$repo_root/docs/NATIVE_HOST.md" "$bundle/NATIVE_HOST.md"
cp "$repo_root/docs/PROVIDER_PACK.md" "$bundle/PROVIDER_PACK.md"
cp "$repo_root/LICENSE" "$repo_root/SECURITY.md" "$repo_root/VERSION" "$bundle/"
chmod +x "$bundle/host.sh" "$bundle/.pop-can/pop-can.sh"

commit="$(git -C "$repo_root" rev-parse HEAD)"
if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=normal)" ]]; then source_clean=false; else source_clean=true; fi
if [[ "${POP_CAN_REQUIRE_CLEAN_SOURCE:-0}" == "1" && "$source_clean" != "true" ]]; then
  echo "release requires a clean tracked and untracked worktree" >&2
  exit 1
fi
source_date_epoch="${SOURCE_DATE_EPOCH:-$(git -C "$repo_root" show -s --format=%ct HEAD)}"
commit_time="$(node -e "process.stdout.write(new Date(Number('$source_date_epoch') * 1000).toISOString())")"

syft_bin="${POP_CAN_SYFT_BIN:-}"
if [[ -z "$syft_bin" ]]; then
  syft_bin="$(node "$repo_root/release/install-syft.mjs")"
fi
raw_sbom="$stage_root/sbom.raw.json"
"$syft_bin" "dir:$bundle" \
  --source-name "$bundle_name" \
  --source-version "$version" \
  -o "cyclonedx-json=$raw_sbom"
node "$repo_root/release/normalize-sbom.mjs" \
  --input "$raw_sbom" \
  --output "$bundle/SBOM.cdx.json" \
  --commit "$commit" \
  --platform "$platform" \
  --timestamp "$commit_time"
node "$repo_root/release/archive-metadata.mjs" prepare \
  --root "$bundle" \
  --platform "$platform" \
  --version "$version" \
  --archive-name "$asset" \
  --commit "$commit" \
  --commit-time "$commit_time" \
  --source-date-epoch "$source_date_epoch" \
  --source-clean "$source_clean"
node "$repo_root/release/archive-metadata.mjs" touch --root "$bundle" --epoch "$source_date_epoch"

mkdir -p "$release_dir"
archive_manifest="$release_dir/$asset.manifest.json"
rm -f "$release_dir/$asset" "$release_dir/$asset.sha256" "$archive_manifest"
tar -C "$stage_root" -czf "$release_dir/$asset" "$bundle_name"

verify_root="$(mktemp -d "${TMPDIR:-/tmp}/pop-can-release-verify-$platform.XXXXXX")"
tar -xzf "$release_dir/$asset" -C "$verify_root"
if ! node "$repo_root/release/archive-metadata.mjs" verify \
  --root "$verify_root/$bundle_name" \
  --platform "$platform" \
  --extension-version "$version"; then
  rm -f "$release_dir/$asset" "$release_dir/$asset.sha256" "$archive_manifest"
  exit 1
fi
node "$repo_root/release/archive-metadata.mjs" finalize \
  --archive "$release_dir/$asset" \
  --root "$bundle"
(
  cd "$release_dir"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$asset" > "$asset.sha256"
  else
    shasum -a 256 "$asset" > "$asset.sha256"
  fi
)

echo "packaged: $release_dir/$asset"
echo "checksum: $release_dir/$asset.sha256"
echo "manifest: $archive_manifest"
