#!/usr/bin/env bash
# Pop Can source-checkout management CLI for Linux and macOS.
set -euo pipefail

script_path="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
repo_root="$(cd "$(dirname "$script_path")/../.." && pwd)"
host_name="com.just3bowls.pop_can"

cache_root() {
  printf '%s' "${POP_CAN_CACHE_DIR:-$repo_root/server/.cache}"
}

default_extension_id() {
  if [[ -n "${POP_CAN_EXTENSION_ID:-}" ]]; then
    printf '%s' "$POP_CAN_EXTENSION_ID"
  elif [[ -f "$repo_root/client-extension/keys/extension-id.txt" ]]; then
    tr -d '[:space:]' < "$repo_root/client-extension/keys/extension-id.txt"
  elif [[ -f "$(dirname "$script_path")/extension-id.txt" ]]; then
    tr -d '[:space:]' < "$(dirname "$script_path")/extension-id.txt"
  fi
}

usage() {
  cat <<'EOF'
Usage:
  pop-can.sh setup [--skip-model] [--browser NAME] [--profile DIR]
                   [--name NAME] [--manifest-dir DIR]
  pop-can.sh build
  pop-can.sh model [--dest DIR] [--base URL]
  pop-can.sh debug [browser args...]
  pop-can.sh register [extension-id] [--classpath] [--app PATH] [--browser NAME]
                                   [--profile DIR] [--name NAME] [--manifest-dir DIR]
  pop-can.sh unregister [--browser NAME] [--profile DIR]
                        [--name NAME] [--manifest-dir DIR]
  pop-can.sh help
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

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum"
  else
    echo "shasum"
  fi
}

sha256_file() {
  local path="$1" tool
  tool="$(sha256_tool)"
  if [[ "$tool" == "sha256sum" ]]; then
    sha256sum "$path" | awk '{print $1}'
  else
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

expected_model_hash() {
  local checksums="$1" wanted="$2" hash name
  while read -r hash name; do
    if [[ "$name" == "$wanted" ]]; then
      printf '%s' "$hash"
      return 0
    fi
  done < "$checksums"
  return 1
}

platform_key() {
  case "$(uname -s)" in
    Linux) echo "linux" ;;
    Darwin) echo "macos" ;;
    *)
      echo "unsupported OS: $(uname -s)" >&2
      exit 1
      ;;
  esac
}

detect_browser() {
  local platform="$1"
  local browser="${2:-}"
  if [[ -n "$browser" ]]; then
    echo "$browser"
    return
  fi
  case "$platform" in
    linux)
      local candidate
      for candidate in google-chrome chromium google-chrome-beta google-chrome-unstable; do
        if [[ -d "${XDG_CONFIG_HOME:-$HOME/.config}/$candidate" ]]; then
          echo "$candidate"
          return
        fi
      done
      echo "google-chrome"
      ;;
    macos)
      if [[ -d "$HOME/Library/Application Support/Google/Chrome" ]]; then
        echo "Google/Chrome"
      elif [[ -d "$HOME/Library/Application Support/Chromium" ]]; then
        echo "Chromium"
      else
        echo "Google/Chrome"
      fi
      ;;
  esac
}

manifest_dir_for() {
  local platform="$1"
  local browser="$2"
  local profile_dir="$3"
  if [[ -n "$profile_dir" ]]; then
    echo "$profile_dir/NativeMessagingHosts"
  elif [[ "$platform" == "linux" ]]; then
    echo "${XDG_CONFIG_HOME:-$HOME/.config}/$browser/NativeMessagingHosts"
  else
    echo "$HOME/Library/Application Support/$browser/NativeMessagingHosts"
  fi
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "$value"
}

write_manifest() {
  local extension_id="$1"
  local name="$2"
  local host_path="$3"
  local manifest_dir="$4"
  local manifest="$manifest_dir/$name.json"
  mkdir -p "$manifest_dir"
  cat > "$manifest" <<JSON
{
  "name": "$(json_escape "$name")",
  "description": "Pop Can local server (native messaging host)",
  "path": "$(json_escape "$host_path")",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://$(json_escape "$extension_id")/"]
}
JSON
  echo "registered: $manifest -> $host_path"
}

remove_manifest() {
  local name="$1"
  local manifest_dir="$2"
  local manifest="$manifest_dir/$name.json"
  if [[ -f "$manifest" ]]; then
    rm -f "$manifest"
    echo "removed: $manifest"
  else
    echo "not registered: $manifest"
  fi
}

write_classpath_launcher() {
  local platform="$1"
  local launcher_dir="$repo_root/server/target/native-host/$platform/classpath"
  local launcher="$launcher_dir/host.sh"
  mkdir -p "$launcher_dir"
  {
    echo '#!/usr/bin/env bash'
    printf 'exec %q __native-host\n' "$script_path"
  } > "$launcher"
  chmod +x "$launcher"
  echo "$launcher"
}

native_host() {
  local platform
  platform="$(platform_key)"
  local classpath_file="$repo_root/server/target/native-host/$platform/classpath/classpath.txt"
  if [[ ! -f "$classpath_file" ]]; then
    echo "missing $classpath_file - run: sbt --client 'server/writeNativeHostClasspath'" >&2
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "java not found; install JDK 21+" >&2
    exit 1
  fi
  exec java -cp "$(cat "$classpath_file")" com.just3bowls.server.bootstrap.ServerMain \
    --native-host
}

command_build() {
  [[ $# -eq 0 ]] || usage_error "build takes no arguments; only app-image is supported"
  local platform
  platform="$(platform_key)"

  local version
  version="$(tr -d '[:space:]' < "$repo_root/VERSION")"
  [[ -n "$version" ]] || {
    echo "VERSION is empty" >&2
    exit 1
  }
  command -v jpackage >/dev/null 2>&1 || {
    echo "jpackage is required (use JDK 21+)" >&2
    exit 1
  }

  cd "$repo_root"
  local out_dir="$repo_root/server/target/native-host/$platform/app-image"
  local jar_dir="$repo_root/server/target/scala-3.7.1"
  local jar_name="pop-can-server.jar"

  if [[ "${POP_CAN_SKIP_ASSEMBLY:-0}" == "1" ]]; then
    echo "==> skip sbt server/assembly (POP_CAN_SKIP_ASSEMBLY=1)"
  else
    echo "==> sbt server/assembly"
    sbt --client 'server/assembly'
  fi
  [[ -f "$jar_dir/$jar_name" ]] || {
    echo "assembly jar not found: $jar_dir/$jar_name" >&2
    exit 1
  }

  local package_input
  package_input="$(mktemp -d "${TMPDIR:-/tmp}/pop-can-$platform-input.XXXXXX")"
  trap 'rm -rf -- "${package_input:-}"' EXIT
  cp "$jar_dir/$jar_name" "$package_input/$jar_name"

  mkdir -p "$out_dir"
  if [[ "$platform" == "linux" ]]; then
    rm -rf -- "$out_dir/PopCanHost"
  else
    rm -rf -- "$out_dir/PopCanHost.app"
  fi

  local -a jpackage_args=(
    --type app-image
    --name PopCanHost
    --app-version "$version"
    --vendor "Pop Can"
    --description "Pop Can local server (native messaging host)"
    --input "$package_input"
    --main-jar "$jar_name"
    --main-class com.just3bowls.server.bootstrap.ServerMain
    --arguments "--native-host"
    --java-options "-Dpop-can.native-host=true"
    --dest "$out_dir"
    --verbose
  )
  if [[ "$platform" == "macos" ]]; then
    jpackage_args+=(
      --mac-package-name "Pop Can"
      --mac-package-identifier com.just3bowls.popcan
    )
  fi

  echo "==> jpackage --type app-image"
  jpackage "${jpackage_args[@]}"

  local app_image
  local launcher
  if [[ "$platform" == "linux" ]]; then
    app_image="$out_dir/PopCanHost"
    launcher="$app_image/bin/PopCanHost"
  else
    app_image="$(find "$out_dir" -maxdepth 1 -name 'PopCanHost.app' -type d -print -quit)"
    launcher="$app_image/Contents/MacOS/PopCanHost"
  fi
  [[ -n "$app_image" && -x "$launcher" ]] || {
    echo "packaged app launcher not found under $out_dir" >&2
    exit 1
  }

  local host_cfg
  host_cfg="$(find "$app_image" -name 'PopCanHost.cfg' -type f -print -quit)"
  [[ -n "$host_cfg" ]] || {
    echo "packaged launcher config not found under $app_image" >&2
    exit 1
  }
  if grep -Fq "$repo_root" "$host_cfg" || grep -Fq "pop-can.log.dir" "$host_cfg"; then
    echo "packaged launcher leaks a workspace log path: $host_cfg" >&2
    exit 1
  fi

  local model_dir
  model_dir="$(cache_root)/models/multilingual-e5-small"
  local host_jar
  host_jar="$(find "$app_image" -name "$jar_name" -type f -print -quit)"
  [[ -f "$host_jar" ]] || {
    echo "packaged host jar not found under $app_image" >&2
    exit 1
  }
  if [[ -f "$model_dir/model.onnx" && -f "$model_dir/tokenizer.json" ]]; then
    echo "==> copy ONNX memory model"
    mkdir -p "$(dirname "$host_jar")/models"
    rm -rf "$(dirname "$host_jar")/models/multilingual-e5-small"
    cp -R "$model_dir" "$(dirname "$host_jar")/models/multilingual-e5-small"
  elif [[ "${POP_CAN_REQUIRE_MEMORY_MODEL:-0}" == "1" ]]; then
    echo "memory model missing; run: server/scripts/pop-can.sh model" >&2
    exit 1
  else
    echo "warning: memory model missing; host will use BM25 + hash fallback" >&2
  fi

  echo "done -> $app_image"
  echo "next: server/scripts/pop-can.sh register"
}

command_model() {
  local dest="${POP_CAN_MEMORY_MODEL:-$(cache_root)/models/multilingual-e5-small}"
  local base="${POP_CAN_MODEL_BASE_URL:-https://hf-mirror.com/Xenova/multilingual-e5-small/resolve/main}"
  local trust_env="${POP_CAN_MODEL_TRUST_ENV:-0}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dest) require_value "$1" "${2:-}"; dest="$2"; shift 2 ;;
      --base) require_value "$1" "${2:-}"; base="$2"; shift 2 ;;
      -h|--help) usage; return ;;
      *) usage_error "unknown model argument: $1" ;;
    esac
  done
  command -v curl >/dev/null 2>&1 || {
    echo "curl is required" >&2
    exit 1
  }
  local expected_checksums="$repo_root/server/memory-model.sha256"
  [[ -f "$expected_checksums" ]] || {
    echo "trusted memory model checksum file is missing: $expected_checksums" >&2
    exit 1
  }
  mkdir -p "$dest"
  echo "==> memory model -> $dest"
  local hash_tool
  hash_tool="$(sha256_tool)"
  local remote name target
  while read -r remote name; do
    target="$dest/$name"
    local expected actual
    expected="$(expected_model_hash "$expected_checksums" "$name")" || {
      echo "checksum entry missing for $name" >&2
      exit 1
    }
    if [[ -s "$target" ]]; then
      actual="$(sha256_file "$target")"
      if [[ "$actual" == "$expected" ]]; then
        echo "keep $target"
        continue
      fi
      rm -f "$target" "$target.part"
      echo "removed invalid cached model file: $target" >&2
    fi
    echo "download $remote"
    if [[ "$trust_env" == "1" ]]; then
      curl --fail --location --retry 4 --retry-all-errors --output "$target.part" "$base/$remote"
    else
      curl --noproxy '*' --fail --location --retry 4 --retry-all-errors --output "$target.part" "$base/$remote"
    fi
    mv "$target.part" "$target"
  done <<'FILES'
onnx/model_quantized.onnx model.onnx
tokenizer.json tokenizer.json
config.json config.json
FILES
  local verify_status=0
  if [[ "$hash_tool" == "sha256sum" ]]; then
    (cd "$dest" && sha256sum -c "$expected_checksums") || verify_status=$?
  else
    (cd "$dest" && shasum -a 256 -c "$expected_checksums") || verify_status=$?
  fi
  if [[ "$verify_status" != "0" ]]; then
    rm -f "$dest/model.onnx" "$dest/tokenizer.json" "$dest/config.json"
    echo "memory model checksum verification failed; invalid files were removed" >&2
    exit 1
  fi
  (
    cd "$dest"
    if [[ "$hash_tool" == "sha256sum" ]]; then
      sha256sum model.onnx tokenizer.json config.json > SHA256SUMS
    else
      shasum -a 256 model.onnx tokenizer.json config.json > SHA256SUMS
    fi
  )
  echo "done"
  echo "next: server/scripts/pop-can.sh build"
}

command_setup() {
  local skip_model=0
  local -a register_args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-model) skip_model=1; shift ;;
      --browser) require_value "$1" "${2:-}"; register_args+=(--browser "$2"); shift 2 ;;
      --profile) require_value "$1" "${2:-}"; register_args+=(--profile "$2"); shift 2 ;;
      --name) require_value "$1" "${2:-}"; register_args+=(--name "$2"); shift 2 ;;
      --manifest-dir) require_value "$1" "${2:-}"; register_args+=(--manifest-dir "$2"); shift 2 ;;
      -h|--help) usage; return ;;
      *) usage_error "unknown setup argument: $1" ;;
    esac
  done

  local extension_id
  extension_id="$(default_extension_id)"
  [[ -n "$extension_id" ]] || usage_error "setup requires the fixed extension ID file"
  if [[ ! "$extension_id" =~ ^[a-p]{32}$ ]]; then
    echo "invalid fixed extension ID: $extension_id" >&2
    exit 1
  fi

  if [[ "$skip_model" == "1" ]]; then
    echo "warning: model preparation skipped; existing ONNX cache will be used if present, otherwise Memory will use BM25 + hash fallback" >&2
    export POP_CAN_REQUIRE_MEMORY_MODEL=0
  else
    command_model
    export POP_CAN_REQUIRE_MEMORY_MODEL=1
  fi

  command_build
  command_register "$extension_id" "${register_args[@]}"
  echo ""
  echo "setup complete"
  echo "extension id: $extension_id"
  echo "reload the extension page or restart the browser before using the new host"
}

command_register() {
  local extension_id=""
  local mode="app-image"
  local app_path=""
  local browser=""
  local profile_dir=""
  local name="$host_name"
  local explicit_manifest_dir=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --classpath) mode="classpath"; shift ;;
      --app) require_value "$1" "${2:-}"; app_path="$2"; shift 2 ;;
      --browser) require_value "$1" "${2:-}"; browser="$2"; shift 2 ;;
      --profile) require_value "$1" "${2:-}"; profile_dir="$2"; shift 2 ;;
      --name) require_value "$1" "${2:-}"; name="$2"; shift 2 ;;
      --manifest-dir) require_value "$1" "${2:-}"; explicit_manifest_dir="$2"; shift 2 ;;
      -h|--help) usage; return ;;
      -*) usage_error "unknown register option: $1" ;;
      *)
        [[ -z "$extension_id" ]] || usage_error "unexpected argument: $1"
        extension_id="$1"
        shift
        ;;
    esac
  done
  [[ -n "$extension_id" ]] || extension_id="$(default_extension_id)"
  [[ -n "$extension_id" ]] || usage_error "registration requires an extension ID (fixed ID file is missing)"
  [[ ! ("$mode" == "classpath" && -n "$app_path") ]] || usage_error "--classpath and --app are mutually exclusive"
  if [[ ! "$extension_id" =~ ^[a-p]{32}$ ]]; then
    echo "warning: '$extension_id' does not look like a Chrome extension ID (32 chars, a-p)" >&2
  fi

  local platform
  platform="$(platform_key)"
  browser="$(detect_browser "$platform" "$browser")"
  local manifest_dir="$explicit_manifest_dir"
  [[ -n "$manifest_dir" ]] || manifest_dir="$(manifest_dir_for "$platform" "$browser" "$profile_dir")"

  local host_path
  if [[ "$mode" == "classpath" ]]; then
    local classpath_file="$repo_root/server/target/native-host/$platform/classpath/classpath.txt"
    [[ -f "$classpath_file" ]] || {
      echo "missing $classpath_file - run: sbt --client 'server/writeNativeHostClasspath'" >&2
      exit 1
    }
    host_path="$(write_classpath_launcher "$platform")"
  else
    if [[ -n "$app_path" ]]; then
      if [[ -f "$app_path" && -x "$app_path" ]]; then
        host_path="$app_path"
      elif [[ "$platform" == "linux" ]]; then
        host_path="$app_path/bin/PopCanHost"
      else
        host_path="$app_path/Contents/MacOS/PopCanHost"
      fi
    elif [[ "$platform" == "linux" ]]; then
      host_path="$repo_root/server/target/native-host/linux/app-image/PopCanHost/bin/PopCanHost"
    else
      host_path="$repo_root/server/target/native-host/macos/app-image/PopCanHost.app/Contents/MacOS/PopCanHost"
    fi
  fi
  [[ -x "$host_path" ]] || {
    echo "native host launcher is not executable: $host_path" >&2
    exit 1
  }
  write_manifest "$extension_id" "$name" "$host_path" "$manifest_dir"
}

command_unregister() {
  local browser=""
  local profile_dir=""
  local name="$host_name"
  local explicit_manifest_dir=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --browser) require_value "$1" "${2:-}"; browser="$2"; shift 2 ;;
      --profile) require_value "$1" "${2:-}"; profile_dir="$2"; shift 2 ;;
      --name) require_value "$1" "${2:-}"; name="$2"; shift 2 ;;
      --manifest-dir) require_value "$1" "${2:-}"; explicit_manifest_dir="$2"; shift 2 ;;
      -h|--help) usage; return ;;
      *) usage_error "unknown unregister argument: $1" ;;
    esac
  done
  local platform
  platform="$(platform_key)"
  browser="$(detect_browser "$platform" "$browser")"
  local manifest_dir="$explicit_manifest_dir"
  [[ -n "$manifest_dir" ]] || manifest_dir="$(manifest_dir_for "$platform" "$browser" "$profile_dir")"
  remove_manifest "$name" "$manifest_dir"
}

linux_debug() {
  local port="${PC_CDP_PORT:-${TH_CDP_PORT:-${POP_CAN_CDP_PORT:-9222}}}"
  local bin="${PC_CHROME:-${TH_CHROME:-}}"
  local config_root="${XDG_CONFIG_HOME:-$HOME/.config}"
  local candidate dir display
  local -a candidates used
  candidates=(google-chrome google-chrome-stable chromium chromium-browser google-chrome-beta google-chrome-unstable)
  if [[ -z "$bin" ]]; then
    used=()
    for candidate in "${candidates[@]}"; do
      case "$candidate" in
        chromium|chromium-browser) dir="chromium" ;;
        *) dir="$candidate" ;;
      esac
      [[ -d "$config_root/$dir" ]] && used+=("$candidate")
    done
    for candidate in "${used[@]}" "${candidates[@]}"; do
      if command -v "$candidate" >/dev/null 2>&1; then
        bin="$(command -v "$candidate")"
        break
      fi
    done
  fi
  [[ -n "$bin" && -x "$bin" ]] || {
    echo "No Chrome/Chromium found; set PC_CHROME=/path/to/chrome" >&2
    exit 1
  }
  local config_dir
  local manifest
  case "$(basename "$bin")" in
    chromium|chromium-browser) config_dir="chromium" ;;
    google-chrome-beta) config_dir="google-chrome-beta" ;;
    google-chrome-unstable) config_dir="google-chrome-unstable" ;;
    *) config_dir="google-chrome" ;;
  esac
  if [[ -n "${PC_DISPLAY:-${TH_DISPLAY:-}}" ]]; then
    display="${PC_DISPLAY:-${TH_DISPLAY:-}}"
  elif [[ -n "${DISPLAY:-}" ]]; then
    display="$DISPLAY"
  elif [[ -S /tmp/.X11-unix/X0 ]]; then
    display=":0"
  else
    display=""
  fi
  if [[ -n "$display" ]]; then
    export DISPLAY="$display"
    [[ -n "${XAUTHORITY:-}" || ! -f "$HOME/.Xauthority" ]] || export XAUTHORITY="$HOME/.Xauthority"
  else
    echo "Warning: no usable X display found; pass PC_DISPLAY=:0 or run from a desktop terminal." >&2
  fi
  if curl -s --max-time 2 "http://127.0.0.1:$port/json/version" >/dev/null 2>&1; then
    echo "Note: port $port is already in use." >&2
  fi
  manifest="$config_root/$config_dir/NativeMessagingHosts/$host_name.json"
  echo "browser: $bin   display: ${display:-<none>}   profile: <default>   cdp: http://127.0.0.1:$port"
  [[ -f "$manifest" ]] ||
    echo "Note: native host is not registered. Run: server/scripts/pop-can.sh register --classpath --browser $config_dir"
  exec "$bin" \
    --remote-debugging-port="$port" \
    --no-first-run \
    --no-default-browser-check \
    "$@"
}

macos_debug() {
  local port="${PC_CDP_PORT:-${TH_CDP_PORT:-${POP_CAN_CDP_PORT:-9222}}}"
  local bin="${POP_CAN_CHROME:-}"
  local profile="${POP_CAN_DEBUG_PROFILE:-$HOME/Library/Application Support/PopCan/chrome-debug-macos}"
  local browser_config manifest browser_pid up candidate
  if [[ -z "$bin" ]]; then
    for candidate in \
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
      "$HOME/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
      "/Applications/Chromium.app/Contents/MacOS/Chromium" \
      "$HOME/Applications/Chromium.app/Contents/MacOS/Chromium"
    do
      [[ -x "$candidate" ]] && bin="$candidate" && break
    done
  fi
  [[ -n "$bin" && -x "$bin" ]] || {
    echo "No Chrome/Chromium found; set POP_CAN_CHROME=/path/to/browser" >&2
    exit 1
  }
  case "$bin" in
    *Chromium*) browser_config="Chromium" ;;
    *) browser_config="Google/Chrome" ;;
  esac
  if curl -fsS --max-time 2 "http://127.0.0.1:$port/json/version" >/dev/null 2>&1; then
    echo "port $port is already serving CDP; choose another with PC_CDP_PORT" >&2
    exit 1
  fi
  mkdir -p "$profile"
  manifest="$HOME/Library/Application Support/$browser_config/NativeMessagingHosts/$host_name.json"
  echo "browser: $bin"
  echo "profile: $profile"
  echo "cdp: http://127.0.0.1:$port"
  [[ -f "$manifest" ]] ||
    echo "Note: native host is not registered. Run: server/scripts/pop-can.sh register --classpath"
  echo "Note: first run requires loading the extension into this profile and signing in to providers."
  "$bin" \
    --remote-debugging-port="$port" \
    --user-data-dir="$profile" \
    --no-first-run \
    --no-default-browser-check \
    "$@" >/dev/null 2>&1 &
  browser_pid=$!
  up=0
  for _ in {1..12}; do
    sleep 0.5
    if curl -fsS --max-time 2 "http://127.0.0.1:$port/json/version" >/dev/null 2>&1; then
      up=1
      echo "CDP is up"
      break
    fi
  done
  if [[ "$up" != "1" ]]; then
    echo "CDP failed to open on port $port; quit all browser instances and retry" >&2
    exit 1
  fi
  wait "$browser_pid"
}

command_debug() {
  case "$(platform_key)" in
    linux) linux_debug "$@" ;;
    macos) macos_debug "$@" ;;
  esac
}

command="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi
case "$command" in
  setup) command_setup "$@" ;;
  build) command_build "$@" ;;
  model) command_model "$@" ;;
  debug) command_debug "$@" ;;
  register) command_register "$@" ;;
  unregister) command_unregister "$@" ;;
  __native-host) native_host ;;
  help|-h|--help) usage ;;
  *) usage_error "unknown command: $command" ;;
esac
