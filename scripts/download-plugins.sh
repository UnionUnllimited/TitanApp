#!/usr/bin/env bash
# NaiveProxy and Mieru clients for Android, packaged as executables in jniLibs
# (lib*.so so Android extracts them to nativeLibraryDir, where they may be run).
#   naive: from the official plugin APKs (lib/<abi>/libnaive.so)
#   mieru: android_arm64 / android_amd64 builds; linux_armv7 (static Go) for 32-bit phones
#   sing-box (TUIC client): its android builds
# x86_64 too: the app ships x86_64 libXray, so x86 devices (Chromebooks, PC emulators)
# install as x86_64 and only get that ABI's libs.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/jniLibs
AUTH=()
[ -n "${GITHUB_TOKEN:-}" ] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
tmp=$(mktemp -d)
# GitHub answers 502/504 now and then: tried again before the build gives up.
dl() { curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "$@"; }

asset() { # repo regex -> download url of the latest release's matching asset
  dl "${AUTH[@]}" "https://api.github.com/repos/$1/releases/latest" |
    jq -r --arg re "$2" '.assets[] | select(.name | test($re)) | .browser_download_url'
}

for abi in arm64-v8a armeabi-v7a x86_64; do
  url=$(asset klzgrad/naiveproxy "^naiveproxy-plugin-.*-${abi}\\.apk$" | head -1)
  echo "naive $abi: $url"
  dl -o "$tmp/naive-$abi.apk" "$url"
  mkdir -p "$OUT/$abi"
  unzip -p "$tmp/naive-$abi.apk" "lib/$abi/libnaive.so" > "$OUT/$abi/libnaive.so"
done

declare -A MIERU=([arm64-v8a]="_android_arm64\\.tar\\.gz$" [armeabi-v7a]="_linux_armv7\\.tar\\.gz$" [x86_64]="_(android|linux)_amd64\\.tar\\.gz$")
for abi in "${!MIERU[@]}"; do
  url=$(asset enfein/mieru "^mieru_.*${MIERU[$abi]}" | sort | head -1)  # android_ before linux_
  echo "mieru $abi: $url"
  mkdir -p "$tmp/mieru-$abi"
  dl -o "$tmp/mieru-$abi.tar.gz" "$url"
  tar -xzf "$tmp/mieru-$abi.tar.gz" -C "$tmp/mieru-$abi"
  cp "$(find "$tmp/mieru-$abi" -type f -name mieru | head -1)" "$OUT/$abi/libmieru.so"
done

# TUIC servers: sing-box (newest 1.12.x, as in the Windows build) as the client.
SB_TAG=$(git ls-remote --tags --refs https://github.com/SagerNet/sing-box.git "refs/tags/v1.12.*" |
  sed 's|.*refs/tags/||' | grep -E '^v1\.12\.[0-9]+$' | sort -V | tail -1)
SB_VER=${SB_TAG#v}
declare -A SINGBOX=([arm64-v8a]=android-arm64 [armeabi-v7a]=android-arm [x86_64]=android-amd64)
for abi in "${!SINGBOX[@]}"; do
  url="https://github.com/SagerNet/sing-box/releases/download/$SB_TAG/sing-box-$SB_VER-${SINGBOX[$abi]}.tar.gz"
  echo "sing-box $abi: $url"
  mkdir -p "$tmp/sb-$abi"
  dl -o "$tmp/sb-$abi.tar.gz" "$url"
  tar -xzf "$tmp/sb-$abi.tar.gz" -C "$tmp/sb-$abi"
  cp "$(find "$tmp/sb-$abi" -type f -name sing-box | head -1)" "$OUT/$abi/libsingbox.so"
done

chmod 755 "$OUT"/*/lib*.so
ls -la "$OUT"/*/
rm -rf "$tmp"
