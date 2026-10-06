#!/usr/bin/env bash
# Run only on the owner's computer. Never upload the keystore or passwords.
set -euo pipefail
if [[ $# -ne 4 ]]; then
  echo 'Usage: bash sign-local.sh unsigned.apk signed.apk /path/to/private-key.jks alias' >&2
  exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
rike_tools="$ANDROID_HOME/build-tools/35.0.0"
rike_input="$1"; rike_output="$2"; rike_keystore="$3"; rike_alias="$4"
[[ -f "$rike_input" && -f "$rike_keystore" ]]
[[ ! -e "$rike_output" ]] || { echo 'Output already exists; choose a new file.' >&2; exit 2; }
rike_permissions="$("$rike_tools/aapt" dump permissions "$rike_input")"
rike_metadata="$("$rike_tools/aapt" dump badging "$rike_input")"
[[ "$rike_permissions" != *android.permission.INTERNET* ]]
[[ "$rike_metadata" != *application-debuggable* ]]
[[ "$rike_metadata" == *"package: name='app.rike.offline'"* ]]
rike_aligned="$(mktemp -t rike-aligned.XXXXXX)"
trap 'rm -f "$rike_aligned"' EXIT
"$rike_tools/zipalign" -f -p 4 "$rike_input" "$rike_aligned"
# apksigner prompts locally. No password is put in args, shell history or logs.
"$rike_tools/apksigner" sign --ks "$rike_keystore" --ks-key-alias "$rike_alias" --out "$rike_output" "$rike_aligned"
"$rike_tools/apksigner" verify --verbose --print-certs "$rike_output"
"$rike_tools/zipalign" -c -p 4 "$rike_output"
echo 'Signed APK ready. Compare its certificate SHA-256 with the previous release before distributing.'
