#!/usr/bin/env bash
# 从干净的 main 出包并部署到模拟器 / 真机。
# 用法见 usage()；全流程日志写 dist/deploy-<ts>.log。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAIN_BRANCH="${ISPEECH_MAIN_BRANCH:-main}"
JAVA_HOME_DEFAULT="${ISPEECH_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
AVD_NAME="${ISPEECH_AVD:-ispeech_test}"
IME_ID="com.ispeech.ime/com.ispeech.input.IspeechImeService"
PKG="com.ispeech.ime"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIST_DIR="${REPO_ROOT}/dist"
LOG="${DIST_DIR}/deploy-${STAMP}.log"

BUILD_TYPE="debug"
TARGET="auto"
DO_BUILD=1
SKIP_VERIFY=0
MERGE_ALL=0

info() { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
用法: tools/deploy/deploy.sh [选项]

  --release           打 release 包（默认 debug）
  --target auto|emulator|phone|all|serial:<s>   部署目标（默认 auto：有真机优先真机，否则模拟器）
  --no-build          不重新构建，复用最近的 dist 包
  --skip-verify       跳过静态校验（会显眼告警）
  --merge-all         出包前先 worktree.sh land-all 合并所有任务分支
  -h, --help          显示帮助
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --release) BUILD_TYPE="release" ;;
        --target) TARGET="${2:-}"; shift ;;
        --target=*) TARGET="${1#*=}" ;;
        --no-build) DO_BUILD=0 ;;
        --skip-verify) SKIP_VERIFY=1 ;;
        --merge-all) MERGE_ALL=1 ;;
        -h|--help) usage; exit 0 ;;
        *) usage; die "未知参数: $1" ;;
    esac
    shift
done

mkdir -p "${DIST_DIR}" "${DIST_DIR}/screenshots"
# 全流程日志：stdout/stderr 同时进日志
exec > >(tee -a "${LOG}") 2>&1

info "日志：${LOG}"

# ---------- 工具链 ----------

resolve_sdk() {
    local sdk="" lp="${REPO_ROOT}/local.properties"
    if [ -f "${lp}" ]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "${lp}" | head -1)"
        [ -d "${sdk}" ] || sdk=""
    fi
    if [ -z "${sdk}" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then sdk="${ANDROID_HOME}"; fi
    if [ -z "${sdk}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then sdk="${ANDROID_SDK_ROOT}"; fi
    if [ -z "${sdk}" ]; then
        for cand in /opt/homebrew/share/android-commandlinetools "${HOME}/Library/Android/sdk"; do
            [ -d "${cand}" ] && { sdk="${cand}"; break; }
        done
    fi
    printf '%s' "${sdk}"
}

SDK="$(resolve_sdk)"
if [ -n "${SDK}" ]; then
    # 本机 local.properties 坏了就顺手修好（该文件 gitignore，不入库）
    if ! grep -q "^sdk.dir=${SDK}$" "${REPO_ROOT}/local.properties" 2>/dev/null; then
        printf 'sdk.dir=%s\n' "${SDK}" > "${REPO_ROOT}/local.properties"
        info "已修正 local.properties → sdk.dir=${SDK}"
    fi
    ADB="${SDK}/platform-tools/adb"
    EMULATOR_BIN="${SDK}/emulator/emulator"
else
    ADB="$(command -v adb || true)"
    EMULATOR_BIN="$(command -v emulator || true)"
fi
[ -n "${ADB}" ] && [ -x "${ADB}" ] || die "找不到 adb：请设置 ANDROID_HOME 或把 platform-tools 加入 PATH"

if [ -d "${JAVA_HOME_DEFAULT}" ]; then
    export JAVA_HOME="${JAVA_HOME_DEFAULT}"
elif [ -n "${JAVA_HOME:-}" ]; then
    warn "未找到 ${JAVA_HOME_DEFAULT}，沿用 JAVA_HOME=${JAVA_HOME}"
else
    die "需要 JDK 17：安装后设 ISPEECH_JAVA_HOME，或 export JAVA_HOME=/path/to/jdk17"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
info "JAVA_HOME=${JAVA_HOME}"

worktree_dirty_count() { git -C "$1" status --porcelain --untracked-files=no 2>/dev/null | wc -l | tr -d ' '; }

# ---------- ① 分支与工作区 ----------

BRANCH="$(git -C "${REPO_ROOT}" symbolic-ref --short HEAD 2>/dev/null || echo detached)"
[ "${BRANCH}" = "${MAIN_BRANCH}" ] || die "当前分支是 ${BRANCH}，只允许从 ${MAIN_BRANCH} 出包。请先 tools/dev/worktree.sh land <task-id>"
[ "$(worktree_dirty_count "${REPO_ROOT}")" = "0" ] \
    || die "${MAIN_BRANCH} 工作区不干净，禁止出包。请在任务 worktree 完成开发后 land 回 main"
info "分支 ${BRANCH}，工作区干净"

# ---------- ② 合并所有任务分支 ----------

if [ "${MERGE_ALL}" = "1" ]; then
    info "先合并所有未落地的 task/* 分支"
    "${REPO_ROOT}/tools/dev/worktree.sh" land-all
fi

# ---------- ③ 静态校验 ----------

if [ "${SKIP_VERIFY}" = "1" ]; then
    warn "=============================================="
    warn " 已按要求跳过静态校验（--skip-verify）"
    warn " 本次部署的代码未经过 check_* 验证，风险自负"
    warn "=============================================="
else
    FAILED=""
    for s in static input_logic t9 stats phrase_usage pinyin crosscut quick_trigger; do
        if python3 "${REPO_ROOT}/tools/verify/check_${s}.py" >/dev/null 2>&1; then
            info "check_${s} PASS"
        else
            warn "check_${s} FAIL"
            FAILED="${FAILED} ${s}"
        fi
    done
    [ -z "${FAILED}" ] || die "静态校验失败:${FAILED}（修好后重试，或 --skip-verify 强制跳过）"
fi

# ---------- ④ 构建 ----------

VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "${REPO_ROOT}/app/build.gradle.kts" | head -1)"
[ -n "${VERSION_NAME}" ] || VERSION_NAME="unknown"
TASK_NAME="assembleDebug"
[ "${BUILD_TYPE}" = "release" ] && TASK_NAME="assembleRelease"
APK_OUT="${REPO_ROOT}/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"

if [ "${DO_BUILD}" = "1" ]; then
    info "构建 ${TASK_NAME}（versionName=${VERSION_NAME}）"
    ( cd "${REPO_ROOT}" && ./gradlew --console=plain "${TASK_NAME}" )
else
    info "--no-build：跳过构建"
fi
[ -f "${APK_OUT}" ] || die "找不到构建产物：${APK_OUT}"

# ---------- ⑤ 归档到 dist ----------

APK="${DIST_DIR}/ispeech-${BUILD_TYPE}-${VERSION_NAME}-$(date +%Y%m%d-%H%M).apk"
if [ -f "${APK}" ]; then
    APK="${APK%.apk}-${STAMP}.apk"
fi
cp "${APK_OUT}" "${APK}"
APK_SHA="$(shasum -a 256 "${APK}" | awk '{print $1}')"
APK_SIZE="$(wc -c < "${APK}" | tr -d ' ')"
info "APK：${APK}"
info "大小：${APK_SIZE} 字节"
info "sha256：${APK_SHA}"

# ---------- ⑥ 解析设备 ----------

list_devices() { "${ADB}" devices | awk 'NR>1 && $2=="device"{print $1}'; }
is_emulator() { case "$1" in emulator-*) return 0 ;; *) return 1 ;; esac; }

wait_boot() {
    local serial="$1" i=0
    info "等待 ${serial} 启动完成…"
    "${ADB}" -s "${serial}" wait-for-device
    while [ "${i}" -lt 120 ]; do
        [ "$("${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
        sleep 2; i=$((i + 1))
    done
    die "${serial} 启动超时（240s）"
}

start_emulator() {
    local i=0 serial=""
    EMULATOR_BIN="${EMULATOR_BIN:-$(command -v emulator || true)}"
    [ -n "${EMULATOR_BIN}" ] && [ -x "${EMULATOR_BIN}" ] || die "找不到 emulator（设置 ANDROID_HOME 或 ISPEECH_AVD 所在 SDK）"
    info "模拟器未运行，拉起 AVD ${AVD_NAME}"
    nohup "${EMULATOR_BIN}" -avd "${AVD_NAME}" -no-snapshot -no-audio -no-boot-anim \
        > "${DIST_DIR}/emulator-${STAMP}.log" 2>&1 &
    while [ "${i}" -lt 150 ]; do
        serial="$(list_devices | grep '^emulator-' | head -1 || true)"
        [ -n "${serial}" ] && break
        sleep 2; i=$((i + 1))
    done
    [ -n "${serial}" ] || die "模拟器 300s 内未出现，查看 ${DIST_DIR}/emulator-${STAMP}.log"
    wait_boot "${serial}"
    printf '%s' "${serial}"
}

resolve_targets() {
    local want="$1" all emus phones s
    all="$(list_devices)"
    emus="$(printf '%s\n' "${all}" | grep '^emulator-' || true)"
    phones="$(printf '%s\n' "${all}" | grep -v '^emulator-' | grep -v '^$' || true)"

    case "${want}" in
        serial:*)
            s="${want#serial:}"
            printf '%s\n' "${all}" | grep -qx "${s}" || die "设备 ${s} 不在线：$(printf '%s ' ${all})"
            echo "${s}"; return 0 ;;
        emulator)
            if [ -z "${emus}" ]; then emus="$(start_emulator)"; fi
            printf '%s\n' "${emus}" | grep -v '^$' ;;
        phone)
            [ -n "${phones}" ] || {
                warn "当前没有真机。已连接设备："
                "${ADB}" devices -l
                warn "接真机：USB 调试打开后执行 adb devices；无线：adb tcpip 5555 && adb connect <手机IP>:5555"
                die "未找到真机"
            }
            printf '%s\n' "${phones}" ;;
        all)
            if [ -z "${emus}" ]; then emus="$(start_emulator)"; fi
            { printf '%s\n' "${emus}"; printf '%s\n' "${phones}"; } | grep -v '^$' ;;
        auto)
            if [ -n "${phones}" ]; then printf '%s\n' "${phones}"
            elif [ -n "${emus}" ]; then printf '%s\n' "${emus}"
            else start_emulator; fi ;;
        *) die "未知 --target: ${want}" ;;
    esac
}

# ---------- ⑦⑧⑨ 安装 / 切换输入法 / 截图 ----------

deploy_one() {
    local serial="$1" ime_now shot
    info "[${serial}] 安装 APK"
    "${ADB}" -s "${serial}" install -r "${APK}"

    info "[${serial}] 启用输入法 ${IME_ID}"
    "${ADB}" -s "${serial}" shell ime enable "${IME_ID}" || true
    info "[${serial}] 切换默认输入法"
    "${ADB}" -s "${serial}" shell ime set "${IME_ID}"
    sleep 1
    ime_now="$("${ADB}" -s "${serial}" shell settings get secure default_input_method | tr -d '\r')"
    info "[${serial}] default_input_method=${ime_now}"
    [ "${ime_now}" = "${IME_ID}" ] || warn "[${serial}] 回读与预期不一致，可能被系统其它输入法抢占，请在设置里手动确认"

    # 打开输入法设置页，便于截到有意义的画面（失败不致命）
    "${ADB}" -s "${serial}" shell am start -a android.settings.INPUT_METHOD_SETTINGS >/dev/null 2>&1 || true
    sleep 2
    shot="${DIST_DIR}/screenshots/deploy-${STAMP}-${serial}.png"
    if "${ADB}" -s "${serial}" exec-out screencap -p > "${shot}" 2>/dev/null; then
        info "[${serial}] 截图：${shot}（$(wc -c < "${shot}" | tr -d ' ') 字节）"
    else
        warn "[${serial}] 截图失败"
        rm -f "${shot}"
    fi

    info "[${serial}] 已安装包信息："
    "${ADB}" -s "${serial}" shell dumpsys package "${PKG}" 2>/dev/null \
        | grep -E 'versionName|versionCode|firstInstallTime' | head -3 || true
}

TARGETS="$(resolve_targets "${TARGET}")"
[ -n "${TARGETS}" ] || die "没有可用设备"
for serial in ${TARGETS}; do
    deploy_one "${serial}"
done

info "完成。APK=${APK}"
info "sha256=${APK_SHA}"
info "日志=${LOG}"