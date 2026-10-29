#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release, which
# is what the in-app updater looks for. Bump `val version` in
# app/build.gradle.kts first; the tag is v<version>.
#
#   scripts/release.sh "这一版改了什么"
set -euo pipefail
cd "$(dirname "$0")/.."

notes=${1:?"usage: scripts/release.sh <release notes>"}
version=$(sed -nE 's/^ *val version = "([0-9.]+)"/\1/p' app/build.gradle.kts)
tag="v$version"
repo=MuqiuPeng/VoiceTodo

[ -z "$(git status --porcelain)" ] || { echo "working tree not clean"; exit 1; }
grep -q VOICETODO_KEYSTORE ~/.gradle/gradle.properties || { echo "release keystore not configured"; exit 1; }
if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then echo "$tag already released"; exit 1; fi

./gradlew -q :parser:test :app:testDebugUnitTest :app:assembleRelease
apk=app/build/outputs/apk/release/VoiceTodo-$version.apk
cp app/build/outputs/apk/release/app-release.apk "$apk"

git tag -a "$tag" -m "$tag"
git push origin "$tag"
gh release create "$tag" "$apk" --repo "$repo" --title "$tag" --notes "$notes"
