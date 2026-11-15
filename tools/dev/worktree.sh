#!/usr/bin/env bash
# iSpeech worktree 工作流：一个任务 = 一个任务分支 + 一个独立 worktree。
# 子命令：new / status / land / land-all / remove
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAIN_BRANCH="${ISPEECH_MAIN_BRANCH:-main}"
WT_ROOT="${ISPEECH_WT_ROOT:-${HOME}/workspace/_wt/ispeech}"
TRAILER="Co-Authored-By: Claude Code <noreply@anthropic.com>"

info() { printf '\033[1;34m[worktree]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
用法: tools/dev/worktree.sh <子命令> [参数]

  new <task-id> [slug]          从 origin/main（fetch 失败退回本地 main）建 task/<id>[-slug]
  status                        列出所有 worktree：分支 / 路径 / 脏改动 / 相对 main 的 ahead-behind
  land <task-id> [message]      在 main 工作区把任务分支 squash 合并（要求 main 干净）
  land-all                      按创建顺序合并所有未合并的 task/* 分支
  remove <task-id> [--force]    删除 worktree 与分支（未合并时必须 --force）

环境变量:
  ISPEECH_WT_ROOT      worktree 根目录（默认 ~/workspace/_wt/ispeech）
  ISPEECH_MAIN_BRANCH  主干分支名（默认 main）
EOF
}

# ---------- 公共 ----------

main_worktree_root() {
    local common
    common="$(git -C "${REPO_ROOT}" rev-parse --git-common-dir)"
    case "${common}" in
        /*) ;;
        *) common="${REPO_ROOT}/${common}" ;;
    esac
    ( cd "${common}/.." && pwd -P )
}

# 本机没配 user.name/user.email 时，沿用仓库上一次提交的身份，避免合并在半路失败
ensure_git_identity() {
    [ -n "$(git -C "${REPO_ROOT}" config user.email || true)" ] && return 0
    local name email
    name="$(git -C "${REPO_ROOT}" log -1 --format=%an)"
    email="$(git -C "${REPO_ROOT}" log -1 --format=%ae)"
    [ -n "${email}" ] || die "git 身份未配置，且无法从历史提交推断；请 git config user.email ..."
    export GIT_AUTHOR_NAME="${name}" GIT_AUTHOR_EMAIL="${email}"
    export GIT_COMMITTER_NAME="${name}" GIT_COMMITTER_EMAIL="${email}"
    warn "未配置 git 身份，沿用历史提交身份：${name} <${email}>"
}

# 已跟踪文件是否有改动（dist 产物、日志等未跟踪文件不算脏）
worktree_dirty_count() {
    git -C "$1" status --porcelain --untracked-files=no 2>/dev/null | wc -l | tr -d ' '
}

ensure_local_properties() {
    local dir="$1" sdk="" lp="$1/local.properties" main_lp
    main_lp="$(main_worktree_root)/local.properties"
    if [ -f "${main_lp}" ]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "${main_lp}" | head -1)"
        [ -d "${sdk}" ] || sdk=""
    fi
    if [ -z "${sdk}" ] && [ -f "${lp}" ]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "${lp}" | head -1)"
        [ -d "${sdk}" ] || sdk=""
    fi
    if [ -z "${sdk}" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
        sdk="${ANDROID_HOME}"
    fi
    if [ -z "${sdk}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then
        sdk="${ANDROID_SDK_ROOT}"
    fi
    if [ -z "${sdk}" ]; then
        for cand in /opt/homebrew/share/android-commandlinetools "${HOME}/Library/Android/sdk"; do
            [ -d "${cand}" ] && { sdk="${cand}"; break; }
        done
    fi
    [ -n "${sdk}" ] || die "找不到 Android SDK：请设置 ANDROID_HOME，或在主仓 local.properties 写 sdk.dir=/path/to/Android/sdk"
    printf 'sdk.dir=%s\n' "${sdk}" > "${lp}"
    info "local.properties → sdk.dir=${sdk}"
}

branch_worktree_path() {
    git -C "${REPO_ROOT}" worktree list --porcelain | awk -v want="branch refs/heads/$1" '
        /^worktree /{p=substr($0,10)}
        $0==want{print p; exit}'
}

pick_base_ref() {
    local remote="origin/${MAIN_BRANCH}" local_main="${MAIN_BRANCH}" fetched=0
    if git -C "${REPO_ROOT}" fetch --quiet origin "${MAIN_BRANCH}" 2>/dev/null \
        && git -C "${REPO_ROOT}" rev-parse --verify --quiet "${remote}" >/dev/null; then
        fetched=1
    else
        warn "fetch origin/${MAIN_BRANCH} 失败（离线或无远端），使用本地 ${MAIN_BRANCH}"
    fi
    # 本地 main 领先 origin/main（已 land 但未 push）时必须基于本地，否则新任务会丢改动
    if [ "${fetched}" = "1" ] \
        && git -C "${REPO_ROOT}" rev-parse --verify --quiet "${local_main}" >/dev/null \
        && [ "$(git -C "${REPO_ROOT}" rev-list --count "${remote}..${local_main}")" != "0" ]; then
        warn "本地 ${MAIN_BRANCH} 领先 ${remote}，基于本地 ${MAIN_BRANCH} 建分支"
        echo "${local_main}"
    elif [ "${fetched}" = "1" ]; then
        echo "${remote}"
    else
        echo "${local_main}"
    fi
}

require_main_clean() {
    local root
    root="$(git -C "${REPO_ROOT}" rev-parse --show-toplevel)"
    [ "${root}" = "$(main_worktree_root)" ] || die "请先在 main 工作区执行：cd $(main_worktree_root)"
    [ "$(git -C "${REPO_ROOT}" symbolic-ref --short HEAD)" = "${MAIN_BRANCH}" ] \
        || die "当前不在 ${MAIN_BRANCH}（在 $(git -C "${REPO_ROOT}" symbolic-ref --short HEAD)）；合并只能在 main 工作区做"
    [ "$(worktree_dirty_count "${REPO_ROOT}")" = "0" ] \
        || die "${MAIN_BRANCH} 工作区有未提交改动，先去任务 worktree 提交（禁止在 main 改代码）"
}

# ---------- new ----------

cmd_new() {
    local task_id="${1:-}" slug="${2:-}" branch wt_path base existing
    [ -n "${task_id}" ] || die "用法: worktree.sh new <task-id> [slug]"
    branch="task/${task_id}"
    [ -n "${slug}" ] && branch="${branch}-${slug}"
    wt_path="${WT_ROOT}/${task_id}"

    mkdir -p "${WT_ROOT}"
    git -C "${REPO_ROOT}" worktree prune

    existing="$(branch_worktree_path "${branch}")"
    if [ -n "${existing}" ]; then
        info "分支 ${branch} 已有 worktree，直接复用：${existing}"
        ensure_local_properties "${existing}"
        printf '\n  cd %s\n\n' "${existing}"
        return 0
    fi

    if git -C "${REPO_ROOT}" show-ref --verify --quiet "refs/heads/${branch}"; then
        [ -e "${wt_path}" ] && die "目录已存在但不是该分支的 worktree：${wt_path}（确认无用后自行删除或换 ISPEECH_WT_ROOT）"
        info "复用已存在分支 ${branch}"
        git -C "${REPO_ROOT}" worktree add "${wt_path}" "${branch}"
    else
        base="$(pick_base_ref)"
        [ -e "${wt_path}" ] && die "目录已存在：${wt_path}（确认无用后自行删除或换 ISPEECH_WT_ROOT）"
        info "从 ${base} 新建分支 ${branch}"
        git -C "${REPO_ROOT}" worktree add -b "${branch}" "${wt_path}" "${base}"
    fi

    ensure_local_properties "${wt_path}"
    info "worktree 就绪：${wt_path}"
    printf '\n下一步：\n  cd %s\n  # 开发、自测：python3 tools/verify/check_crosscut.py\n  # 提交后回 main 落地：tools/dev/worktree.sh land %s\n\n' "${wt_path}" "${task_id}"
}

# ---------- status ----------

cmd_status() {
    local path="" branch="" dirty ahead behind current root
    root="$(main_worktree_root)"
    current="$(git -C "${REPO_ROOT}" rev-parse --show-toplevel)"
    printf '%-44s %-9s %-7s %-16s %s\n' "BRANCH" "DIRTY" "AHEAD" "BEHIND" "WORKTREE"
    git -C "${REPO_ROOT}" worktree list --porcelain | while IFS= read -r line; do
        case "${line}" in
            "worktree "*) path="${line#worktree }" ;;
            "branch refs/heads/"*) branch="${line#branch refs/heads/}" ;;
            "")
                [ -n "${path}" ] || continue
                dirty="$(worktree_dirty_count "${path}")"
                set -- $(git -C "${REPO_ROOT}" rev-list --left-right --count "${MAIN_BRANCH}...${branch}" 2>/dev/null || echo "0 0")
                behind="$1"; ahead="$2"
                printf '%-44s %-9s %-7s %-16s %s%s\n' "${branch}" "${dirty}" "${ahead}" "${behind}" "${path}" \
                    "$([ "${path}" = "${current}" ] && echo '  (current)')"
                path=""; branch=""
                ;;
        esac
    done
}

# ---------- land ----------

has_conflict() {
    local branch="$1"
    if git merge-tree -h 2>&1 | grep -q -- '--write-tree'; then
        ! git merge-tree --write-tree "${MAIN_BRANCH}" "${branch}" >/dev/null 2>&1
    else
        return 1   # 旧版 git 无法预检，交给 git merge 自己报冲突
    fi
}

cmd_land() {
    local task_id="${1:-}" message="${2:-}" branch commits tree_diff subject
    [ -n "${task_id}" ] || die "用法: worktree.sh land <task-id> [message]"
    branch="task/${task_id}"
    git -C "${REPO_ROOT}" show-ref --verify --quiet "refs/heads/${branch}" || die "找不到分支 ${branch}"

    require_main_clean

    if git -C "${REPO_ROOT}" diff --quiet "${MAIN_BRANCH}" "${branch}"; then
        info "${branch} 与 ${MAIN_BRANCH} 无差异（已合并过），跳过"
        return 0
    fi

    commits="$(git -C "${REPO_ROOT}" rev-list --count "${MAIN_BRANCH}..${branch}")"
    [ "${commits}" != "0" ] || die "${branch} 相对 ${MAIN_BRANCH} 没有提交（改动可能还在工作区未提交）"

    if has_conflict "${branch}"; then
        die "预检发现冲突，未改动 main。请回任务 worktree：cd $(branch_worktree_path "${branch}") && git rebase ${MAIN_BRANCH} 解决后重试"
    fi

    info "squash 合并 ${branch}（${commits} 个提交）到 ${MAIN_BRANCH}"
    if ! git -C "${REPO_ROOT}" merge --squash "${branch}"; then
        git -C "${REPO_ROOT}" merge --abort 2>/dev/null || true
        die "合并失败，已尝试 abort。请回任务 worktree：cd $(branch_worktree_path "${branch}") && git rebase ${MAIN_BRANCH} 解决后重试"
    fi

    subject="${message}"
    if [ -z "${subject}" ]; then
        subject="${task_id}: $(git -C "${REPO_ROOT}" log -1 --format=%s "${branch}")"
    fi
    case "${subject}" in
        "${task_id}:"*) ;;
        *) subject="${task_id}: ${subject}" ;;
    esac

    ensure_git_identity
    git -C "${REPO_ROOT}" commit --no-verify -m "${subject}" -m "${TRAILER}" >/dev/null
    git -C "${REPO_ROOT}" log --oneline -1
    info "已落地到 ${MAIN_BRANCH}。清理：tools/dev/worktree.sh remove ${task_id}"
}

cmd_land_all() {
    local b id
    require_main_clean
    local list
    list="$(git -C "${REPO_ROOT}" for-each-ref --sort=committerdate --format='%(refname:short)' refs/heads/task/)"
    [ -n "${list}" ] || { info "没有 task/* 分支"; return 0; }
    while IFS= read -r b; do
        [ -n "${b}" ] || continue
        id="${b#task/}"
        if git -C "${REPO_ROOT}" diff --quiet "${MAIN_BRANCH}" "${b}"; then
            info "${b} 已合并，跳过"
            continue
        fi
        cmd_land "${id}"
    done <<EOF
${list}
EOF
}

# ---------- remove ----------

cmd_remove() {
    local task_id="${1:-}" force="${2:-}" branch path merged=1
    [ -n "${task_id}" ] || die "用法: worktree.sh remove <task-id> [--force]"
    branch="task/${task_id}"
    git -C "${REPO_ROOT}" show-ref --verify --quiet "refs/heads/${branch}" || die "找不到分支 ${branch}"
    path="$(branch_worktree_path "${branch}")"
    [ -n "${path}" ] || die "分支 ${branch} 没有关联 worktree（可能已移除）"

    if git -C "${REPO_ROOT}" merge-base --is-ancestor "${branch}" "${MAIN_BRANCH}" 2>/dev/null \
        || git -C "${REPO_ROOT}" diff --quiet "${MAIN_BRANCH}" "${branch}"; then
        merged=0
    fi

    if [ "${merged}" != "0" ] && [ "${force}" != "--force" ]; then
        die "${branch} 尚未合并进 ${MAIN_BRANCH}；确认要丢弃请加 --force"
    fi
    if [ "$(worktree_dirty_count "${path}")" != "0" ] && [ "${force}" != "--force" ]; then
        die "worktree 有未提交改动：${path}；确认丢弃请加 --force"
    fi

    git -C "${REPO_ROOT}" worktree remove "${path}" ${force:+--force}
    if [ "${force}" = "--force" ]; then
        git -C "${REPO_ROOT}" branch -D "${branch}"
    else
        git -C "${REPO_ROOT}" branch -d "${branch}"
    fi
    git -C "${REPO_ROOT}" worktree prune
    info "已移除 worktree 与分支 ${branch}"
}

# ---------- 入口 ----------

[ $# -ge 1 ] || { usage; exit 1; }
sub="$1"; shift
case "${sub}" in
    new)      cmd_new "$@" ;;
    status)   cmd_status "$@" ;;
    land)     cmd_land "$@" ;;
    land-all) cmd_land_all "$@" ;;
    remove)   cmd_remove "$@" ;;
    -h|--help|help) usage ;;
    *) usage; die "未知子命令: ${sub}" ;;
esac