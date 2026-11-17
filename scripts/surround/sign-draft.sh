#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:?}"
: "${SURROUND_KEYSTORE_B64:?Repository signing secret is required}"
: "${SURROUND_STORE_PASSWORD:?Repository signing secret is required}"
umask 077
key_dir=$(mktemp -d)
trap 'rm -rf -- "$key_dir"' EXIT
printf '%s' "$SURROUND_KEYSTORE_B64" | base64 --decode > "$key_dir/release.p12"
printf '%s' "$SURROUND_STORE_PASSWORD" > "$key_dir/password"
mkdir -p .build/release
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign \
  --ks "$key_dir/release.p12" --ks-key-alias moonlight-surround \
  --ks-pass "file:$key_dir/password" --key-pass "file:$key_dir/password" \
  --out .build/release/moonlight-surround-armeabi-v7a.apk \
  app/build/outputs/apk/nonRoot/release/app-nonRoot-release-unsigned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs \
  .build/release/moonlight-surround-armeabi-v7a.apk
expected=$(openssl x509 -in docs/signing-certificate.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr '[:upper:]' '[:lower:]')
actual=$("$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs \
  .build/release/moonlight-surround-armeabi-v7a.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ "$actual" == "$expected" ]]
python3 scripts/surround/verify-apk.py .build/release/moonlight-surround-armeabi-v7a.apk release
cp app/build/outputs/native-debug-symbols/nonRootRelease/native-debug-symbols.zip .build/release/
python3 scripts/surround/source-bundle.py
(
  cd .build/release
  sha256sum moonlight-surround-armeabi-v7a.apk moonlight-surround-source.tar.gz \
    build-manifest.json native-debug-symbols.zip > SHA256SUMS
)
