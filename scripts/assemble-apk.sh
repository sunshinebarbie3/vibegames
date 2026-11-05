#!/usr/bin/env bash
#
# assemble-apk.sh — automate assembling the VIBE Android APK.
#
# Runs the same checks CI runs (string resource validation, debug compile,
# unit tests and lint) and reports the produced debug APK path. Optionally
# installs it on a connected, authorized physical phone via adb.
#
# Usage:
#   ./scripts/assemble-apk.sh           # assembleDebug + install if a phone is connected
#   ./scripts/assemble-apk.sh release   # assembleRelease (requires preflight config)
#   ./scripts/assemble-apk.sh no-install # skip adb install even if a phone is present
#
# Prerequisites on this machine:
#   - Java 17+ on PATH (or JAVA_HOME set)
#   - Android SDK: ANDROID_HOME/sdk.dir pointing at SDK with platform android-36
#   - The app's real config for a signed/release build: local.properties,
#     app/google-services.json and keystore.properties (not required for debug)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BRANCH="arena/a068a4b9-vibegames"
VARIANT="${1:-debug}"
SKIP_INSTALL="${2:-}"
APK_DEBUG="app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="app/build/outputs/apk/release/app-release.apk"

log()   { printf '\033[1;32m==> %s\033[0m\n' "$*"; }
warn()  { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
fail()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Branch hygiene — build from the tracked arena branch
# ---------------------------------------------------------------------------
current_branch="$(git symbolic-ref --short HEAD 2>/dev/null || true)"
if [ "$current_branch" != "$BRANCH" ]; then
    fail "Expected to be on branch $BRANCH, but you are on '$current_branch'. Switch to $BRANCH first."
fi

log "Ensuring $BRANCH is up to date"
git pull --ff-only origin "$BRANCH" >/dev/null 2>&1 || warn "Could not fast-forward from origin; building local tree as-is"

# ---------------------------------------------------------------------------
# 2. Toolchain prerequisites
# ---------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
    fail "Java is not available. Install JDK 17+ and set JAVA_HOME, or run this in Android Studio."
fi
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
    fail "JAVA_HOME=$JAVA_HOME does not contain a usable java binary."
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
        ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' local.properties | tr -d '\r')"
    fi
fi
if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    fail "Android SDK not found. Set ANDROID_HOME or add sdk.dir=/path/to/android-sdk to local.properties."
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
log "Toolchain OK: java=$(java -version 2>&1 | head -1), ANDROID_HOME=$ANDROID_HOME"

# ---------------------------------------------------------------------------
# 3. Pre-build checks
# ---------------------------------------------------------------------------
if [ ! -f local.properties ]; then
    warn "local.properties is missing. Debug build can still proceed; a release/signing build needs sdk.dir, API_BASE_URL and GOOGLE_SERVER_CLIENT_ID."
fi
if [ ! -f app/google-services.json ]; then
    warn "app/google-services.json is missing — FCM/Cloud Messaging will not work. Debug build can still proceed."
fi

log "Validating string resources"
python3 tools/check_strings.py

# ---------------------------------------------------------------------------
# 4. Assemble
# ---------------------------------------------------------------------------
case "$VARIANT" in
    debug)
        TARGET=":app:assembleDebug :app:testDebugUnitTest :app:lintDebug"
        APK="$APK_DEBUG"
        ;;
    release)
        TARGET=":app:assembleRelease"
        APK="$APK_RELEASE"
        ;;
    *)
        fail "Unknown variant '$VARIANT'. Use debug or release."
        ;;
esac

log "Assembling $VARIANT APK (this can take several minutes on first run)"
./gradlew $TARGET --stacktrace

if [ ! -f "$APK" ]; then
    fail "Build completed but $APK was not produced."
fi

log "APK ready: $REPO_ROOT/$APK"
log "Size: $(du -h "$APK" | cut -f1)"

# ---------------------------------------------------------------------------
# 5. Optional install on a connected physical phone
# ---------------------------------------------------------------------------
if [ "$SKIP_INSTALL" = "no-install" ]; then
    log "Skipping adb install (no-install requested)"
    exit 0
fi

if command -v adb >/dev/null 2>&1; then
    devices="$(adb devices 2>/dev/null | grep -c 'device$' || true)"
    if [ "${devices:-0}" -gt 0 ]; then
        log "Physical phone detected — installing $APK"
        adb install -r "$APK"
        log "Install complete. Launch: com.vibe.app"
    else
        warn "No authorized physical phone visible to adb. Install manually: adb install -r $APK"
    fi
else
    warn "adb not on PATH. Install manually: adb install -r $APK"
fi
