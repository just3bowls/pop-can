#!/usr/bin/env bash
# Pop Can release-bundle setup and native messaging registration CLI.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core="$root/.pop-can/pop-can.sh"
fixed_id_file="$root/.pop-can/extension-id.txt"
host_name="com.just3bowls.pop_can"

usage() {
  cat <<'EOF'
Usage:
  ./host.sh setup [--provider-pack <file>] [--browser <dir>] [--profile <user-data-dir>] [--name <host-name>]
  ./host.sh register [extension-id] [--browser <dir>] [--profile <user-data-dir>] [--name <host-name>]
  ./host.sh unregister [--browser <dir>] [--profile <user-data-dir>] [--name <host-name>]
  ./host.sh help
EOF
}

usage_error() {
  echo "$1" >&2
  usage >&2
  exit 2
}

require_value() {
  local option="$1"
  local value="${2:-}"
  [[ -n "$value" ]] || usage_error "missing value for $option"
}

fixed_extension_id() {
  if [[ -n "${POP_CAN_EXTENSION_ID:-}" ]]; then
    printf '%s' "$POP_CAN_EXTENSION_ID"
  elif [[ -f "$fixed_id_file" ]]; then
    tr -d '[:space:]' < "$fixed_id_file"
  fi
}

open_browser_extensions() {
  if [[ "${POP_CAN_SKIP_BROWSER_OPEN:-0}" == "1" ]]; then
    echo "Browser launch skipped (POP_CAN_SKIP_BROWSER_OPEN=1)."
    return
  fi
  local browser="$1"
  local profile_dir="$2"
  local bin=""
  local -a args=()
  local -a candidates=()
  case "$(uname -s)" in
    Darwin)
      case "$browser" in
        Chromium) bin="/Applications/Chromium.app/Contents/MacOS/Chromium" ;;
        *) bin="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ;;
      esac
      ;;
    Linux)
      local candidate
      case "$browser" in
        chromium) candidates=(chromium chromium-browser) ;;
        google-chrome-beta) candidates=(google-chrome-beta) ;;
        google-chrome-unstable) candidates=(google-chrome-unstable) ;;
        *) candidates=(google-chrome google-chrome-stable chromium) ;;
      esac
      for candidate in "${candidates[@]}"; do
        if command -v "$candidate" >/dev/null 2>&1; then
          bin="$(command -v "$candidate")"
          break
        fi
      done
      ;;
  esac
  if [[ -z "$bin" || ! -x "$bin" ]]; then
    echo "Native host registered. Open chrome://extensions manually and choose Load unpacked." >&2
    return
  fi
  if [[ -n "$profile_dir" ]]; then args+=("--user-data-dir=$profile_dir"); fi
  nohup "$bin" "${args[@]}" --new-window "chrome://extensions" >/dev/null 2>&1 &
  echo "Opened browser extensions page."
}

copy_extension_path() {
  local extension_path="$root/extension"
  case "$(uname -s)" in
    Darwin)
      if command -v pbcopy >/dev/null 2>&1 && printf '%s' "$extension_path" | pbcopy >/dev/null 2>&1; then
        echo "Extension path copied to clipboard: $extension_path"
        return
      fi
      ;;
    Linux)
      local copier
      for copier in wl-copy xclip xsel; do
        if command -v "$copier" >/dev/null 2>&1; then
          case "$copier" in
            wl-copy) printf '%s' "$extension_path" | wl-copy >/dev/null 2>&1 || continue ;;
            xclip) printf '%s' "$extension_path" | xclip -selection clipboard >/dev/null 2>&1 || continue ;;
            xsel) printf '%s' "$extension_path" | xsel --clipboard --input >/dev/null 2>&1 || continue ;;
          esac
          echo "Extension path copied to clipboard: $extension_path"
          return
        fi
      done
      ;;
  esac
  echo "Extension path: $extension_path"
}

provider_pack_sha256() {
  local file="$1"
  local output=""
  if command -v sha256sum >/dev/null 2>&1; then
    output="$(sha256sum "$file")"
  elif command -v shasum >/dev/null 2>&1; then
    output="$(shasum -a 256 "$file")"
  else
    return 1
  fi
  printf '%s' "${output%% *}"
}

stage_provider_pack() {
  local extension_path="$root/extension"
  local bootstrap_dir="$extension_path/bootstrap"
  local target="$bootstrap_dir/provider-pack.popcan.json"
  local checksum="$bootstrap_dir/provider-pack.sha256"
  local pack_part="$target.part"
  local checksum_part="$checksum.part"
  local source_file="$1"
  local url=""
  local bytes=""
  local hash=""

  if [[ "${POP_CAN_SKIP_PROVIDER_PACK:-0}" == "1" ]]; then
    echo "Provider Pack staging skipped (POP_CAN_SKIP_PROVIDER_PACK=1)."
    return 0
  fi
  if ! mkdir -p "$bootstrap_dir"; then
    echo "Provider Pack not staged: cannot create $bootstrap_dir. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  rm -f "$pack_part" "$checksum_part"

  if [[ -n "$source_file" ]]; then
    if [[ ! -f "$source_file" ]]; then
      echo "Provider Pack not staged: file not found: $source_file" >&2
      return 0
    fi
    if ! cp "$source_file" "$pack_part"; then
      rm -f "$pack_part"
      echo "Provider Pack not staged: cannot read $source_file. Use Config -> Providers -> Import instead." >&2
      return 0
    fi
  else
    url="${POP_CAN_PROVIDER_PACK_URL:-}"
    if [[ -z "$url" ]]; then
      local url_file="$root/.pop-can/provider-pack-url.txt"
      [[ -f "$url_file" ]] || url_file="$root/provider-pack-url.txt"
      if [[ -f "$url_file" ]]; then
        url="$(tr -d '[:space:]' < "$url_file")"
      fi
    fi
    if [[ -z "$url" ]]; then
      echo "Provider Pack not staged: download URL is missing. Use Config -> Providers -> Import instead." >&2
      return 0
    fi
    if command -v curl >/dev/null 2>&1; then
      if ! curl -fsSL --max-time 20 "$url" -o "$pack_part"; then
        rm -f "$pack_part"
        echo "Provider Pack download failed; setup will continue. Use Config -> Providers -> Import instead." >&2
        return 0
      fi
    elif command -v wget >/dev/null 2>&1; then
      if ! wget -q --timeout=20 -O "$pack_part" "$url"; then
        rm -f "$pack_part"
        echo "Provider Pack download failed; setup will continue. Use Config -> Providers -> Import instead." >&2
        return 0
      fi
    else
      echo "Provider Pack not staged: curl or wget is required. Use Config -> Providers -> Import instead." >&2
      return 0
    fi
  fi

  if [[ ! -s "$pack_part" ]]; then
    rm -f "$pack_part"
    echo "Provider Pack not staged: downloaded file is empty. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  if ! bytes="$(wc -c < "$pack_part" | tr -d '[:space:]')"; then
    rm -f "$pack_part"
    echo "Provider Pack not staged: cannot inspect downloaded file. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  if [[ "$bytes" -gt 2097152 ]]; then
    rm -f "$pack_part"
    echo "Provider Pack not staged: file exceeds 2 MB. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  if ! hash="$(provider_pack_sha256 "$pack_part")" || [[ -z "$hash" ]]; then
    rm -f "$pack_part"
    echo "Provider Pack not staged: SHA256 tool is unavailable. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  if ! printf '%s\n' "$hash" > "$checksum_part"; then
    rm -f "$pack_part" "$checksum_part"
    echo "Provider Pack not staged: cannot write SHA256 sidecar. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  if ! mv "$pack_part" "$target" || ! mv "$checksum_part" "$checksum"; then
    rm -f "$pack_part" "$checksum_part"
    echo "Provider Pack staging failed; setup will continue. Use Config -> Providers -> Import instead." >&2
    return 0
  fi
  echo "Provider Pack staged. It will be verified when the extension starts."
}

action="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi
if [[ "$action" == "help" || "$action" == "-h" || "$action" == "--help" ]]; then
  usage
  exit 0
fi
if [[ "$action" != "setup" && "$action" != "register" && "$action" != "unregister" ]]; then
  usage_error "unknown command: $action"
fi

extension_id=""
browser=""
profile_dir=""
provider_pack_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider-pack) require_value "$1" "${2:-}"; provider_pack_file="$2"; shift 2 ;;
    --browser) require_value "$1" "${2:-}"; browser="$2"; shift 2 ;;
    --profile) require_value "$1" "${2:-}"; profile_dir="$2"; shift 2 ;;
    --name) require_value "$1" "${2:-}"; host_name="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -* ) usage_error "unknown option: $1" ;;
    *)
      if [[ "$action" == "register" && -z "$extension_id" ]]; then
        extension_id="$1"
        shift
      else
        usage_error "unexpected argument: $1"
      fi
      ;;
  esac
done
if [[ "$action" != "setup" && -n "$provider_pack_file" ]]; then
  usage_error "--provider-pack is only valid with setup"
fi

case "$(uname -s)" in
  Darwin)
    [[ -n "$browser" ]] || browser="Google/Chrome"
    app_path="$root/native-host/PopCanHost.app"
    manifest_dir="$HOME/Library/Application Support/$browser/NativeMessagingHosts"
    ;;
  Linux)
    if [[ -z "$browser" ]]; then
      for candidate in google-chrome chromium google-chrome-beta google-chrome-unstable; do
        [[ -d "${XDG_CONFIG_HOME:-$HOME/.config}/$candidate" ]] && browser="$candidate" && break
      done
      browser="${browser:-google-chrome}"
    fi
    app_path="$root/native-host/PopCanHost"
    manifest_dir="${XDG_CONFIG_HOME:-$HOME/.config}/$browser/NativeMessagingHosts"
    ;;
  *)
    echo "unsupported OS: $(uname -s)" >&2
    exit 1
    ;;
esac

if [[ -n "$profile_dir" ]]; then
  manifest_dir="$profile_dir/NativeMessagingHosts"
fi

if [[ "$action" == "unregister" ]]; then
  "$core" unregister --name "$host_name" --manifest-dir "$manifest_dir"
  exit 0
fi

[[ -n "$extension_id" ]] || extension_id="$(fixed_extension_id)"
[[ -n "$extension_id" ]] || usage_error "fixed extension ID file is missing; use register <extension-id>"
"$core" register "$extension_id" \
  --app "$app_path" \
  --name "$host_name" \
  --manifest-dir "$manifest_dir"

if [[ "$action" == "setup" ]]; then
  stage_provider_pack "$provider_pack_file"
  copy_extension_path
  open_browser_extensions "$browser" "$profile_dir"
  echo "In chrome://extensions, enable Developer mode, choose Load unpacked, and select the copied path."
  echo "The fixed extension ID is: $extension_id"
fi
