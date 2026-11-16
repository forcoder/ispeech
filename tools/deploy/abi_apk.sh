#!/usr/bin/env bash
# 从全量 release 包派生「单 ABI 包」：用于平台附件体积上限（>100 MB 会被拒），
# 或只给某一种 SoC 的机器做诊断/复测。签名证书、applicationId、versionCode/Name
# 与输入包完全一致，可直接覆盖安装。
#
# 用法: tools/deploy/abi_apk.sh <in.apk> <abi> [out.apk]
#   例: tools/deploy/abi_apk.sh app/build/outputs/apk/release/app-release.apk arm64-v8a
#
# 为什么不能只用 `zipalign -f 4`：本包 extractNativeLibs=false，.so 必须**按页对齐**
# 才能被系统直接 mmap；`zip -d` 删条目会重排整个归档、打乱原对齐，而 `-f 4` 只补到
# 4 字节。缺 `-p` 时安装报 `INSTALL_FAILED_INVALID_APK: Failed to extract native
# libraries`（VOCA-231 上一版诊断包就是这样，装了装不上），所以这里对齐后强制校验。
set -euo pipefail

IN_APK="${1:-}"
ABI="${2:-}"
OUT_APK="${3:-}"

[ -n "$IN_APK" ] && [ -n "$ABI" ] || { echo "用法: $0 <in.apk> <abi> [out.apk]" >&2; exit 2; }
[ -f "$IN_APK" ] || { echo "找不到输入包: $IN_APK" >&2; exit 2; }

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "找不到 build-tools（设 ANDROID_HOME）" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KS_PROPS="$REPO_ROOT/keystore/keystore.properties"
[ -f "$KS_PROPS" ] || { echo "缺 $KS_PROPS，无法重签名" >&2; exit 2; }

prop() { grep -E "^$1=" "$KS_PROPS" | head -1 | cut -d= -f2-; }
KS_STORE="$(prop storeFile)"
KS_ALIAS="$(prop keyAlias)"

if [ -z "$OUT_APK" ]; then
    OUT_APK="$REPO_ROOT/dist/$(basename "$IN_APK" .apk)-$ABI.apk"
fi
mkdir -p "$(dirname "$OUT_APK")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$IN_APK" "$WORK/stripped.apk"

# ① 删掉目标 ABI 之外的 native 库，以及旧签名（重签名前必须清掉）
zip -q -d "$WORK/stripped.apk" 'META-INF/*' >/dev/null
for other in $(unzip -Z1 "$WORK/stripped.apk" 'lib/*' | sed 's|lib/\([^/]*\)/.*|\1|' | sort -u); do
    [ "$other" = "$ABI" ] || zip -q -d "$WORK/stripped.apk" "lib/$other/*" >/dev/null
done

# ② 先按页对齐，再签名（顺序反了对齐就白做）
"$BT/zipalign" -p -f 4 "$WORK/stripped.apk" "$WORK/aligned.apk"
(
    export ISPEECH_KS_PASS="$(prop storePassword)" ISPEECH_KEY_PASS="$(prop keyPassword)"
    "$BT/apksigner" sign \
        --ks "$REPO_ROOT/$KS_STORE" \
        --ks-pass env:ISPEECH_KS_PASS \
        --ks-key-alias "$KS_ALIAS" \
        --key-pass env:ISPEECH_KEY_PASS \
        "$WORK/aligned.apk"
)

mv "$WORK/aligned.apk" "$OUT_APK"

# ③ 交付前自检：对齐 / 签名 / ABI 清单，任一不过直接失败，不出「装不上的包」
"$BT/zipalign" -c -v -p 4 "$OUT_APK" >/dev/null || {
    echo "FAIL: .so 未按页对齐，安装会报 Failed to extract native libraries" >&2; exit 1; }
"$BT/apksigner" verify "$OUT_APK" || { echo "FAIL: 签名校验不过" >&2; exit 1; }

ABIS="$(unzip -Z1 "$OUT_APK" 'lib/*' | sed 's|lib/\([^/]*\)/.*|\1|' | sort -u | tr '\n' ' ')"
[ "$ABIS" = "$ABI " ] || { echo "FAIL: 期望只剩 $ABI，实际 $ABIS" >&2; exit 1; }

echo "包: $OUT_APK"
echo "大小: $(stat -f%z "$OUT_APK" 2>/dev/null || stat -c%s "$OUT_APK") B"
echo "sha256: $(shasum -a 256 "$OUT_APK" | awk '{print $1}')"
echo "ABI: $ABIS"
echo "签名证书 SHA-256: $("$BT/apksigner" verify --print-certs "$OUT_APK" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
"$BT/aapt2" dump badging "$OUT_APK" 2>/dev/null | grep -E '^package:' | head -1
