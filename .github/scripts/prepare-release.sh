#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?release version is required}"
BRANCH="${2:?release branch is required}"

if [[ "$BRANCH" != "dev" && "$BRANCH" != "main" ]]; then
  echo "Unsupported release branch: $BRANCH" >&2
  exit 1
fi

update_gradle_version() {
  local file="$1"
  python3 - "$file" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
version = sys.argv[2]
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r"(?m)^\s*version\s*=\s*.*$", f"version={version}", text, count=1)
if count != 1:
    raise SystemExit(f"Expected exactly one version property in {path}, found {count}")
path.write_text(updated, encoding="utf-8")
PY
}

update_gradle_version gradle.properties
update_gradle_version api/gradle.properties

GRADLE_ACTOR="${GPR_USER:-${GITHUB_ACTOR:-github-actions}}"
GRADLE_TOKEN="${GPR_READ_PACKAGES_TOKEN:-${GITHUB_TOKEN:-}}"

run_gradle() {
  GITHUB_ACTOR="$GRADLE_ACTOR" GITHUB_TOKEN="$GRADLE_TOKEN" \
    ./gradlew --no-daemon "$@"
}

run_gradle -PsignAsDebug -Pversion="$VERSION" --continue testDebugUnitTest

if [[ "$BRANCH" == "dev" ]]; then
  MANAGER_TASK=":app:assembleDev"
  APK_DIR="app/build/outputs/apk/dev"
else
  MANAGER_TASK=":app:assembleRelease"
  APK_DIR="app/build/outputs/apk/release"
fi

run_gradle -Pversion="$VERSION" "$MANAGER_TASK"

rm -rf release-assets
mkdir -p release-assets

EXPECTED_MANAGER_APKS=(
  "universal-patch-manager-v${VERSION}-universal.apk"
  "universal-patch-manager-v${VERSION}-arm64-v8a.apk"
  "universal-patch-manager-v${VERSION}-armeabi-v7a.apk"
  "universal-patch-manager-v${VERSION}-x86.apk"
  "universal-patch-manager-v${VERSION}-x86_64.apk"
)

for apk in "${EXPECTED_MANAGER_APKS[@]}"; do
  src="${APK_DIR}/${apk}"
  if [[ ! -f "$src" ]]; then
    echo "Missing expected manager APK: $src" >&2
    find "$APK_DIR" -maxdepth 1 -type f -print || true
    exit 1
  fi
  cp "$src" release-assets/
done

count="$(find release-assets -maxdepth 1 -type f -name '*.apk' | wc -l | tr -d ' ')"
if [[ "$count" != "5" ]]; then
  echo "Expected 5 release APKs, found $count" >&2
  find release-assets -maxdepth 1 -type f -print
  exit 1
fi

find release-assets -maxdepth 1 -type f -name '*.apk' -print | sort