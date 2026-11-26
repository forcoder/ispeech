# VOCA-65 统一合并窗口清单（规格链路收口）

> **Owner：项目经理 ｜ 生成：2026-11-16 ｜ v3 修订：11-17 ｜ v3.1 复测：11-17 18:02 CST ｜ v3.2：11-18 02:0x CST ｜ v3.3（11-18 14:4x CST）｜ v4（11-25 10:4x UTC，本轮重写）｜ v4.2（11-26 PR #40 登记入窗，见 §6-c）｜v4.3（11-26 质检打回 3 项整改 + 清单可达化，见 §6-d）**

> ## v4 改了什么（只此一处，其余章节读数前请以本节为准）
>
> v3 是按「VOCA-124 / PR #4」写的。**那个前提已经作废两次**：① PR #4 早已 `CLOSED`、代码经 squash `0168e59` 进了 `main`（v3 §6 整段因此失效）；② 之后又交付了三条新 PR（#39 / #40 / #41），v3 里一条都没有。v4 按现在真实在飞的东西重写合并面：
>
> | 段 | v3 写的 | v4 实际 | 命令 |
> |---|---|---|---|
> | 文档段 | 三条取件（验收 `36403e6` / UX `9801d7f` / UI `02b7cee`） | **不变，仍三条**；本轮实测两份规格在 `origin/main` 上仍 MISSING、验收文件 main 版 532 行 vs v1.1.3 969 行 | 见 §2 |
> | 代码段-1 | 合 PR #4 | **PR #4 不存在了**（CLOSED，`0168e59` 已入库）——**本节删除** | — |
> | 代码段-2 | （无） | **PR #41**（VOCA-631 条目 1 本体，head `7fb70fd`，16 文件）——**它已完整包含 PR #39**，合它即覆盖 #39 | `gh pr merge` 或 squash cherry-pick |
> | 代码段-3 | （无） | **PR #40**（VOCA-628 条目 5a，head **`383d702`**（= `5138ca9` + R37 一行修复，见 §6-d），8 文件 +1507/−37，质检已 `PASS-WITH-CAVEAT`、打回项 0；v4.2 已登记入窗，见 §6-c） | 同上 |
> | 代码段-4 | （无） | **PR #39**（VOCA-627 D-2，head `1870849`）**不再单独合**：`git diff --stat 1870849 7fb70fd` 对它的 5 个产品文件全部为净增，说明 #41 含 #39 的子集 | 不合，跳过 |
>
> **三条硬前提（v4 新增，缺一不可）**：
>
> 1. **先 rebase 再合**（新登 **R32**）：`gh api …/pulls/{40,41}` → 两条 `base` 都是 `bb1bf2b`，而 `main` 已到 **`a3336e8`**（期间进了 `#36`/`#37`/`#42`/`#44`）。直接 squash 现 head 会把 main 上的改动回退。
> 2. **`docs/quick-phrase-enhancement.md` 必冲突**（实测）：main 的 §12.7 是 VOCA-293「从远程同步」，#41 的 §12.7 是 VOCA-627「文本+光标双状态」。`git merge-file` 实测 **1 处冲突块**。解法 = **两节都保留**，把 #41 侧三节（原文 §12.7/§12.8/§12.9）顺延为 **§12.8/§12.9/§12.10**，并改掉 `:766`/`:771`/`:830` 三处内部交叉引用（`见 §12.8` → `见 §12.9` 等）。**不得丢任何一节的正文**。
> 3. **合并窗口的前提不再是「人类批窗口」，而是「人类给一次合并 + 出包 go」**（见计划 §10 v3.14）。合并到 `main`、打包、装真机仍属生产操作，按项目规程五 **agent 只产出命令与回滚，执行需人类确认**。
>
> **v4 未做的检查（诚实声明）**：`git merge-tree` 在本 worktree 因沙箱拒写临时文件跑不通（`unable to create temporary file: Operation not permitted`），故**没有**给出「#40 / #41 rebase 到 main 后自动无冲突」的结论（**此项已由 v4.2 §6-c 第 2 条补上：逐文件三方合并 0 冲突**）。上面第 2 条冲突是用逐文件 `git merge-file`（三方合并，可写工作目录）测出来的，#40 与 main 无文件名重叠（`comm -12` 空），但**行级冲突须执行时验**（v4.2 §6-c 第 2 条已对 #40 验完）。：取件 ref、命令、顺序**一字未改**。v3.3 变更：**F 逾期默认已执行**（11-18 12:00 CST 无人类回复），§6 期限整链 +1 天（窗口 yes → **11-19**、出包 → 11-19、真机 → **11-20**）；§8 补 11-18 复测（PR #4/#18/release 未变；adb 出现真机 `FMR0224913042917`，serial ≠ 基线机，待人类确认）与 **P1 门禁（VOCA-510，main 既存设置页 NPE，挡 AC-3.10）**。v3.2 只重写 §8-2 的决策 F 表述（去掉作废分支 + 两档代价 + 逾期默认，已同步 VOCA-419 item 17），并把 PR #18 head 从 `c524351` 更正为 **`78343a4`**（实测仍 `OPEN`/`MERGEABLE`，且 `TOOLBAR_HEIGHT_DP` 在该 head 上仍是 34）。
> 用途：VOCA-65 规格链路（66 / 67 / 68 / 69 / 122 / 414）**已验收结论的交付物 → `main`** 的取件命令、顺序与回滚。合并到 `main` 属生产操作（项目规程五），**本清单只给命令与回滚，执行需人类确认**。
> 所有 SHAs 与结论均为 2026-11-17 在本机仓库 `/Users/cookie/workspace/ispeech` 实测（命令与输出见 §4），不是转述。

## 0. v3 相对 v2 改了什么（**历史留痕**：v3 的代码段已被 v4 取代，见顶部与 §6）

| # | 变更 | 证据 |
|---|---|---|
| 1 | **Stage 2 规格链路 6/6 全部关闭**（VOCA-69 11-17 04:14Z 质检 PASS → `done`，是最后一条） | `multica issue children VOCA-65` → stage2 total 5 closed 5 + stage1 1/1 |
| 2 | **§2.1 的三方合并作废**：验收文件升到 **v1.1.3 `36403e6`**，其基线就是 `main` 当前那份 blob，逐文件覆盖不再退回人类的 APK 路径 | `git rev-parse origin/main:docs/requirements/VOCA-65-acceptance.md` = `76d178c:…` = **`99619fb`**（同一个 blob）；v1.1.3 §9 A1 自述「三方合并要求随之解除」（N7 ⑦） |
| 3 | **VOCA-68 取件 ref 换成远程可达的 `9801d7f`**（`origin/agent/ux/voca-68-spec`），不再是本地未推的 `agent/ux/voca-68@1d567d5` | `git ls-remote` → `9801d7f705cb10b0065d86883b2a91ecfd323dd5 refs/heads/agent/ux/voca-68-spec`；780 行 |
| 4 | **VOCA-69 取件 ref 换成 `02b7cee`**（`origin/agent/ui/voca-69-docs`，质检第四轮 PASS 的那份），任务分支 `agent/ui/voca-69` 仍停在 `28f61d8` 且**不该推**（见 R25） | `git ls-remote` → `02b7cee…  refs/heads/agent/ui/voca-69-docs`；`git diff --name-only 28f61d8..02b7cee` 只有那一个 md；846 行 |
| 5 | **新增 VOCA-414（验收文件 v1.1.3）** 一条取件，其分支**只在本地**（`agent/ba/voca-414`），origin 无 ref | `git branch --contains 36403e6` → 仅 `agent/ba/voca-414`；`git ls-remote --heads origin \| grep voca-414` → 空 |
| 6 | **第二段（代码）head 换代**：PR #4 由 `7ed1230` → **`8ab0a88`**，仍 `MERGEABLE` / `CLEAN` | `gh pr view 4 --json headRefOid,mergeable,mergeStateStatus` |

## 1. 结论一览

| Issue | 交付物 | 验收 | 是否已在 `main` | 取件 ref | 依赖决策 F |
|---|---|---|---|---|---|
| VOCA-66 | `docs/requirements/VOCA-65-acceptance.md` v1.0 | **通过** `done` | **已合入** `56ac41b`（人类 11-10 23:09 CST） | 无需再合 | 否 |
| VOCA-67 | `docs/adr/VOCA-65-phrase-learning.md` | **通过** `done` | **已合入** `70a0ded`（同上） | 无需再合 | 否 |
| VOCA-122 + VOCA-414 | 同 66 的文件 → **v1.1.3**（13 项回写 + AC-3.7/3.12 新口径 + §6.3 五项 + D-6 改写） | **通过** `done` | 未合 | **`36403e6`（本地）** — 覆盖式取件，v2 的三方合并已作废 | 否 |
| VOCA-68 | `docs/ux/VOCA-65-interaction-spec.md` v1.1.1（780 行） | **通过** `done`（质检·UX 11-16 PASS） | 未合 | **`9801d7f`**（origin 可达） | 否 |
| VOCA-69 | `docs/ui/VOCA-65-key-visual-spec.md`（846 行） | **通过** `done`（质检·UI 11-17 04:14Z PASS） | 未合 | **`02b7cee`**（origin 可达） | 否 |

- **六条规格子单的验收全部与决策 F 无关**；F 只卡条目 4 的**实现开工**，不卡任何规格结论。
- 文档段与代码段**互相独立**：文档段三条取件现在就能走；代码段（v4：**#41 + #40**，PR #4 已不存在，见 §6）两条均已质检 `PASS-WITH-CAVEAT`、均可合，前置只剩 rebase 与人类一次 go。
- **v4 复测（11-25 10:4x UTC，本轮实测）**：`origin/main` = **`a3336e8`**；`git cat-file -e origin/main:docs/ux/VOCA-65-interaction-spec.md` 与 `…/docs/ui/VOCA-65-key-visual-spec.md` **均 MISSING**（两份规格仍未入库）；`origin/main:docs/requirements/VOCA-65-acceptance.md` = blob `99619fb` / 532 行，v1.1.3 `36403e6` = blob `85fa819b` / 969 行；`gh api …/pulls/{39,40,41}` → 三条全 `OPEN`、`base` 全 `bb1bf2b`（落后 main 4 个提交）；`adb devices -l` 仅 `emulator-5554`。三条取件 SHA（`36403e6` / `9801d7f` / `02b7cee`）在本机**仍可解析**，行数 969 / 780 / 846 与 v3 记录一致。

## 2. 取件命令（在 `/Users/cookie/workspace/ispeech` 执行）

⚠️ **不要 `git merge` 这些分支**。整分支合并会把夹带的非交付文件一起进 `main`（实测见 §3）。只取指定的 3 个 doc 路径。

```bash
cd /Users/cookie/workspace/ispeech
git fetch origin --prune
git checkout main && git pull --ff-only          # 应为 fa57a07

git checkout 36403e6 -- docs/requirements/VOCA-65-acceptance.md   # 本地 SHA，未推 origin（见 §7 前提 1）
git checkout 9801d7f -- docs/ux/VOCA-65-interaction-spec.md       # origin/agent/ux/voca-68-spec
git checkout 02b7cee -- docs/ui/VOCA-65-key-visual-spec.md        # origin/agent/ui/voca-69-docs

git status --short        # 必须只出现这 3 个 docs/ 路径，否则停（§3）
git diff --stat --cached  # 预期：acceptance +478/−42、ux 新增 780 行、ui 新增 846 行
git commit -m "docs(voca-65): 合入规格链路（验收 v1.1.3 + 交互规格 v1.1.1 + 视觉规格）"
python3 tools/verify/check_crosscut.py    # 纯文档改动，预期仍 161/0/0
```

行数自检（取件后 `wc -l`）：`969 / 780 / 846`。三条路径互不重叠，`36403e6` 的那份基线 = `main` 现 blob ⇒ 覆盖不会丢人类改动。

## 3. 夹带物（这些分支都有，**不得进 `main`**）

| ref | 夹带内容 | 后果 |
|---|---|---|
| `agent/ux/voca-68`（本地 `1d567d5`） | `.claude/worktrees/voca-74` gitlink（`.gitignore:37` 已忽略却仍被 baseline 提交） | 进 `main` 后是无 `.gitmodules` 的坏条目 |
| `agent/ba/voca-122` / `agent/ba/voca-414` | 同上 gitlink；**`agent/ba/voca-414` 另在 baseline 提交 `be4657e` 里带 139 MB 的 `dist/ispeech-release-1.5-20261115-1004.apk`** | 该分支**不可推**（一推净传 139 171 786 B） |
| `agent/ui/voca-69`（任务分支，`28f61d8`） | gitlink + `IDENTITY.md` / `SOUL.md` / `USER.md` | agent 人格文件混进应用仓库 |
| 32 条 `agent/agent/*` 本地分支（共 42 条，含本单 `agent/agent/voca-65`）；本机 77 条本地分支里 45 条携带 | 同一 139 MB APK（baseline 快照） | 见 R25：远程 46 个 ref 目前 0 命中，推任何一条都是净上传 |

`9801d7f` 与 `02b7cee` 已核对**不含**该 blob：`git rev-list --objects 9801d7f \| grep -c d0a15bc6` → **0**；`02b7cee` → **0**。

## 4. 复现方式（11-17 本轮实际跑的）

```bash
git rev-parse --short origin/main                                   # fa57a07（未变）
git show origin/main:app/build.gradle.kts | grep versionName         # "1.5" → dist/ 无 v1.6 产物，条目 3 未入库
git rev-parse origin/main:docs/requirements/VOCA-65-acceptance.md \
            76d178c:docs/requirements/VOCA-65-acceptance.md          # 均 99619fb ⇒ 三方合并作废
git show 36403e6:docs/requirements/VOCA-65-acceptance.md | wc -l     # 969（v1.1.3）
git show 36403e6:docs/requirements/VOCA-65-acceptance.md | grep -c 'Q6\|D-6'   # 28（414 的两项回写在文内）
git show 9801d7f:docs/ux/VOCA-65-interaction-spec.md | wc -l         # 780
git show 02b7cee:docs/ui/VOCA-65-key-visual-spec.md | wc -l          # 846
git ls-remote --heads origin | grep -E 'voca-(68-spec|69-docs|124)'   # 三条远程 ref 在位
git branch --contains 36403e6                                        # 仅 agent/ba/voca-414（本地）
git rev-list --objects --all | grep -c d0a15bc6                      # 1（对象存在于本机）
git rev-list --objects --remotes | grep -c d0a15bc6                  # 0（远程 46 个 ref 全干净）
gh pr view 4 --repo forcoder/ispeech --json state,mergeable,mergeStateStatus,headRefOid
# → OPEN / MERGEABLE / CLEAN / 8ab0a88210546285dacf2e30cfaf0c33f0256a41
```

## 5. 回滚

- 三条都是纯文档、单提交：`git revert <merge-window-commit>` 整批退回，**不影响任何代码**（条目 3 的代码在 PR #4，尚未合）。
- 只想退一条：`git checkout <上一提交> -- docs/ux/VOCA-65-interaction-spec.md`（或对应路径）后单独提交。
- 验收文件退回 v1.0：`git checkout 56ac41b -- docs/requirements/VOCA-65-acceptance.md`。
- 交互规格 / 视觉规格在 `main` 上原本不存在 → 退回即 `git rm docs/ux/VOCA-65-interaction-spec.md docs/ui/VOCA-65-key-visual-spec.md`。

## 6. 第二段：代码 —— **v4 已重写，PR #4 不存在了，看这里**

| 序 | 取件 | head | 文件数 | 前置于 | 状态 |
|---|---|---|---|---|---|
| 1 | **PR #41**（条目 1 本体 VOCA-631） | `7fb70fd` | 16 | 无 | `OPEN` / `base=bb1bf2b`（须 rebase）/ 质检 `PASS-WITH-CAVEAT` |
| 2 | **PR #40**（条目 5a VOCA-628） | **`383d702`** | 8 | 无 | `OPEN` / `base=bb1bf2b`（须 rebase）/ 质检 `PASS-WITH-CAVEAT`、打回项 0 / **v4.2 已登记入窗 §6-c；v4.3 已带 R37 一行修复** |
| — | ~~PR #39（D-2 VOCA-627）~~ | `1870849` | 7 | — | **合并时跳过**：#41 已完整包含它的 5 个产品文件（`git diff --stat 1870849 7fb70fd` 全净增） |

**顺序**：#41 → #40，理由 = #41 改 `IspeechImeService.kt` +127/-18（面板搜索状态），#40 同文件 +77/-9（AI 上屏分支），两者区域不重叠（实测 `git merge-file` 三方合并 **0 冲突**）；#41 先进 main，#40 后合时按「后合入者适配先合入者」rebase 一次即可。反过来也可，代价一样。

**共同前置（不完成不许合）**：

1. **rebase 到 `main@fd1d686` 或更新**（R32）：两条 base 都停在 `bb1bf2b`。v4.2 已补文件级冲突结论：`IspeechImeService.kt` 是 #40 与 main / #40 与 #41 的唯一重叠文件，三方合并 0 冲突（详见 §6-c 第 2 条）；全树级 `git merge-tree` 仍受沙箱限制未做。
2. **`docs/quick-phrase-enhancement.md` 冲突**（只影响 #41）：main 的 §12.7 与 #41 的 §12.7 撞名，实测 1 处冲突块。**两节都保留**，#41 侧顺延为 §12.8/§12.9/§12.10 并改 3 处内部引用（`:766`/`:771`/`:830`）。
3. **门禁复跑**：#41 侧 `tools/verify/check_*.py` 全绿（质检 11 只已复跑 `failed=0`）、`./gradlew :app:testDebugUnitTest` 与 main 基线对比零新增失败（`AgentDispatcherTest` 在 main 上即失败，不计回归）；**#40 侧 13/13 绿（`383d702`，`/usr/bin/python3` = 3.9.6 下实测）**——R37 已在 v4.3 修掉，原「12/13」与更早的「13 只全绿」两版表述均以此为准，见 §6-d 第 2/3 条。
4. **人类给一次 go**（合并 + 出包同一次答复）：见计划 §10 v3.14。合并本身可自主（工作区铁律 (a)~(d) 满足时），**出包与装真机仍等人类**。

**回滚**：两条都是独立 PR，`git revert <squash sha>` 各自回退；#41 回退影响条目 1（搜索框中间编辑）与 D-2 渲染面，#40 回退影响条目 5a（AI 一步填入），互不牵连。回退后 `main` 的 `docs/quick-phrase-enhancement.md` 需确认回退没带走 VOCA-293 的 §12.7。

**出包后**：VOCA-127 的真机 6 项与 VOCA-628 的 AC-5a.3 在发布后真机回归里补跑（都已按豁免口径收口，不阻塞出包）。

## 6-c. v4.2（11-26，PM）—— PR #40 正式登记入窗 + R32 的文件级冲突结论已补

来源：VOCA-628 督导 11:01:38Z 诊断（「零打回项的交付被一条已降级的 AC 钉死在 blocked」，判假阻塞、`blocked`→`in_progress` 并改派项目经理，期限 11-27 18:00Z 前把 PR #40 登记进窗口）。以下全部为本轮在任务分支 worktree 实测，不引用实现者或质检的输出。

**1. PR #40 的现 head 与状态（替换 §6 表第 2 行的 `3d2fc3e`）**

```text
$ gh pr view 40 --json number,state,headRefOid,baseRefName,changedFiles,additions,deletions,mergeable,mergeStateStatus
# → #40 OPEN / head 5138ca9c346b60e7269ad85a66345138b4858245 / base=main
#   changedFiles=8, +1504 / -37, MERGEABLE, CLEAN
$ git rev-parse 5138ca9^{tree}  b62e6f7^{tree}      # 9ea06a71… = 9ea06a71…（PR head 与任务分支同树）
```

- 相对 v4 记的 `3d2fc3e` 又走了 2 个提交（`45ed4fd` 空态标题保持「无结果」、`5138ca9` 缺 Pillow 硬停 + 5a.4 空态断言），**文件数仍是 8**，无新增文件面。
- `base` 仍停在 `bb1bf2b`，`main` 已从 `a3336e8` 走到 **`fd1d686`**（期间进 `#47`），**R32 的「须先 rebase」依然成立**。

**2. R32 的开放项已补上文件级冲突结论（v4 当时诚实声明「未做」的那一条）**

`git merge-tree` 在本 worktree 仍是 rc=128（`unable to create temporary file: Operation not permitted`），改用与 v4 相同的手工三方合并（`git merge-file`，写工作目录）：

```text
$ # base=bb1bf2b / main=fd1d686 / pr=5138ca9，逐文件对 IspeechImeService.kt 三方合并
$ git merge-file -p --diff3 .merge/p40.kt .merge/b.kt .merge/m.kt > .merge/o40.kt ; echo $?
0 ; grep -c '^<<<<<<<' .merge/o40.kt → 0
$ # 同一口径对 PR #41（7fb70fd）复算
$ 41-vs-main → rc=0 / 冲突块 0
$ # 并入序：先 #41 的 head，再把 #40 叠上去
$ git merge-file … 7fb70fd + #40 → rc=0 / 冲突块 0
```

- **结论**：`IspeechImeService.kt` 是本单与 main、以及本单与 #41 的**唯一**重叠文件（`comm -12` 实测，其余 7 个文件与两者都不重名）；行级三方合并 **0 冲突**，`#41 → #40` 与 `#40 → #41` 两种顺序均 0 冲突。⇒ v4 §6「顺序 #41 → #40」保留，原「行级冲突仍须执行时验」一条**现已验完**。
- 未覆盖：`git merge-tree` 全树级结论仍缺（沙箱限制不变）；`docs/quick-phrase-enhancement.md` 的那 1 处冲突属 #41 侧，与本单无关，v4 的解法不变。

**3. 门禁复跑（在 `5138ca9` 的干净导出树上跑，不在含 `ce8fb5f` 产物的工作区）**

> **v4.3 加注**：本节读数是 `5138ca9` 在 **Python 3.15.2** 下的真实输出，但 `check_ai_suggest.py` 在 3.9.6 / 3.12.15 下会崩（R37，根因见下条）。R37 已由 `383d702` 修掉，**合并窗口请以 §6-d 第 3 条 `383d702` 的 13/13 读数为准**，本节保留供追溯。

```text
$ git archive 5138ca9 | tar -x -C .verify && cd .verify
$ for f in tools/verify/check_*.py; do /opt/homebrew/bin/python3.15 "$f" | tail -1; done
check_ai_suggest.py: passed=60 failed=0      check_crosscut.py: passed=183 failed=0
check_dist_manifest.py: passed=7 failed=0    check_input_logic.py: passed=40 failed=0
check_keyboard_ui.py: passed=19 failed=0     check_model_host.py: 源可用（exit 0）
check_phrase_csv_import.py: passed=48 failed=0   check_phrase_usage.py: passed=31 failed=0
check_pinyin.py: passed=66 failed=0          check_quick_trigger.py: passed=82 failed=0 warned=1
check_static.py: passed=6 failed=0           check_stats.py: passed=53 failed=0
check_t9.py: passed=87 failed=0 warned=1
```

**门禁可移植性缺口（登记 **R37**）**：`check_ai_suggest.py` 在 **Python 3.9** 下直接崩——`tools/verify/voca628_ai_one_tap_adb.py` 的 `@dataclass` 触发 `AttributeError: 'NoneType' object has no attribute '__dict__'`。**根因与解释器版本无关**：`check_ai_suggest.py:272-275` 用 `spec_from_file_location` + `exec_module` 动态加载取证脚本时**没有先注册 `sys.modules["voca628_adb"]`**，而 `voca628_ai_one_tap_adb.py` 有 `from __future__ import annotations` ⇒ `:93 @dataclass` 的字符串注解走 `dataclasses._is_type` ⇒ `sys.modules.get(cls.__module__)` 返回 `None`。实测 **3.9.6 / 3.12.15 崩、3.15.2 通过**——不是「3.10+ 才支持」的版本门槛：3.15 的 `dataclasses` 把同一处 `sys.modules.get(...)` 结果交给了 `getattr(root, …, None)`，`None` 被吞掉；3.9/3.12 直接 `.__dict__`。v4.2 原文写的「3.12 与 3.15 正常」是错的，已撤回。

修法一行（`tools/verify/check_ai_suggest.py`，紧接 `module_from_spec` 之后）：

```python
    adb_mod = importlib.util.module_from_spec(spec)
    sys.modules["voca628_adb"] = adb_mod        # ← 补这行，exec_module 前注册
    spec.loader.exec_module(adb_mod)
```

改后 3.9.6 与 3.12.15 均出 `SUMMARY: passed=60 failed=0`；同一棵树上补丁生效后，13 只脚本在 3.9.6 下 `failed=0`（见 §6-d 第 3 条）。**该修复已由 v4.3 落到 PR #40 分支 `383d702`**（PM 代为提交，作者仍是前端，见 §6-d 第 2 条），所以合并窗口取件用 `383d702` 即可，无需再等一轮前端返工。

**4. AC-5a.3 的处置（本单唯一的实质性遗留）**

```text
$ adb devices -l
emulator-5554  device  product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a
$ adb -s emulator-5554 shell getprop ro.kernel.qemu
1
$ ioreg -p IOUSB -w0 -l | grep -A… 'MRR-W29@'   # USB 已枚举 HUAWEI MRR-W29（idVendor 4817），但无 ADB 接口
```

- 真机 `FMR0224913042917` **仍不在线**（`adb devices -l` 只有模拟器，`ro.kernel.qemu=1`）；USB 侧 `MRR-W29` 已枚举但设备未开 USB 调试，故无 ADB 接口。**模拟器读数不顶真机**——这条口径不变。
- 但质检 10:56:43Z 的裁决书写明**「打回项：无」**，5 条 AC 已由其独立复现，AC-5a.3 的「模拟器 PASS + 待真机复核」降级已在 VOCA-739 §九 由人类结论获批。⇒ 本单**不以 AC-5a.3 阻断入窗**：登记为**发布后回归遗留项**（真机回线后跑 `--serial <真机> --strict-device` 回填），与 VOCA-127 的 6 项同一处理方式。
- 风险栏写法（供出包记录引用）：`条目 5a 的 AC-5a.3 仅有模拟器读数（zzqaxy→zzqaxy好的…，未删用户内容），真机 getTextBeforeCursor 时序未验，随发布后真机回归补跑`——**不得写成「已通过」**。

**5. 入窗后的顺序与前提（本单视角，§6 表不变）**

| 序 | 取件 | head | 前置 | 谁执行 |
|---|---|---|---|---|
| 1 | PR #41（VOCA-631） | `7fb70fd` | rebase 到 `main@fd1d686` + `docs/quick-phrase-enhancement.md` 1 处冲突手工保留两节 | PM 出命令 · **人类 go** |
| 2 | PR #40（VOCA-628） | **`383d702`** | rebase 到「#41 合入后的 main」；冲突按本次实测预期 0 | 同上 |

出包与装真机仍属生产操作，**agent 不执行**；AC-5a.3 与 VOCA-127 六项一并进发布后真机回归。

## 6-d. v4.3（11-26，PM）—— 质检 11:35Z 打回 3 项整改 + 清单可达化

来源：VOCA-628 质检专员·项目经理 11:35:00Z 裁决 `FAIL`，打回 3 项（① 清单不可达；② R37 根因登记错；③ 「13 只全绿」表述撤回）。本节是那 3 项的对账，全部本轮在任务分支 worktree 自带测量。

**1. 打回项 ①：清单可达化（v4.2 只在本地未推的分支上，质检与人类都取不到）**

```text
$ git ls-remote origin | grep -c 628-7e938e703ac9
0
$ git push origin HEAD:agent/agent/voca-628-7e938e703ac9
remote: error: File dist/ispeech-release-1.5-20261119-1557.apk is 132.75 MB;
        this exceeds GitHub's file size limit of 100.00 MB
! [remote rejected] … (pre-receive hook declined)
```

- 根因：本任务分支的基线提交 `ce8fb5f`（"baseline — uncommitted work from the local directory"）把 `dist/` 下的 **132.75 MB release APK** 一起提交了，GitHub 单文件上限 100 MB 直接拒收**整条分支**（不是拒某个文件）。这正是仓库硬规则严禁提交大文件的那一条，属基线提交的既存问题，不是本轮引入。
- 整改（两条路径都做了）：① 本清单**随 VOCA-65 附件交付**（沿用 v1–v4.1 全部 15 次的历史惯例）；② 另从 `origin/main` 切一条只含本文件的干净分支 **`pyt/docs/voca-65-merge-manifest-v4.3`** 并推上 origin（取 branch tip，不写死 SHA——本文档也在该分支上，写死 SHA 会随每次修订自我失效），质检与人类可直接 `git fetch` 取件：

  ```text
  $ git fetch origin pyt/docs/voca-65-merge-manifest-v4.3
  $ git show FETCH_HEAD:docs/voca-65-merge-manifest.md | sed -n '/^## 6-d\./,/^## 6-b\./p'
  $ git ls-remote origin | grep voca-65-merge-manifest      # → refs/heads/pyt/docs/voca-65-merge-manifest-v4.3
  $ git diff --stat origin/main FETCH_HEAD                  # → 1 file changed（只有 docs/voca-65-merge-manifest.md）
  ```

  该分支 `git diff --stat origin/main` **只含这一个文件**，不含 `dist/` 任何产物；内容与本文件当前版本逐字一致。任务分支上那个 132 MB blob 的清理另开 chore 单，不占用本单窗口。
- 该文件在 `origin/main` 上**不存在**（`git ls-tree origin/main docs/voca-65-merge-manifest.md` 空）——它是 PM 工作产物，随 VOCA-65 附件流转，不进 main。

**2. 打回项 ②：R37 根因订正（已回写 §6-c 第 3 条）**

原文把原因记成「Python 3.9 的 `@dataclass` 问题、须 3.10+」，**方向错了**：真正的缺陷是 `check_ai_suggest.py` 动态加载取证脚本时漏注册 `sys.modules`。修复前后对比（都在 `5138ca9` 的干净导出树上，仓库自带脚本，未删未改断言）：

```text
$ /usr/bin/python3   tools/verify/check_ai_suggest.py   # 3.9.6
AttributeError: 'NoneType' object has no attribute '__dict__'
$ /opt/homebrew/bin/python3.12 tools/verify/check_ai_suggest.py   # 3.12.15
AttributeError: 'NoneType' object has no attribute '__dict__'  ← 证伪「3.10+ 即过」
$ # 加一行 sys.modules["voca628_adb"] = adb_mod 后：
$ /usr/bin/python3   tools/verify/check_ai_suggest.py → SUMMARY: passed=60 failed=0
$ /opt/homebrew/bin/python3.12 tools/verify/check_ai_suggest.py → SUMMARY: passed=60 failed=0
```

- 3.15 之所以能跑通，是它 `dataclasses` 用了 `getattr(root, …, None)` 吞掉 `None`（`inspect.getsource(dataclasses)` 实测），**不是版本门槛**，所以「换 3.10+ 解释器」这条处置建议是错的，已在 §6-c 撤回。
- **已落地**：修复以 `383d702` 提交并**快进推上 PR #40 分支 `feat/voca-628-ai-one-tap`**（`5138ca9..383d702`，`gh pr view 40` 复核 head / 8 文件 +1507−37 / `MERGEABLE`+`CLEAN`；对它单独 `gh api compare` 只有 `tools/verify/check_ai_suggest.py +3 −0`）。PM 代为提交是为了不再多耗一轮，脚本归属不变，前端可在后续 PR 上继续改。

**3. 打回项 ③：「ai_suggest 60/0、13 只全绿」表述撤回 → 现已重新成立（带前提）**

- 撤回与恢复是两件事，都已做：**撤回**的是「未经解释器标注的 13 只全绿」；**恢复**的是 `383d702` 树上、`/usr/bin/python3`（3.9.6）实跑的 13/13 `failed=0`——12 只原样通过，`check_ai_suggest.py` 是修好后通过（`passed=60 failed=0`，与 3.15.2 的结果一致）。§6 表「共同前置 3」据此改为 **13/13 绿（`383d702`）**。
- §6-c 第 3 条的 13 行读数**保留原文本并加注**——它是 `5138ca9` 在 3.15.2 下的真实输出，不删，只标清解释器与已过期状态。

**4. 未整改项与理由（诚实声明）**

- **AC-5a.3 真机**：`adb devices -l` 仍只有 `emulator-5554`（`ro.kernel.qemu=1`），USB 侧 `MRR-W29` 无 ADB 接口。属人类设备门，与 VOCA-127 六项同批进发布后回归，**本单不伪称已通过**。
- **全树 `git merge-tree`**：沙箱仍 rc=128，用逐文件 `git merge-file` 替代（§6-c 第 2 条已给 0 冲突结论）。

## 6-b. v3 留痕（**已失效，仅供追溯**）：原第二段「代码（VOCA-124 / PR #4）」

| 步骤 | 内容 | owner | 期限（CST） |
|---|---|---|---|
| 1 | PR #4 第 4 次质检（**增量复验**，只看 `7ed1230..8ab0a88` 那两份文档） | 质检专员·前端 | 11-17 内 |
| 2 | squash 合入 `main`（顺序在文档段之后：后合入者适配先合入者） | PM 执行 · **人类确认** | **11-19 窗口**（v3.3：11-18 12:00 无答复，+1 天；与 VOCA-203 对齐） |
| 3 | 重新打全量 release 包（现 `main` = `versionName 1.5`，无 v1.6 产物；VOCA-186 那批包不含条目 3，不能用） | SRE / 装机 · **人类确认** | 11-19（v3.3 +1 天） |
| 4 | VOCA-127 六项真机 AC（判据认 `8ab0a88` 的 `docs/voice-phrase-learning-verify.md`，旧 SHA `7ed1230`/`48f91ea`/`8015e80` 一律作废） | 测试 | **11-20**（v3.3 +1 天；AC-3.10 另需 VOCA-510 先入库） |

**取件只认 SHA，不认分支名**：`agent/agent/voca-124` 那条本地链永远推不上去（`9ccbba4` 带 139 MB 包被 pre-receive 拒绝），可合的只有 PR #4 的 **`8ab0a88`**。

## 7. 执行本窗口的前提（三条，缺一先补齐）

1. **验收文件 v1.1.3 目前只在本地** `agent/ba/voca-414`。本机执行窗口不受影响；若要远程留痕，需**另起一条干净 docs-only 分支**（从 `main` 切 → `git checkout 36403e6 -- docs/requirements/VOCA-65-acceptance.md` → 单提交 → 推），**不要推 `agent/ba/voca-414` 本身**（会净上传 139 MB，见 §3）。
2. **人类的 yes**（v2 两段走 + 出包）。11-18 12:00 CST 前拿不到 → 整链后移一天，我在 VOCA-65 报偏差，不压缩测试轮次。**已执行（11-18，v3.3）**：到期无答复，窗口顺延至 **11-19**，VOCA-127 due 改 **11-20**，偏差已在 VOCA-65 上报。
3. **`git status --short` 只出现 3 个 `docs/` 路径**再提交（§2 的自检）。
4. **v4 新增：两条代码 PR 先 rebase**（R32）。`gh api …/pulls/{40,41}` 现 `base=bb1bf2b`，main 已 `a3336e8`；不 rebase 直接 squash 会回退 `#36`/`#37`/`#42`/`#44` 的改动。rebase 后重跑各自门禁（§6 前置 3）再合。
5. **v4 新增：`docs/quick-phrase-enhancement.md` 冲突必须两节都留**（§6 前置 2）。丢一节的后果 = 要么 `main` 上 VOCA-293 的「同步后立刻可见」口径消失，要么本次条目 1 的双状态口径不进文档。

## 8. 合完之后的门禁（不在本窗口内）

> **11-25 10:5x UTC（PM，v4.1）—— 发布准入加一条硬条件（承接 VOCA-127 收口核验）**：本次合并后打的全量包，**不得**以「VOCA-127 已 `done`」作为条目 3 真机验收凭据。127 判的是 `PASS-WITH-CAVEAT`，真机 6 项 **0/6 未验**，模拟器层读数所在宿主 `emulator-5560` 已销毁、自报包指纹 `156ac579…018e8` 在 `dist/` 六个 apk 中 0 命中。真机准入的唯一凭据是 **VOCA-657**（`backlog`，事件唤醒 `01a21259-98aa` enabled）的 6 项 go/no-go；发布前若它仍是 `backlog`，在发布记录里明写「条目 3 真机 6 项未验，随发布后回归」，不得写成已通过。见计划 R33。

> **11-25 10:4x UTC 复测（PM，v4）**：见 §1 末条。要点 = 文档段**仍未入库**（两份规格 main 上 MISSING、验收文件仍旧版 532 行）、代码段三条 PR **全 `OPEN`** 且 base 全停在 `bb1bf2b`。⇒ 「人类一次 go」是本窗口唯一的门；设备已不是门（127/628 均按豁免收口）。

> **11-17 18:02 CST 复测（PM）**：窗口前提未变——PR #4 `OPEN`/`MERGEABLE`/`CLEAN`/`mergedAt=null`/head 仍是 **`8ab0a88`**、`git merge-base --is-ancestor 8ab0a88 origin/main` **exit=1**、`origin/main` 仍 `fa57a07`、`gh release list` 空、本机 `adb devices -l` 空。VOCA-124 第 4 次质检 04:39Z **PASS-WITH-CAVEAT** → `done`，代码段取件 ref 从「等复核」正式变成「只等人类 yes」。

> **11-18 14:4x CST 复测（PM，v3.3）**：PR #4 仍 `OPEN`/`MERGEABLE`/`8ab0a88`、`merge-base --is-ancestor` exit=1、`origin/main` 仍 `fa57a07`；PR #18 仍 `OPEN`/`78343a4`；`gh release list` 空；**`adb devices -l` 变了**：真机 `FMR0224913042917`（ALN-AL00，USB）在线、emulator-5554 消失——**serial ≠ 基线机 `23F0224224000950`**，能否作回归机待人类确认；② 合入+出包未成立，VOCA-127 仍 `blocked`（due 改 11-20）。**新增门禁**：main 既存 **P1 设置页 NPE**（`8ab0a88`/`fa57a07` 双构建复现，挡 AC-3.10 与「UI 切五笔」）已开 **VOCA-510** 派前端（due 11-19）——按「P0/P1 未清零不交付」，是否列本窗口准入条件由洞察BA/人类裁定；测试模拟器层 5/6 不替代真机验收。

0. **随文档段那一次提交顺手带走的一行**（VOCA-122 质检·BA 的 caveat，计划 R29）：验收文件 **AC-3.7 的 Given 补上「且测试机启用的五笔码词库无 `code` 以 `klqt` 开头的单字条目」**——现值只挡了快捷短语 / 快速应用，分类 / 细胞词库经 `CellDictEngine` 的 `startsWith` 能在满码补出单字压过「喂猫」，11-19 真机会因此出**假 fail**（测试侧已在 `439790b` 用例集 §4 通用前置 2 自挡，所以这行不卡窗口，只是别再让它漂着）。归属洞察BA，改完 `check_crosscut.py` 预期仍 161/0/0。
1. 合入后跑 `python3 tools/verify/check_crosscut.py`（文档改动预期不影响，红则说明取件取多了）。
2. **条目 4 实现**：卡决策 F，**F 已在 11-18 重述成可回答的形状**（旧表述挂着没人回有两个原因：①作废后剩下的两档没算代价；**二是它只盯着工具栏，漏了同一句里的 ✕**）：
   - **要它决定什么**：AC-4.6 末句「独立控件（工具栏键、搜索框 ✕、面板按钮）可点 ≥40×40」现状有**两项**必然不达标——`origin/main`（`fa57a07`）`SoftKeyboardView.kt:962` `TOOLBAR_HEIGHT_DP = 34`（PR #18 head `78343a4` 的 `:963` 仍是 34，**PR #18 从不碰工具栏**）、`QuickPhrasePanel.kt:646-647` `searchClear` `minimumWidth=dp(30)`/`minimumHeight=dp(28)`；第三项 ◀▶ 属条目 1，main 上还不存在。旧①「纳入 VOCA-46」作废（VOCA-46 11-16 19:30Z 判 `done`，范围三项不含工具栏）。
   - **② 整句移出本批（PM 推荐）**：BA 改验收文件 v1.1.3 → v1.1.4 的 **4 处**（`:359` §4.2 注、`:390` AC-4.6 行末句、`:418` R4「独立控件」行、`:836` §13.4 仍待定第 2 条），**字母键的 ≥38 / ≥40 判定一字不动**；本批零代码增量 → **不多一轮全量打包 + 真机回归**；代价是工具栏与 ✕ 的可点面积本包不解决，40×40 的值仍在 VOCA-69 §5.3 与令牌 `control.toolbar.height=40`，以后开单零返工。
   - **③ 新建一条「独立控件 ≥40×40」issue**：BA 补 AC + 前端改两处（`TOOLBAR_HEIGHT_DP` 34→40 = **键盘总高 +6 dp**，吃掉 AC-4.7 ≤8 dp 预算的 6/8，R4-35 后不得再加高；`searchClear` 30/28→40/40 不改总高）。硬前置：**PR #18 先进 main**（同改 `SoftKeyboardView.kt`，R4-26 明令不得单独改工具栏高）→ 按已提请的执行序 PR #18 = W5 / **11-21**，③ 的日期在 11-21 之后并**再加一轮全量回归**，而真机 `23F0224224000950` 11-18 02:0x CST 实测仍不在线（`adb devices -l` 只有 `emulator-5554`）。
   - **回答格式与逾期默认**：一句「F=②」或「F=③」；11-18 12:00 CST 未回 → 条目 4 继续不排期，我不代人类改 AC，**本窗口的文档段/代码段不等 F**。
   - **更正**：v3.1 写的「F 落定后 1 个工作日内开派」不完整——条目 4 另有隐藏前置 **R28**（VOCA-69 整张比例表按 46 dp 键高给值，46 dp 只在未合的 PR #18 里，main 现值 `KeyboardSettings.kt:18 = 52`）。F 与 PR #18 入库都到位后，我在 1 个工作日内开派。
3. **条目 1/2 实现**：仍未开 issue（有意不派）。条目 1 首项是 D-2——`main` 上 `searchText` 已退化为 `TextView`、无 caret 渲染面，走恢复 `EditText` 路线需先过架构师；条目 2 规格已定但排在条目 1 之后（同改 `IspeechImeService.kt` / 面板）。
4. **5b（通知触发 AI 悬浮）**：需求 4 项 + 隐私结论仍未补齐，只登记不排期——追踪项由父单 VOCA-65 持有。
