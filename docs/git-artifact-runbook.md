# Git 产物体积治理 Runbook（VOCA-445）

状态：方案已成文，**历史重写类操作未执行**，等人类确认。
适用范围：`git@github.com:forcoder/ispeech.git`，本地共享仓库 `/Users/cookie/workspace/ispeech`（worktree 模式）。

## 一、结论

**远端历史没有被污染，不需要 `git filter-repo`，也不需要为了「能推送」而迁 LFS。**

污染只发生在**本地**：Multica 运行时每次 run 会把工作区里未忽略的文件自动提交成
`chore(agent): baseline — uncommitted work from the local directory` / `multica: local directory snapshot`
这类基线提交。`tools/deploy/deploy.sh:158` 产出的部署包命名是
`dist/ispeech-<type>-<version>-<YYYYMMDD>-<HHMM>.apk`，这个形式 `.gitignore` 没覆盖，
于是 132.7 MiB 的包被基线提交写进分支历史 → 超过 GitHub 单文件硬上限 → 该分支整体推不上去。

所以真正的修复是**堵住入口（`.gitignore` 白名单）**＋**把已经困在本地的在途分支重建成干净历史**，
而不是去改写所有人都在用的远端历史。

## 二、证据（2026-11-17 实测，均可复跑）

| # | 事实 | 命令 | 结果 |
| --- | --- | --- | --- |
| E1 | 阻断文件 | `git cat-file -s d0a15bc6` | `139171786` B = **132.7 MiB** |
| E2 | 远端 43 个分支全部干净 | 见下方审计脚本（`refs/remotes/origin`） | `refs/remotes/origin` 44 个 ref（43 个分支 + `origin/HEAD`）逐个跑，**0 个** >100 MiB 可达 blob |
| E3 | `main` 干净 | `git rev-list --objects origin/main \| …` | **0 个** >100 MiB blob；可达最大 blob 是 `dist/ispeech-debug.apk` 22,795,894 B |
| E4 | 污染只在本地分支 | 同一审计脚本跑 `refs/heads` | 当场复跑 **50 / 84**（其中 10 个远端有对应分支、40 个只在本地）；本单首次实测为 49 / 82，增量是并发新建的 worktree 分支 |
| E5 | 污染来源是平台基线提交 | `git log --all --format='%h %s' -- dist/ispeech-release-1.5-20261115-1004.apk` | 命中提交标题清一色是 `chore(agent): …uncommitted work…` / `multica: local directory snapshot` |
| E6 | 98.4 MiB 曾经推上去过 | `git branch -a --contains 199b972` | **两条**远端分支携带该包：`origin/release/voca-186-v1.6`、`origin/chore/voca-121-dist-readme-gate`，说明**硬上限在 98.4 与 139.2 MiB 之间**（GitHub 文档口径 100 MiB）；50 MiB 以上只告警不拦截 |
| E7 | 基线提交尊重 `.gitignore` | `git diff --stat main HEAD` | 只多出那个 APK；`.multica/`、`AGENTS.md`、`CLAUDE.md` 等被忽略的文件没进树 → **改 `.gitignore` 就能止住** |
| E8 | 只删文件不解决问题 | `7d4b51e chore(pm): 摘除平台自动带入的 139 MB 构建包，恢复本分支可推送` | 该分支 tip 至今仍在 E4 的污染清单里（blob 留在自己的历史中） |
| E9 | 本仓库没有 CI | `ls .github/workflows` | 不存在 → 「CI 能正常拉取」这条验收项当前没有对象，见第六节缺口 |
| E10 | 体积底数 | `du -sh $(git rev-parse --git-common-dir)` | 共享 `.git` 174 MB；`main` tracked 的 8 个 APK 合计 137,896,366 B ≈ 131.5 MiB |
| E11 | 第一版白名单自身是回退（质检发现） | `git check-ignore -q dist/ispeech-debug-1.5-20261117-1530.apk` → 非 0 | 第一版的 `!dist/ispeech-debug*.apk` / `!dist/ispeech-release-v*.apk` 通配把 `deploy.sh` **默认**（`--release` 才转 release，`:17 BUILD_TYPE="debug"`）产物重新放开；`main` 旧规则 `dist/ispeech-debug-*.apk` 本来是忽略的。整改见 P0-A′ |

审计脚本（治理前后各跑一次，输出为空即合格）：

```bash
# 把 refs/remotes/origin 换成 refs/heads 即检查本地分支
git for-each-ref --format='%(refname:short)' refs/remotes/origin | while read -r r; do
  git rev-list --objects "$r" \
    | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
    | awk -v b="$r" '$1=="blob" && $3>104857600 {print "POLLUTED", b, $3, $4; exit}'
done
```

分支计数是**活仓库**上的快照：三次实测之间本地分支 79 → 82 → 84、污染分支 46 → 49 → 50
（其他 agent 的 worktree 正在并发建分支，每个新 run 的基线提交又带走一次污染）。因此所有分支数量只作量级参考，
**执行时一律以审计脚本当场输出为准**；结论（远端 0 个巨型 blob）在两次实测中均成立。

## 三、方案对比与推荐

| | A. `git filter-repo` 重写远端历史 | B. 迁 Git LFS | **C. 堵入口 + 重建在途分支（推荐）** |
| --- | --- | --- | --- |
| 解决的是 | 远端历史里的巨型 blob | 仓库长期体积 | 实际发生的问题 |
| 必要性 | **不必要**：E2/E3 证明远端从来没有过这个 blob | 非阻塞项，属于另一件事 | 必要且充分 |
| 代价 | 全员重新 clone；43 个远端分支 + 全部在途分支重建；所有人本地 reflog/其他 worktree 都要清；已有 PR/issue 引用 SHA 全部失效 | 需要客户端装 LFS、远端配额；把 131.5 MiB 历史 APK 转指针同样要重写历史；CI/构建机拉取链路要改 | 一次 `.gitignore` 提交 + 逐分支 cherry-pick 重建（第四节） |
| 可逆性 | 不可逆（远端 ref 被强推覆盖，旧对象一旦被 GC 无法找回） | 不可逆（同上） | 完全可逆：`git revert` 一个提交 |
| 风险 | 高：与「禁止 force push 共享分支」「统一合并窗口」直接冲突 | 中高：配额与拉取配置未验证；且当前不需要 | 低 |
| 结论 | ❌ 拒绝 | ⏸ 降级为 P2 择期评估 | ✅ 执行 |

选 A 的唯一理由是「远端已污染」，而 E2/E3 已经把它证伪了。在没有污染的仓库上做历史重写，是拿全员重 clone 的确定代价去换一个不存在的收益。

## 四、执行清单

### P0-A：堵住入口（本单已完成，无需人类确认，可正常评审合并）

`.gitignore` 里 dist/ 段改成白名单：`dist/*` 全忽略，只放开
`README.md` / `SHA256SUMS` / 精确到交付包形状的 `ispeech-<type>-vN.M.apk`（`_archive` 同口径）。
效果：带时间戳的部署包、`internal` 内测包、日志、截图、任何新形态产物默认进不了工作区索引；
按 `dist/README.md` 声明的交付包照旧可提交；确要提交例外用 `git add -f`。
已 tracked 的文件不受 `.gitignore` 影响，历史与现有包不动。

### P0-A′：白名单收严到交付包形状（质检打回项 1 整改，随 P0-A 同一提交面合并）

第一版用 `!dist/ispeech-debug*.apk`、`!dist/ispeech-release-v*.apk` 放开交付包，通配同时放开了
`deploy.sh` 的默认产物 `dist/ispeech-debug-1.5-<YYYYMMDD>-<HHMM>.apk`（E11），相对 `main` 是回退。
现在放开项只匹配「`v` + 数字.数字」的交付包命名，时间戳形态一律落回 `dist/*` 忽略：

```
!dist/ispeech-debug.apk
!dist/ispeech-release-v[0-9].[0-9].apk        （另有 [0-9][0-9] minor 一档，覆盖 v1.10）
!dist/ispeech-debug-v[0-9].[0-9].apk
!dist/_archive/ispeech-debug.apk / ispeech-debug-v[0-9].[0-9].apk
!dist/_archive/ispeech-release-v[0-9].[0-9].apk / -build[0-9].apk
```

不改用质检建议的 `v[0-9].apk` / `v[0-9][0-9].apk`：gitignore 里 `.` 是字面量，这两条匹配的是
`ispeech-release-v1.apk` 这种名字，匹配不到 `dist/README.md` 声明的 `ispeech-release-v1.5.apk`，
`v1.0 / v1.3 / v1.5` 三个在库交付包会被 `dist/*` 吃掉（实测见第六节第 1 条 b）。

质检风险 2 点名的 3 个 `origin/release/voca-186-v1.6` 上 tracked 的产物，处置口径：

| 文件 | 现在是否在白名单内 | 统一合并窗口要做什么 |
| --- | --- | --- |
| `dist/_archive/ispeech-debug.apk` | 是（P0-A′ 放开） | 同名替换可直接提交，无需 `-f` |
| `dist/_archive/ispeech-debug-v1.0.apk` | 是（P0-A′ 放开） | 同上 |
| `dist/ispeech-internal-v1.6.apk`（98.4 MiB 内测包） | **否，故意不放开** | 它就是 E6 的下一次翻车候选。tracked 优先于 ignore，合进 main 不会丢文件；**更新或换名时必须 `git add -f`**，请在合并清单里点名这一条 |

### P0-B：捞出困在污染分支上的真实改动（需人类确认后执行）

在途分支的未推送工作（截至 E4/E5 实测；**前两条经本轮复核属「已发布」，不困在本地**，判定命令见下表之后）：

| 分支 | 远端已有 | 未推送提交 | 其中有实际内容的 | 是否与远端分叉 |
| --- | --- | --- | --- | --- |
| `agent/agent/voca-231` | 是 | 8（本地领先） | **0** — 7 个真实提交（`feat(voice)`/`fix(voice)`/`fix(packaging)`/`docs(voice)`）`git cherry` 全为 `-`，本地独有的只有基线提交 `64a9d88` | **已发布，勿再 cherry-pick**：等价工作已在远端 tip `00fddfa`，交付走 **PR #12**（OPEN / base main / MERGEABLE）→ **本行不需 rescue** |
| `agent/agent/voca-207` | 是 | 5（本地领先） | **0** — 4 个真实提交（`fix(sync)`/`docs(adr)`/`docs(quick)`/`chore(verify)`）`git cherry` 全为 `-`，本地独有的只有基线提交 `0753c13` | **已发布，勿再 cherry-pick**：等价工作已在远端 tip `4d6363c`，交付走 **PR #11**（OPEN / MERGEABLE）→ **本行不需 rescue** |
| `agent/agent/voca-37` | 是 | 7 | 5（`docs(pm)` 含风险册记录；`7d4b51e` 摘包提交**不要**带） | 否 |
| `agent/ui/voca-69` | 是 | 5 | 4（`fix(ui)`/`docs(ui)` 质检整改） | 否 |
| `agent/ux/voca-45` | 是 | 5 | 3（`docs(ux)` 质检整改；`898dbea` 摘包提交**不要**带） | 否 |
| `agent/ba/voca-122` | 是 | 3 | 2（`docs(requirements)`） | 否 |
| `agent/agent/voca-121` / `-33` / `-56` / `agent/mika/voca-30` | 是 | 1–2 | 0（只有平台基线提交，无实际工作） | 否 |
| 其余 40 个被污染的本地分支（E4 当场口径） | 否 | — | 远端无对应分支，按 P0-A 合并后重建即可 | — |

**要不要 rescue 以补丁等价性判定，不以本地领先提交数判定**（本单第一轮就是按 `git rev-list --count` 的本地领先数把已发布的 231/207 误列进待救回清单）：

```bash
for B in agent/agent/voca-231 agent/agent/voca-207 agent/agent/voca-37 agent/ui/voca-69 agent/ux/voca-45 agent/ba/voca-122; do
  echo "--- $B ---"
  git cherry -v "origin/$B" "$B" | grep '^+' | grep -vE 'chore\(agent\)|multica:|local directory'
done
# 期望：231 / 207 两行为空（真实提交已等价在远端，只需合各自 PR）；其余四条列出自己的真实提交
```

过滤条件要挡住**两种**平台基线提交标题——`chore(agent): baseline — …local directory` 与
`chore(agent): uncommitted work from the local directory since the previous turn`；
只 grep 掉 `baseline` 会把后一种放过，导致 `voca-207` 输出一行基线提交、看起来像「还有未救回的工作」（本单实测就是这样）。

`git cherry` 的 `-` 表示远端已有**等价补丁**（同一改动以另一组 SHA 落在远端 tip 上：231 的 7 个同名提交在 `00fddfa`、207 的 4 个在 `4d6363c`），
这类分支只等自己的 PR 被合进 main，**不能再 cherry-pick 一遍**（那会把同一份改动提交两次）；`+` 才是真困在本地的工作。

分叉判定用 `git rev-list --count origin/$B..$B`（本地领先）与 `git rev-list --count $B..origin/$B`
（远端领先），两个都不为 0 即已分叉。**FF 性由模板结构保证，而不是由本地分支保证**：
必须从 `origin/$B` 开 rescue 分支再 cherry-pick 真实提交，这样远端 tip 才是新历史的祖先。
上表 4 条待 rescue 的分支都**未分叉**，但「未分叉」不等于可以直推本地分支：本地历史里带着平台基线提交的那个 139,171,786 B blob
（`git rev-list --objects $B | … >104857600` 对本单点名的 4 条全部命中），pre-receive 按体积拒收的是**整条历史**，不是 tip 的树。

单分支重建模板，**只适用上表 `git cherry` 出 `+` 的 4 条**（`voca-231` / `voca-207` 已发布，不走本模板）；
fast-forward，不需要 force push，因为远端 tip 是新历史的祖先：

```bash
B=agent/agent/voca-37
git fetch origin "$B"
git switch -c "rescue/${B#*/}" "origin/$B"
# 只挑真实提交，跳过 chore(agent)/multica: 平台基线提交，也跳过摘包提交（用 E5 的 log 对照 SHA）
git cherry-pick a293b64 a039964 c443f73 7ace621 3a010fe
# 推送前自检：新历史必须为空输出
git rev-list --objects HEAD | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
  | awk '$1=="blob" && $3>104857600'
git push origin HEAD:"$B"     # fast-forward
```

4 条分支各自的 `cherry-pick` 清单（按 `git log --reverse` 的旧→新顺序，已剔除平台基线提交与摘包提交）：

| 分支 | 要挑的真实提交 | 明确**不**挑 |
| --- | --- | --- |
| `agent/agent/voca-37` | `a293b64` `a039964` `c443f73` `7ace621` `3a010fe` | `92e2680`（基线）、`7d4b51e`（摘包） |
| `agent/ui/voca-69` | `e8d3f6c` `5a091e3` `37ee140` `2ece128` | `a9b5a94`（基线） |
| `agent/ux/voca-45` | `e3cd1c8` `c62f77c` `4a6a4a1` | `e0f4d96`（基线）、`898dbea`（摘包） |
| `agent/ba/voca-122` | `6105cd7` `b8e8d40` | `212de54`（基线） |

逐分支跑完后，由项目经理在统一合并窗口按 issue 顺序 squash 合并；**合并目标仍是 main，不改 main 的历史**。
`voca-231` / `voca-207` 不进这个循环——它们没有待救回的工作，合并窗口直接收 PR #12 / PR #11 的现有 head。

### P0-C：清理本地对象库（需人类确认，共享仓库动作）

```bash
# 1. 先确认没有 worktree 还挂在待删分支上
git worktree list
# 2. 删掉已重建/无价值的污染分支（逐个点名，不用通配）
git branch -D agent/agent/voca-231   # 231/207 的本地分支独有部分只剩平台基线提交，工作已在 PR #12/#11 → 正是「无价值」那一类
# 3. 全部清完再回收（这一步会让本地旧对象不可恢复，务必在 P0-B 推送成功之后）
git -c gc.reflogExpire=now reflog expire --expire=now --all
git gc --prune=now
du -sh "$(git rev-parse --git-common-dir)"
```

顺序不可颠倒：**先推送救回的工作，再 GC**。GC 前所有污染 ref 都必须已删除，否则对象仍被引用、回收不掉。

### P1：预防（建议新开 issue）

1. `pre-push` 钩子或脚本：拒绝任何 >95 MiB 的新 blob（当前仓库没有 `core.hooksPath` 基础设施，需要单独建）。
   白名单是**按名字**的防线，换个命名或换类产物就绕过去了；体积门禁才是兜底，建议与 P0-A 同期开单。
2. `tools/deploy/deploy.sh` 出包时打印一行「该包不入库」提示，并把交付包与部署包的命名空间彻底分开。
3. 若平台侧可配置，让运行时基线提交跳过 `dist/`（当前 `.gitignore` 已能挡住，属于加一层保险）。

## 五、回滚预案

| 变更 | 触发回滚的条件 | 回滚动作 | 预计时长 | 数据可逆 |
| --- | --- | --- | --- | --- |
| `.gitignore` 白名单（P0-A / P0-A′） | 有人发现该提交的交付包被静默忽略 / `dist/README.md` 流程跑不通 | `git revert <sha>` 即可；无历史改动 | < 5 min | 是 |
| 单分支重建（P0-B，**只适用 4 条需 rescue 的分支** voca-37 / voca-69 / voca-45 / voca-122） | cherry-pick 漏提交或引入冲突，救回分支内容不对 | 这 4 条远端 `<B>` 的 tip 未被本单写过，删掉 rescue 分支重来即可；先记下旧 tip `git rev-parse "origin/$B"` 备查。**⚠️「远端 tip 从未被移动过」对 `voca-231` / `voca-207` 不成立**：它们的真实提交 11-16 就已推到远端（`git reflog show origin/agent/agent/voca-231` 末条 `update by push` @ 11-16 21:57:49 CST → 现 tip `00fddfa`；207 为 11-16 11:05:57 CST → `4d6363c`，两条即 PR #12 / #11 的 head），对它们做备查或回退**必须以远端现状为基线**，不能拿本地旧 tip 当回滚点；本单也不对它们执行本行 | < 10 min | 是（4 条远端未被本单写过）；231/207 的工作已在远端，走各自 PR 的正常流程 |
| 本地 GC（P0-C） | 事后发现有未推送工作被 prune 掉了 | **不可逆**，只能从 `git fsck --lost-found` 捞悬空提交，不保证成功 | — | 否 |

正因为 P0-C 不可逆，它必须排在 P0-B 全部推送成功、且人类逐分支确认之后。

灰度顺序（变更三重门）：先只合改 `.gitignore` 的那几个提交（当场以 `git log --oneline origin/main..origin/<交付分支> -- .gitignore` 数，本单为 P0-A + P0-A′ 两条）→ 用第六节口径验证新分支可推 → 再按上表从「0 个实际工作」的分支开始清 → 最后做 P0-C。

## 六、验证口径（治理完成的判定）

1. **入口已堵**（a、b 两条都要过，只测一种命名不算过）：

   a) 4 种时间戳/危险形态必须全部被忽略（`deploy.sh` 默认 debug、显式 `--release`、带 `v` 的误写、
   以及 `internal` 内测包）：

   ```bash
   for f in dist/ispeech-debug-1.5-20261117-1530.apk dist/ispeech-release-1.5-20261117-1530.apk \
            dist/ispeech-release-v1.5-20261117-1530.apk dist/ispeech-internal-v1.6.apk; do
     git check-ignore -q "$f" || echo "LEAK $f"; done   # 期望：无输出
   ```

   b) 在库交付包必须仍照旧可提交。**必须加 `--no-index`**：`git check-ignore` 默认查索引，
   tracked 文件永远返回「未忽略」，不加这个标志的测试是假通过（E11 就是这么漏掉的）：

   ```bash
   for f in $(git ls-tree -r --name-only HEAD -- dist/); do
     git check-ignore -q --no-index "$f" && echo "WRONGLY IGNORED $f"; done   # 期望：无输出
   ```

   c) 全链路自检：在留有部署包残留的工作区里跑 `git status --porcelain` 输出为空（只剩本次改动），
   `git add -A --dry-run` 不收入任何 `dist/` 产物；对危险名 `git add <file>` 必须被拒并提示 `-f`。
2. **新分支可推**（本单已实测）：从 `origin/main` 出发、只带本改动的分支
   `chore/voca-445-dist-artifact-guard` 推送成功、PR #27 可建 → 证明阻断点已消失。
   提交数**不写死**——写死的数字每轮整改都会被下一轮自身推翻（本条前两轮分别写的是 3 与 4）。
   当场口径：`git rev-list --count origin/main..origin/chore/voca-445-dist-artifact-guard`。
   取物一律按 `origin/…` 或显式 SHA：本机的**同名本地分支**可能停在整改前的旧点（本轮实测本地停在 `b587d18`、落后远端），
   按本地分支名 `git clone -b` 会拿到旧版本并当场复现 E11 的 2 条 LEAK。
   执行前先 `git fetch --prune` 再比对 `git rev-parse <name> "origin/<name>"`，不一致就以远端为准。
3. **历史无巨型对象**：第二节审计脚本对 `refs/heads` 与 `refs/remotes/origin` 均输出为空。
4. **困住的工作已救回**：分两类，口径不同——
   - **4 个需 rescue 的分支**（`voca-37` / `ui/voca-69` / `ux/voca-45` / `ba/voca-122`）：rescue 结果在远端可见，
     且 `git log origin/<B> --oneline | head` 包含对应的 `fix(...)` / `docs(...)` 提交；
   - **2 个已发布的分支**（`agent/agent/voca-231` → PR #12、`agent/agent/voca-207` → PR #11）：
     工作早已等价在远端（`git cherry` 全 `-`），这一类**验收看 PR 是否被合进 main**，不产 rescue 分支；
     拿它们做 cherry-pick 会把同一份改动提交两遍。
5. **CI 能正常拉取**：⚠️ **本仓库当前没有 CI**（E9：无 `.github/workflows`）。这条验收项现在没有对象，
   不能填「已通过」。要么把 P1-1 的体积门禁接到 CI 上再验，要么把该条改为「构建机
   （`cookie@43.136.44.72:10010`）能 `git fetch` 到 main 并完成一次 `assembleRelease`」，请项目经理定口径。

## 七、待确认项

- GitHub 单文件硬上限的精确值（100 MiB 还是 104,857,600 B）只由 E6 夹逼到 `(98.4, 139.2] MiB`，
  不影响本方案结论；若走 P2 的 LFS，需要人类确认组织 LFS 存储/带宽配额与计费口径后再动。
- `main` 已 tracked 的 131.5 MiB 历史 APK 是否长期留在 git 里（P2 议题）：影响 clone 速度，
  但不阻塞任何推送；本单不扩范围处理。
- 40 个「远端无对应分支」的污染分支（E4 当场口径）是否还有人在用，需要各 issue owner 认领后再删（P0-C 前置）。
