#!/usr/bin/env bash
# Create a GitHub release for the current mod_version.
# Versioning:
#   x.y.z     → stable release (Latest)
#   x.y.z.w   → beta / prerelease (+0.0.0.1 style)
set -euo pipefail
cd "$(dirname "$0")/.."

VER=$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
if [[ -z "$VER" ]]; then
  echo "mod_version missing in gradle.properties" >&2
  exit 1
fi

PARTS=$(echo "$VER" | tr '.' '\n' | wc -l)
JAR="build/libs/pathfinder-${VER}.jar"
TAG="v${VER}"

./gradlew build --quiet

if [[ ! -f "$JAR" ]]; then
  echo "JAR not found: $JAR" >&2
  exit 1
fi

NOTES="Pathfinder ${VER} (NeoForge 1.21.11)"
ARGS=(release create "$TAG" "$JAR" --title "Pathfinder ${VER}" --notes "$NOTES")

if [[ "$PARTS" -ge 4 ]]; then
  ARGS+=(--prerelease)
  echo "Beta / prerelease: $TAG"
else
  ARGS+=(--latest)
  echo "Stable release: $TAG"
fi

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "Release $TAG already exists — uploading asset"
  gh release upload "$TAG" "$JAR" --clobber
  if [[ "$PARTS" -ge 4 ]]; then
    gh release edit "$TAG" --prerelease --title "Pathfinder ${VER}" --notes "$NOTES"
  else
    gh release edit "$TAG" --latest --title "Pathfinder ${VER}" --notes "$NOTES"
  fi
else
  gh "${ARGS[@]}"
fi

echo "OK https://github.com/ovrmiha2007/pathfinder/releases/tag/${TAG}"
