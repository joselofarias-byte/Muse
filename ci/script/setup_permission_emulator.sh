#!/usr/bin/env bash
set -euo pipefail

# Software-only API 29 device for the narrow Spanish permissions review.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/tmp/android-sdk}}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-"$ROOT/.hoplite/android-avd"}
AVD=permissions-es-api29
IMAGE='system-images;android-29;default;x86_64'

case "${1:---help}" in
    --install)
        SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
        if [[ ! -x "$SDKMANAGER" ]]; then
            echo "Install Android command-line tools at $ANDROID_HOME/cmdline-tools/latest first." >&2
            exit 1
        fi
        # These host libraries are required even by the headless emulator.
        if command -v apt-get >/dev/null; then
            if [[ $(id -u) == 0 ]]; then
                apt-get update -qq
                apt-get install -y libxkbfile1 libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxrandr2 libxtst6
            else
                echo 'Host libraries must already be installed: libxkbfile1 libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxrandr2 libxtst6.' >&2
            fi
        fi
        "$SDKMANAGER" --sdk_root="$ANDROID_HOME" 'platform-tools' 'emulator' "$IMAGE"
        mkdir -p "$ANDROID_AVD_HOME"
        if [[ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]]; then
            "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
                --name "$AVD" --package "$IMAGE" --device 'Nexus S' <<< no
        fi
        "$ANDROID_HOME/emulator/emulator" -version
        ;;
    --start)
        if [[ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]]; then
            echo "Run $0 --install first." >&2
            exit 1
        fi
        export ANDROID_I_WANT_MY_TCG=yes
        exec "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
            -accel off -no-window -gpu swiftshader -feature -Vulkan \
            -no-audio -no-boot-anim -no-snapshot -memory 2048 -cores 1
        ;;
    --help|-h)
        cat <<'HELP'
Usage: ci/script/setup_permission_emulator.sh --install | --start

--install installs SDK packages and creates the AVD without starting it.
          SDK licenses must already be accepted. Uses apt-get when run as root.
--start   runs the emulator in the foreground; no KVM is required.

SDK: ANDROID_HOME or ANDROID_SDK_ROOT, default /tmp/android-sdk.
AVD: ANDROID_AVD_HOME, default repository .hoplite/android-avd (ignored by git).
Device: Nexus S, Android 10/API 29 default x86_64, 480x800 px at 240 dpi
        (320 dp wide), 2048 MB RAM, one emulated CPU, software SwiftShader.

After boot completes, configure the disposable device through adb:
  adb root
  adb wait-for-device
  adb shell setprop persist.sys.locale es-ES
  adb shell settings put system system_locales es-ES
  adb reboot
Wait for sys.boot_completed=1, then use font_scale 1.0 and 2.0 in separate runs:
  adb shell settings put system font_scale 1.0
  adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
  adb shell am start -n io.zer0.muse/io.zer0.muse.MainActivity
  adb exec-out screencap -p > permissions.png
  adb emu kill

Use the SDK's platform-tools/adb if adb is not on PATH. Navigate normally to
Settings > Permissions; screenshots alone do not prove callbacks or scrolling.
Software emulation can take several minutes and may hit Android system watchdog
timeouts on constrained hosts. A boot or black screenshot is not UI approval.
HELP
        ;;
    *)
        echo "Unknown option: $1 (use --help)" >&2
        exit 2
        ;;
esac
