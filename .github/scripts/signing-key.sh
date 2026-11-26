#!/usr/bin/env bash
# Writes release.jks and keystore.properties from the KEYSTORE_BASE64, KEYSTORE_PASSWORD,
# KEY_ALIAS and KEY_PASSWORD environment variables (the repository's secrets), so Gradle signs
# with the release key. With any of them missing it writes nothing and says so, unless
# REQUIRE_KEY=1, when it fails instead.
set -euo pipefail

missing=()
for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done

if (( ${#missing[@]} )); then
  if [[ "${REQUIRE_KEY:-0}" == 1 ]]; then
    echo "::error::Missing signing secrets: ${missing[*]}. Releases must be signed with the release key."
    exit 1
  fi
  echo "::notice::No signing secrets (${missing[*]}); the debug APK uses a throwaway debug key and won't install over a release."
  exit 0
fi

echo "$KEYSTORE_BASE64" | base64 --decode > release.jks
cat > keystore.properties <<EOF
storeFile=release.jks
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
echo "Signing with the release key."
