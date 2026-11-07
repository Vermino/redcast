#!/usr/bin/env bash
# Builds the release APK and publishes it as a GitHub release, which installed copies then offer
# as an update. Bump versionCode and versionName in app/build.gradle.kts and commit first.
#
#   scripts/release.sh "What changed, in a line or two"
set -euo pipefail
cd "$(dirname "$0")/.."

notes=${1:?usage: scripts/release.sh "What changed"}
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tag="v$version"

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Commit your changes first." >&2
    exit 1
fi
if gh release view "$tag" --repo Vermino/redcast >/dev/null 2>&1; then
    echo "$tag is already released. Bump versionCode and versionName first." >&2
    exit 1
fi

./gradlew :app:testDebugUnitTest :app:assembleRelease
mkdir -p dist
apk="dist/redcast-$version.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

git tag "$tag"
git push origin "$tag"
if git remote | grep -qx forge; then git push forge "$tag"; fi
gh release create "$tag" "$apk" --repo Vermino/redcast --title "Redcast $version" --notes "$notes"
