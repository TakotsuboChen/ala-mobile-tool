#!/usr/bin/env bash
#
# Install the debug module APK to a rooted device and follow the AlaMobileTool
# logcat output. The target game is force-stopped so that the next launch picks
# up the freshly installed module.
#
# Usage:
#   ./scripts/install-and-logcat.sh [options]
#
# Options:
#   -b    Build debug APK before installing.
#   -i    Install the APK.
#   -l    Follow logcat after installation.
#   -h    Show this help.
#
# With no options, the script builds, installs and tails logcat.
#

set -euo pipefail

cd "$(dirname "$0")/.."

BUILD=0
INSTALL=0
LOGCAT=0

usage() {
    sed -n '1,/^$/p' "$0"
}

if [[ $# -eq 0 ]]; then
    BUILD=1
    INSTALL=1
    LOGCAT=1
else
    while getopts "bilh" opt; do
        case "$opt" in
            b) BUILD=1 ;;
            i) INSTALL=1 ;;
            l) LOGCAT=1 ;;
            h) usage; exit 0 ;;
            *) usage; exit 1 ;;
        esac
    done
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
TARGET_PACKAGE="com.Vince.AlamobileFormula"
TAG="AlaMobileTool"

echo "==> adb: checking for device"
adb devices -l | grep -q "device$" || {
    echo "No Android device detected. Connect a device or start an emulator." >&2
    exit 1
}

if [[ "$BUILD" -eq 1 ]]; then
    echo "==> Building debug APK"
    ./gradlew :app:assembleDebug
fi

if [[ "$INSTALL" -eq 1 ]]; then
    if [[ ! -f "$APK" ]]; then
        echo "APK not found: $APK" >&2
        exit 1
    fi

    echo "==> Installing $APK"
    adb install -r "$APK"

    echo "==> Stopping target game so the module loads on next launch"
    adb shell "am force-stop $TARGET_PACKAGE" || true
fi

if [[ "$LOGCAT" -eq 1 ]]; then
    echo "==> Clearing old logcat and following tag: $TAG"
    adb logcat -c || true
    adb logcat -s "$TAG" -v color -v tag
fi
