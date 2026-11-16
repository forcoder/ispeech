# ADR-0003：`dist/SHA256SUMS` 是生成物，唯一写入路径是 `--regen`（VOCA-400）

- **状态**：Proposed（待统一合并窗口随 PR 落地；进入 main 前不改 main）
- **决策日期**：2026-11-17
- **决策人**：SRE工程师（agent `499aaa3a-4c36-438a-9923-6fb63c46449a`）
- **关联 issue**：VOCA-400（本 ADR）；源自 VOCA-168 §8.4 澄清件，取证链断点在 VOCA-167
- **关联既有文档**：`dist/README.md`「构建与实测环境 / 校验」两节、`tools/verify/README.md`、VOCA-186「最终包门禁固定四项」
- **本文的基线口径**：所有结论以 **`origin/main` = `fa57a07`** 为准；发布分支 `release/voca-186-v1.6`（`eba7a61`）的差异在 §6 逐条对读。
- **范围**：只定「随包校验清单」的生成口径与门禁接线。不动应用内版本号逻辑（VOCA-233 已 `done`），不做仓库产物治理（VOCA-292），不拍板签名策略（VOCA-159）。

---

## 1. 问题

清单的唯一职责是让人用一条命令回答「我手上这份 APK 的字节，是不是仓库里那份」。main 上这条命令当前给的是**假答案**：

```
$ git archive fa57a07 | tar -x -C /tmp/x && cd /tmp/x/dist && shasum -a 256 -c SHA256SUMS
ispeech-debug-v1.0.apk: OK
ispeech-debug.apk: FAILED
ispeech-release-v1.0.apk: OK
ispeech-release-v1.3.apk: OK
ispeech-release-v1.5.apk: FAILED
shasum: WARNING: 2 computed checksums did NOT match
```

两类失效，第二类更严重：

| 失效 | 数量 | 后果 |
|---|---|---|
| 记了但记错（`ispeech-debug.apk`、`ispeech-release-v1.5.apk`） | 2 | 正常包被报成「损坏」，用户重装也查不出问题，只能回到 `dumpsys` |
| **该记但从未记**（`_archive/` 三个历史包） | 3 | 静默通过。`shasum -c` 只校验清单里有的行，清单不全它一声不吭 |

main 上跟踪 8 个 `dist/*.apk`，清单只有 5 行、其中 3 行是对的 —— 覆盖率 3/8。

## 2. 口径（写死，不留「下次记得更新」）

**D1 — 清单是生成物，不是手工账本。** 唯一写入路径是：

```bash
python3 tools/verify/check_dist_manifest.py --regen
```

它按当前字节整份重写。禁止手改，**尤其禁止 `dist/README.md` 里原有的那句 `shasum -a 256 … >> SHA256SUMS`**：追加会让同一路径出现两行，`shasum -c` 取后一行、前一行永久静默，等于给同一个包名积累互相矛盾的记录。本文 §4 已把该行替换掉。

**D2 — 必须穷尽覆盖。** 「该入清单」= `git ls-files --cached --others --exclude-standard -- dist` 里的 `*.apk`，即**已跟踪的 + 会被 `git add -A` 扫进来的**。用 git 索引而不是裸扫目录，是为了同时拿到两端：

- 刚 `cp` 进 `dist/` 还没入库的新包 → 立刻要求一行记录（这是本次补上的主漏洞）；
- `.gitignore` 已排除的本机残留（`dist/ispeech-debug-*.apk`、`deploy-*.log`）→ 不强加成包记录。

**D3 — 记错即失败，不是警告。** 校验任一条不符 → 退出码 1 → 出包中断。

**方案 B（把对不上号的历史包移出清单）已否决**，理由：清单要回答的是「手机上那包是谁」，而人们手上留着的恰恰是历史包（用户机上就是 v1.5）。从清单里划掉 `ispeech-release-v1.5.apk` 会让最常见的自查场景彻底无机检。对不上号的正确解法是**让记录服从字节**，不是让字节服从记录。

## 3. 两条 FAILED 的取证：`f6155fa0…` 不是「重签前的旧校验和」

issue §4 的推断（重签导致漂移）**不成立**。逐 blob 跑 `shasum -a 256` 对读历史提交：

| 文件 | 清单里的值 | 实际值 | 实际值的来历 |
|---|---|---|---|
| `ispeech-release-v1.5.apk` | `f6155fa0…` | `490b152a…` | `f6155fa0…` 是 `72bb547`（10-17 15:48）里该路径 blob 的真实 sha256；`904a98d`（11-09 12:05）把同名文件换成另一份构建，清单未跟着改 |
| `ispeech-debug.apk` | `f21072d5…` | `b308f7ad…` | 同上，同一对提交 |

```
$ git log --all --format=%H -- dist/ispeech-release-v1.5.apk | while read c; do
    git cat-file blob "$c:dist/ispeech-release-v1.5.apk" | shasum -a 256; done
f6155fa0a1161833…  dist/ispeech-release-v1.5.apk   # 72bb547  10-17 15:48
490b152ad20df7af…  dist/ispeech-release-v1.5.apk   # 904a98d  11-09 12:05  ← 当前
```

即漂移根因是**同名重打、原地覆盖、清单不动**（`tools/deploy/deploy.sh` ⑤ 就是这套动作，且它一直只 `info` 打印 sha256、从不落盘），与签名无关。签名侧实测：

| 对象 | 证书 DN | 证书 SHA-256 |
|---|---|---|
| 仓库当前 `dist/ispeech-release-v1.5.apk`（`490b152a…`） | `CN=iSpeech Prototype` | `70b4f4a2…cf2ac7` |
| 仓库当前 `dist/ispeech-debug.apk`（`b308f7ad…`） | `CN=Android Debug` | `857ce64e…f66a0ed` |
| VOCA-159 11-14 12:28 装进用户手机的那包 | `CN=Android Debug` | 同上（是仓库外重签副本 `e1de4def…`，**不在仓库、也不在任何清单**） |

**由此得出本 ADR 的边界结论**：清单只能证明「仓库字节」，证明不了「手机字节」，因为交付路径上存在仓库外的重签变体。这条不是 VOCA-400 能修的，已按 issue 要求回写 VOCA-159（签名 / 版本号策略待拍板项），本 ADR 不代拍。

## 4. 门禁（VOCA-186 四项之后的第五条）

| 门禁 | 命令 | 可跳过 | 失败处置 |
|---|---|---|---|
| 五、随包校验清单与字节一致且穷尽 | `python3 tools/verify/check_dist_manifest.py` | **否**（不受 `--skip-verify` 管辖） | `--regen` 后重跑；仍 FAIL 则不出包 |

接线两处（`tools/deploy/deploy.sh`）：

- **③ 静态校验**：清单校验放在 `--skip-verify` 分支**之外**，硬 `die`。跳过它等于允许带一份会说谎的清单出包，而「会说谎」正是本次的原始故障。
- **⑤ 归档到 dist**：`cp` 完成后立即 `--regen` + 回读校验，新包在离开工位前就已经被记录。

**判定逻辑自身可验**（沿 `check_model_host.py --self-test` 先例）：

```bash
$ python3 tools/verify/check_dist_manifest.py --self-test
…（7 条 PASS）
SUMMARY: passed=7 failed=0 warned=0
```

7 个场景含**双向**断言：好状态放行，篡改一位 hex / 新增未记录包 / 孤儿行 / 同路径两行 / 格式非法 五种坏状态必须各自挡住。只测「好状态通过」的门禁等于没有门禁。

## 5. 回滚

纯工具 + 清单变更，不含业务代码，回滚 = `git revert` 本 PR 的提交。回滚后恢复的是「清单不再被机检」的状态，即 VOCA-400 的故障面，因此**不建议单独回滚校验脚本**；若必须止血，只回滚 `tools/deploy/deploy.sh` 的硬门禁（恢复 `--skip-verify` 可绕过），`--regen` 与清单本身可保留。

## 6. 与发布分支的对读、未覆盖项、移交

**与 `release/voca-186-v1.6`（`eba7a61`）无冲突**：该分支已把 5 个历史包移入 `_archive/` 并列出 9 条（含 `ispeech-internal-v1.6.apk` = `9cddcec5…43f42`）。本 ADR 重生成的 8 条 sha256 值与其中对应的 8 条**逐位相同**，差异只有路径前缀（因为本 PR 基线上的 main 还没移动文件）。合并窗口按 main 实际布局跑一次 `--regen` 即收敛，不需要手工调和清单内容。

**未覆盖 / 移交**：

1. → **VOCA-159**：重签变体的命名与记录策略（待人类拍板，已回写该单）。
2. → **VOCA-292**：`main` 跟踪 8 个 `dist/*.apk` 与 baseline 收尾扫进大二进制。本 PR 只处理了直接卡住交付的那一个文件（见下），通用防护归 VOCA-292。校验脚本对 >100 MB 的包给 `WARN` 不给 `FAIL`，就是为了不抢那条的判定权。
3. `dist/README.md` 的打包段引用仓库根 `README.md` 与 `./ispeech-remote.sh`，**后者在本仓库不存在**（仓库根没有 `*.sh`）。本次只替换其中产生漂移的 `>>` 那一行，远端构建链路的文档失效另开单。
4. 清单不含**构建 commit** 与 `versionCode/versionName`。有 sha256 才能定「哪一包」，但定「这包是 main 哪个提交打的」仍需人工。建议后续把构建 commit 写进 `dist/README.md` 的包表格（VOCA-186 已有此要求，缺执行载体）。
