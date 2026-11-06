#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

if [ -z "${ANDROID_HOME:-}" ]; then
    for sdk_candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -d "$sdk_candidate" ]; then
            ANDROID_HOME=$sdk_candidate
            export ANDROID_HOME
            break
        fi
    done
fi

if [ -n "${GRADLE:-}" ]; then
    GRADLE_COMMAND=$GRADLE
elif [ -x "$PROJECT_DIR/gradlew" ]; then
    GRADLE_COMMAND="$PROJECT_DIR/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_COMMAND=$(command -v gradle)
else
    for gradle_candidate in "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle; do
        if [ -x "$gradle_candidate" ]; then
            GRADLE_COMMAND=$gradle_candidate
            break
        fi
    done
    if [ -z "${GRADLE_COMMAND:-}" ]; then
        printf '%s\n' "Gradle was not found. Install Gradle or set GRADLE to its executable path." >&2
        exit 1
    fi
fi

"$GRADLE_COMMAND" assembleDebug
printf '\nDebug APK: %s\n' "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
