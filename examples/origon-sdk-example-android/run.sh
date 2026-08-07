#!/usr/bin/env bash
#
# Build, install, and launch the Origon SDK example app via CLI
# (no Android Studio required).
#
# Prerequisites (one-time):
#   brew install openjdk@21
#   brew install --cask android-commandlinetools
#   sdkmanager "platform-tools" "build-tools;37.0.0" "platforms;android-37"
#   # for --emulator: also install the emulator + an arm64 system image, e.g.
#   sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"
#   avdmanager create avd -n Origon_API36 \
#       -k "system-images;android-36;google_apis;arm64-v8a" --device pixel_6
#
# Usage:
#   ./run.sh                      # build + install + launch on first connected device
#   ./run.sh --emulator           # boot first available AVD, then build/install/launch
#   ./run.sh --emulator <name>    # boot the named AVD (e.g. Origon_API36), then run
#   ./run.sh <name>               # shorthand for --emulator <name>
#   ./run.sh --apk-only           # just build the APK
#   ./run.sh --logcat             # stream logcat from an already-installed app
#   ./run.sh --emulator <name> --logcat   # boot AVD, then just stream logcat

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PACKAGE="origon.example.android"
ACTIVITY=".MainActivity"

usage() {
    cat <<'EOF'
Usage:
  ./run.sh                      # build + install + launch on first connected device
  ./run.sh --emulator           # boot first available AVD, then build/install/launch
  ./run.sh --emulator <name>    # boot the named AVD (e.g. Origon_API36), then run
  ./run.sh <name>               # shorthand for --emulator <name>
  ./run.sh --apk-only           # just build the APK
  ./run.sh --logcat             # stream logcat from an already-installed app
  ./run.sh --emulator <name> --logcat   # boot AVD, then just stream logcat
EOF
}

# ── Resolve Android SDK ────────────────────────────────────────────────
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for c in "$HOME/Library/Android/sdk" \
             "/opt/homebrew/share/android-commandlinetools" \
             "/usr/local/share/android-commandlinetools"; do
        [[ -d "$c" ]] && { export ANDROID_HOME="$c"; break; }
    done
fi
if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME:-}" ]]; then
    echo "ERROR: Android SDK not found. Set ANDROID_HOME to your SDK root." >&2
    exit 1
fi
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"

# ── Pin a compatible JDK (AGP needs JDK 17-21) ─────────────────────────
jdk_major() { "$1/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1; }
if [[ -z "${JAVA_HOME:-}" || "$(jdk_major "${JAVA_HOME:-/nonexistent}" 2>/dev/null || echo 99)" -gt 21 ]]; then
    for c in "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
             "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
             "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"; do
        [[ -x "$c/bin/java" ]] && { export JAVA_HOME="$c"; break; }
    done
fi

# ── Parse args ─────────────────────────────────────────────────────────
APK_ONLY=false
LOGCAT_ONLY=false
BOOT_EMULATOR=false
EMULATOR_NAME=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk-only) APK_ONLY=true ;;
        --logcat)   LOGCAT_ONLY=true ;;
        -e|--emulator)
            BOOT_EMULATOR=true
            # Optional value: consume the next token unless it's another flag.
            if [[ -n "${2:-}" && "${2:0:1}" != "-" ]]; then EMULATOR_NAME="$2"; shift; fi
            ;;
        -h|--help)  usage; exit 0 ;;
        -*)         echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
        *)          BOOT_EMULATOR=true; EMULATOR_NAME="$1" ;;  # bare AVD name
    esac
    shift
done

pick_device() {
    "$ADB" devices | grep -w device | head -1 | awk -F'\t' '{print $1}'
}

# Serial of the running emulator whose AVD name matches $1, or empty.
emulator_serial_for_avd() {
    local want="$1" serial name
    for serial in $("$ADB" devices | awk '/^emulator-/{print $1}'); do
        name=$("$ADB" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p')
        [[ "$name" == "$want" ]] && { echo "$serial"; return 0; }
    done
    return 1
}

# PID of $PACKAGE on device $1, or empty. Avoids `pidof <pkg>`, which on
# Android 6 (API 23) returns EVERY pid instead of the app's. Parses `ps`
# instead, matching the package as the process name (last column → pid in
# col 2). `ps -A` works on API 24+; on API 23 it prints only a header, so
# fall back to bare `ps`.
app_pid() {
    local dev="$1" pid cmd
    for cmd in 'ps -A' 'ps'; do
        pid=$("$ADB" -s "$dev" shell "$cmd" 2>/dev/null | tr -d '\r' \
            | awk -v pkg="$PACKAGE" '$NF==pkg {print $2; exit}')
        [[ -n "$pid" ]] && { echo "$pid"; return 0; }
    done
    return 1
}

# Stream logs for $PACKAGE on device $1, across API levels. Retries briefly so
# a just-launched app has time to spawn.
stream_logcat() {
    local dev="$1" pid sdk _
    sdk=$("$ADB" -s "$dev" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    for _ in $(seq 1 10); do
        pid=$(app_pid "$dev" || true)
        [[ -n "$pid" ]] && break
        sleep 1
    done
    if [[ -z "$pid" ]]; then
        echo "== $PACKAGE not running; showing crashes + app tags (Ctrl+C to stop) ==" >&2
        "$ADB" -s "$dev" logcat -s "AndroidRuntime:E" "session:V" "moq:V"
        return
    fi
    echo "== Streaming logcat for $PACKAGE (pid $pid) — Ctrl+C to stop ==" >&2
    if [[ "${sdk:-0}" -ge 24 ]]; then
        # `logcat --pid` exists from API 24 onward.
        "$ADB" -s "$dev" logcat --pid="$pid" '*:V'
    else
        # API 23 logcat has no --pid: filter the pid column of threadtime output.
        "$ADB" -s "$dev" logcat -v threadtime '*:V' \
            | grep --line-buffered -E "^[0-9]{2}-[0-9]{2} +[0-9:.]+ +$pid +"
    fi
}

# ── Boot emulator (optional) ───────────────────────────────────────────
# When requested, ensures the target AVD is running and pins the rest of the
# flow to it via DEVICE_OVERRIDE.
DEVICE_OVERRIDE=""
if $BOOT_EMULATOR; then
    [[ -x "$EMULATOR_BIN" ]] || {
        echo "ERROR: emulator not found at $EMULATOR_BIN (sdkmanager \"emulator\")." >&2; exit 1; }

    AVDS=$("$EMULATOR_BIN" -list-avds 2>/dev/null || true)
    [[ -n "$AVDS" ]] || { echo "ERROR: no AVDs exist. Create one with avdmanager." >&2; exit 1; }

    # Default to the first AVD when no name was given.
    [[ -z "$EMULATOR_NAME" ]] && EMULATOR_NAME=$(printf '%s\n' "$AVDS" | head -1)

    if ! printf '%s\n' "$AVDS" | grep -qx "$EMULATOR_NAME"; then
        echo "ERROR: AVD '$EMULATOR_NAME' not found. Available:" >&2
        printf '  %s\n' $AVDS >&2
        exit 1
    fi

    "$ADB" start-server >/dev/null 2>&1 || true
    SERIAL=$(emulator_serial_for_avd "$EMULATOR_NAME" || true)

    if [[ -n "$SERIAL" ]]; then
        echo "== Emulator $EMULATOR_NAME already running ($SERIAL) =="
    else
        echo "== Booting emulator $EMULATOR_NAME =="
        LOG="${TMPDIR:-/tmp}/emulator-$EMULATOR_NAME.log"
        nohup "$EMULATOR_BIN" @"$EMULATOR_NAME" >"$LOG" 2>&1 &
        echo "   (emulator log: $LOG)"
        # Wait for the AVD's serial to register with adb (up to ~4 min).
        for _ in $(seq 1 120); do
            SERIAL=$(emulator_serial_for_avd "$EMULATOR_NAME" || true)
            [[ -n "$SERIAL" ]] && break
            sleep 2
        done
        [[ -n "$SERIAL" ]] || { echo "ERROR: emulator did not register with adb. See $LOG" >&2; exit 1; }
    fi

    echo "== Waiting for $EMULATOR_NAME to finish booting =="
    "$ADB" -s "$SERIAL" wait-for-device
    until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    "$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true  # dismiss lock screen
    echo "== $EMULATOR_NAME ready ($SERIAL) =="
    DEVICE_OVERRIDE="$SERIAL"
fi

# Device for install/launch/logcat: the booted emulator, else first connected.
resolve_device() { [[ -n "$DEVICE_OVERRIDE" ]] && echo "$DEVICE_OVERRIDE" || pick_device; }

# ── Logcat-only mode ───────────────────────────────────────────────────
if $LOGCAT_ONLY; then
    DEVICE=$(resolve_device)
    [[ -z "$DEVICE" ]] && { echo "ERROR: no ADB device connected." >&2; exit 1; }
    stream_logcat "$DEVICE"
    exit 0
fi

# ── Build ──────────────────────────────────────────────────────────────
echo "== Building APK =="
cd "$SCRIPT_DIR"
./gradlew :app:assembleDebug

APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "ERROR: APK not found at $APK" >&2; exit 1; }
$APK_ONLY && { echo "APK: $APK"; exit 0; }

# ── Install & launch ───────────────────────────────────────────────────
DEVICE=$(resolve_device)
[[ -z "$DEVICE" ]] && { echo "ERROR: no ADB device connected (enable USB/Wireless debugging, or pass --emulator)." >&2; exit 1; }
echo "== Installing on $DEVICE =="
"$ADB" -s "$DEVICE" install -r "$APK"

echo "== Launching $PACKAGE =="
"$ADB" -s "$DEVICE" shell am force-stop "$PACKAGE" 2>/dev/null || true
"$ADB" -s "$DEVICE" logcat -c 2>/dev/null || true   # fresh buffer from launch
"$ADB" -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY"

stream_logcat "$DEVICE"
