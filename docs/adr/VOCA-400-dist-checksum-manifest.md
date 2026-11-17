# ADR-0003：`dist/SHA256SUMS` 是生成物，唯一写入路径是 `--regen`（VOCA-400）

- **状态**：Proposed（待统一合并窗口随 PR 落地；进入 main 前不改 main）
- **决策日期**：2026-11-17
- **决策人**：SRE工程师（agent `499aaa3a-4c36-438a-9923-6fb63c46449a`）
- **关联 issue**：VOCA-400（本 ADR）；源自 VOCA-168 §8.4 澄清件，取证链断点在 VOCA-167
- **关联既有文档**：`dist/README.md`「构建与实测环境 / 校验」两节、`tools/verify/README.md`、VOCA-186「最终包门禁固定四项」
- **本文的基线口径**：所有结论以 **`origin/main` = `fa57a07`** 为准；发布分支 `release/voca-186-v1.6`（`eba7a61`）的差异在 §6 逐条对读。
- **修订**：2026-11-17 按 VOCA-400 质检打回项修订三处 —— D2 补「第五条门禁覆盖不到 debug 产物」这条边界、§3 取证命令改为可原样复现的形式、§4 补未跟踪超限包的处置口径；场景数由 7 增至 8。
- **二次修订**：2026-11-17 按质检 PASS-WITH-CAVEAT 的 Z1/Z2 修三处。① §6 第 2 条与 §4 对超限包的判定口径不一致 —— 核对时发现**不只是文案漂移，机器打的也不是 `WARN`**：`dist/` 下已跟踪的超限包在门禁里打的是一行 `PASS`，连「超限」两个字都没有；已把判定改成三态（§4 体积规则），并让 `--self-test` 的超限场景**同时断言两个分支的输出级别**（§4 末）。② §3「签名侧实测」那张表原先没写命令、无法复现，补上本机真跑得通的取证书 / 取指纹路径（本机**没有** `apksigner`）。③ 交付评论 §⑥ 把指纹归给 `apksigner verify --print-certs` —— 那条命令在本机不存在，归因已在那条评论里就地更正（数值未动）。
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

**D2 的覆盖边界（质检打回项 1，写死在这里）**：`.gitignore` 排除的包既不入册、也不被要求入册，而 `tools/deploy/deploy.sh` **不带 `--release` 时**的产物名恰好是 `dist/ispeech-debug-<versionName>-<yyyyMMdd-HHmm>.apk`，落在 `.gitignore:43` 的排除里。所以：

- **第五条门禁的「归档即记录」只对 `--release` 路径成立**，对默认的 debug 路径不成立。实测反例：把一个 `ispeech-debug-1.6-<ts>.apk` 放进 `dist/`，门禁的穷尽覆盖不要求它入册，`--regen` 也不会记它（`rc=0`）；同期放进去的 `ispeech-release-1.6-<ts>.apk` 则被要求并记录。
- 机器侧已消掉「文档说的和机器做的不一致」：`deploy.sh` ⑤ 现在按本包是否真进了清单分支打印 —— 入册则 `本包已入 dist/SHA256SUMS：<路径>`，未入册则 `[warn] 本包未入 dist/SHA256SUMS … 被 .gitignore 排除，清单答不了它的字节`，并给出自查方式。**不再无条件宣称「新包在离开工位前就已经被记录」。**
- debug 产物的自查口径（不走清单，走这一条）：出包日志 `dist/deploy-<ts>.log` 里 ⑤ 打印的 `sha256=` 就是那台设备上装的字节；设备侧对读 `adb -s <serial> shell dumpsys package com.ispeech.ime`（`versionName` / `versionCode` / `firstInstallTime`，`deploy.sh` ⑦ 结尾已顺带打印）。**这份 sha256 只在那台开发机的日志里，不在仓库里**，换人换机就查不到 —— 这是政策代价，不是实现缺陷。
- **与 §1 待拍板推荐项的冲突必须点明**：VOCA-159 的 Q1 推荐「内测包走 debug 证书」。若照该推荐走，**真正会上用户机的正是这条不入库的 debug 路径**，第五条门禁对「手机上装的是哪一包」这个原始问题依然一条都覆盖不到。因此本 ADR 不把「门禁已接上」当成 VOCA-167 的取证问题已解决；那一半只在 Q2（重签产物必须落进 `dist/` 并 `--regen`）拍板并落地后才闭合。
- 否决的第三条路（改 ⑤ 命名或 `.gitignore` 让 debug 产物也入册）：那会让每次 `deploy.sh` 默认跑都往 git 里塞一个 15–23 MB 的新二进制，与 VOCA-292（正在收敛 main 跟踪产物）反向，且正是本次把任务分支推到 GitHub 时被 GH001 拒推的同类动作。产物要不要入库归 VOCA-292 判，本 issue 不抢。

**D3 — 记错即失败，不是警告。** 校验任一条不符 → 退出码 1 → 出包中断。

**方案 B（把对不上号的历史包移出清单）已否决**，理由：清单要回答的是「手机上那包是谁」，而人们手上留着的恰恰是历史包（用户机上就是 v1.5）。从清单里划掉 `ispeech-release-v1.5.apk` 会让最常见的自查场景彻底无机检。对不上号的正确解法是**让记录服从字节**，不是让字节服从记录。

## 3. 两条 FAILED 的取证：`f6155fa0…` 不是「重签前的旧校验和」

issue §4 的推断（重签导致漂移）**不成立**。逐 blob 跑 `shasum -a 256` 对读历史提交：

| 文件 | 清单里的值 | 实际值 | 实际值的来历 |
|---|---|---|---|
| `ispeech-release-v1.5.apk` | `f6155fa0…` | `490b152a…` | `f6155fa0…` 是 `72bb547`（10-17 15:48）里该路径 blob 的真实 sha256；`904a98d`（11-09 12:05）把同名文件换成另一份构建，清单未跟着改 |
| `ispeech-debug.apk` | `f21072d5…` | `b308f7ad…` | 同上，同一对提交 |

```
$ for c in $(git log --all --format=%H -- dist/ispeech-release-v1.5.apk); do
    git rev-parse -q --verify "$c:dist/ispeech-release-v1.5.apk" >/dev/null || continue
    printf '%s  %s…\n' "$(git log -1 --format='%h  %ci' "$c")" \
        "$(git cat-file blob "$c:dist/ispeech-release-v1.5.apk" | shasum -a 256 | cut -c1-16)"
done
904a98d  2026-11-09 12:05:55 +0800  490b152ad20df7af…
72bb547  2026-10-17 15:48:32 +0800  f6155fa0a1161833…
```

> 上面这条是**可原样复现**的形式：`|| continue` 挡掉了 `a9d1dae`（`chore(release): 版本号升到 v1.6`，
> 该提交把此路径移进 `_archive/`，直接 `git cat-file blob` 会吐 `fatal: … exists on disk, but not in`
> 并给空字节算出 `e3b0c442…` 这个假值）。此前贴在评论与 VOCA-159 里的那两行是**摘录**，
> 不是 `git log … | while read` 的原文；该写法在本仓库实测为「3 行 + 1 条 fatal」，已按本节替换。
> 另有一条独立取证不依赖此输出：`git show --stat 904a98d -- dist/` 只改两个 apk 的字节、
> **没碰 `dist/SHA256SUMS`**（清单最后一次写入停在 `72bb547`）。

即漂移根因是**同名重打、原地覆盖、清单不动**（`tools/deploy/deploy.sh` ⑤ 就是这套动作，且它一直只 `info` 打印 sha256、从不落盘），与签名无关。签名侧实测：

| 对象 | 证书 DN | 证书 SHA-256 |
|---|---|---|
| 仓库当前 `dist/ispeech-release-v1.5.apk`（`490b152a…`） | `CN=iSpeech Prototype` | `70b4f4a2…cf2ac7` |
| 仓库当前 `dist/ispeech-debug.apk`（`b308f7ad…`） | `CN=Android Debug` | `857ce64e…f66a0ed` |
| VOCA-159 11-14 12:28 装进用户手机的那包 | `CN=Android Debug` | 同上（是仓库外重签副本 `e1de4def…`，**不在仓库、也不在任何清单**） |

上表**本机可复现**，但复现路径要先说清楚：本机**没有** `apksigner`（无 Android SDK），而这两个包**只有 v2 签名**（v1 条目数为 0），所以证书只能从 APK Signing Block 里取。一次性取证脚本，不进仓库（不属出包链路）：

```bash
$ command -v apksigner || echo "apksigner: 本机没有（无 Android SDK）"
apksigner: 本机没有（无 Android SDK）
$ unzip -l dist/ispeech-release-v1.5.apk | grep -cE "\.(RSA|DSA|EC)|\.SF"
0                                     # 无 META-INF/*.RSA、*.SF → 没有 v1 签名
$ cat > /tmp/carve_apk_cert.py <<'PY'
"""从 APK 的 APK Signing Block（v2/v3 签名）里取签名证书 DER，写到 stdout。
本机没有 apksigner / Android SDK 时用这个：只需要 python3 + openssl。"""
import pathlib
import re
import struct
import sys

d = pathlib.Path(sys.argv[1]).read_bytes()
eocd = d.rindex(b"PK\x05\x06")
cd = struct.unpack_from("<I", d, eocd + 16)[0]          # 中央目录起点
if d[cd - 16:cd] != b"APK Sig Block 42":
    sys.exit("没找到 APK Sig Block 42 —— 这包没有 v2/v3 签名")
size = struct.unpack_from("<Q", d, cd - 24)[0]          # 签名块长度
blk = d[cd - 24 - size:cd - 24]
for m in re.finditer(rb"\x30\x82", blk):
    i = m.start()
    n = struct.unpack_from(">H", blk, i + 2)[0] + 4      # DER: SEQUENCE + 2 字节长度
    if blk[i + 4:i + 6] == b"\x30\x82" and len(blk[i:i + n]) == n:
        sys.stdout.buffer.write(blk[i:i + n])            # 首个内层也是 SEQUENCE 的 = 证书
        sys.exit(0)
sys.exit("签名块里没有 X.509 证书")
PY
$ python3 /tmp/carve_apk_cert.py dist/ispeech-release-v1.5.apk > /tmp/v15.der
$ openssl x509 -inform DER -in /tmp/v15.der -noout -subject -fingerprint -sha256
subject=C=CN, ST=Guangdong, L=Guangzhou, O=iSpeech, OU=iSpeech, CN=iSpeech Prototype
sha256 Fingerprint=70:B4:F4:A2:BE:35:CC:D9:A6:73:D3:1D:33:A1:E4:BF:C1:6A:20:8E:43:BD:D5:D8:BF:5D:1C:AB:10:CF:2A:C7
$ python3 /tmp/carve_apk_cert.py dist/ispeech-debug.apk > /tmp/dbg.der
$ openssl x509 -inform DER -in /tmp/dbg.der -noout -subject -fingerprint -sha256
subject=CN=Android Debug, O=Android, C=US
sha256 Fingerprint=85:7C:E6:4E:EB:E7:FE:D7:C5:15:96:ED:05:70:30:F7:0D:38:10:2E:30:01:16:CD:5A:CA:8B:4F:9F:66:A0:ED
```

`keytool -printcert -file /tmp/v15.der` 给出同一个值（`所有者: CN=iSpeech Prototype`、`签名算法名称: SHA256withRSA`、`2048 位 RSA 密钥`、版本 3）。取证书这一步不靠猜：两个包的签名块里满足「外层与内层都是 SEQUENCE」的候选**各只有 1 个**（块内偏移 104），所以「第一个能 parse 的就是证书」这条在这个仓库里是确定的，不是运气。

上一版这里写的是「实测（`apksigner verify --print-certs`）」—— 那条命令在本机不存在，读者按它复现只会得到 `command not found`；数值本身没错（`70b4f4a2…cf2ac7` / `857ce64e…f66a0ed` 与上面两行 `sha256 Fingerprint` 逐位一致），错的是归因。这就是质检 Z2 打回来的那条。

**由此得出本 ADR 的边界结论**：清单只能证明「仓库字节」，证明不了「手机字节」，因为交付路径上存在仓库外的重签变体。这条不是 VOCA-400 能修的，已按 issue 要求回写 VOCA-159（签名 / 版本号策略待拍板项），本 ADR 不代拍。

## 4. 门禁（VOCA-186 四项之后的第五条）

| 门禁 | 命令 | 可跳过 | 失败处置 |
|---|---|---|---|
| 五、随包校验清单与字节一致且穷尽 | `python3 tools/verify/check_dist_manifest.py` | **否**（不受 `--skip-verify` 管辖） | `--regen` 后重跑；仍 FAIL 则不出包 |

接线两处（`tools/deploy/deploy.sh`）：

- **③ 静态校验**：清单校验放在 `--skip-verify` 分支**之外**，硬 `die`。跳过它等于允许带一份会说谎的清单出包，而「会说谎」正是本次的原始故障。
- **⑤ 归档到 dist**：`cp` 完成后立即 `--regen`（内部先重写、再整份回读校验，任一 FAIL 即 `die`，出包不往后走），然后**按本包是否真进了清单分支打印**：入册 → `本包已入 dist/SHA256SUMS：<相对路径>`；被 `.gitignore` 排除（默认 debug 路径）→ `[warn]` 明说「未入册、清单答不了它的字节」并给出日志 sha256 + `dumpsys` 的自查口径。**「新包在离开工位前就已经被记录」只对 `--release` 成立**，边界与理由见 D2。

**包 >100 MB（GitHub 单文件硬限）：判定按 git 跟踪状态三态分流，处置写死**

| 状态 | 门禁 | 处置（谁 / 移去哪） |
|---|---|---|
| **未跟踪** | `FAIL`（不是 WARN），`--regen` 照记但整份回读仍 `rc=1` → ⑤ `die`，出包中止 | 执行人（出包/SRE）把包 `mv` 出仓库、移到本机**仓库外**目录（例 `~/ispeech-artifacts/`），再跑一次 `--regen` 收敛；移不走就地停下开单 |
| **已跟踪** | `WARN`，不阻断出包 | 属历史既有状态，判定权归 VOCA-292（要不要进 Git LFS），本门禁不抢；WARN 行写明「含它的分支推不上去」 |
| **跟踪状态未知**（无 git 元数据，`git archive` 解出来的包） | `WARN`，不阻断 | 分不清跟踪与否时既不能判 FAIL、**也不能谎称「已跟踪」** —— 文案跟着状态走，不然又是一处「文档说的和机器做的不一致」 |

为什么未跟踪那一态必须 FAIL：`--regen` 会把这种包照记进清单，而它推不进 GitHub（GH001）—— 清单一旦随包提交，就是把「仓库里永远不存在的字节」写成可信答案，与本 issue 要消灭的故障同类。仓库里 release/debug 包实测 15–23 MB，`dist/` 下出现 >100 MB 者按定义不是本仓库产物，而是被 runtime baseline 从工作目录扫进来的外来残留。

> **本条的来历（Z1，2026-11-17 二次修订）**：上一版这里写「已跟踪仍只 WARN」，§6 又写「对 >100 MB 的包给 WARN 不给 FAIL」，两处互相矛盾。核对时发现**机器做的和三处文案都不一样**：已跟踪超限那行调的是 `check(True, …)`，打的是 `PASS:` 且不带「超限」二字 —— 文档要表达的 WARN 在实现里根本没有出口，而只比退出码的 `--self-test`（`rc=0` 对 `rc=0`）看不见这个差别。现在判定显式走三态，超限场景同一次构造两个分支、**断言输出级别**（见下）。

**判定逻辑自身可验**（沿 `check_model_host.py --self-test` 先例）：

```bash
$ python3 tools/verify/check_dist_manifest.py --self-test
…（8 条 PASS）
PASS: [self-test] 超 100 MB 的包（未跟踪挡住 / 已跟踪只 WARN）：期望 已跟踪=WARN+rc0 / 未跟踪=FAIL+rc1，实得 已跟踪=1 WARN+rc0 / 未跟踪=1 FAIL+rc1
SUMMARY: passed=8 failed=0 warned=0
```

8 个场景含**双向**断言：好状态与「无 git 元数据（`git archive` 解包）退回目录扫描」两种状态放行，篡改一位 hex / 新增未记录包 / 孤儿行 / 同路径两行 / 格式非法 / **未跟踪且超 100 MB** 六种坏状态必须各自挡住。超限那条用稀疏文件（`truncate` 到 101 MB）构造，不占实际磁盘，同一个沙箱跑两个分支：`git add -A` 后要求出现 `WARN:` 行且 `rc=0`，`git rm --cached` 后（字节原地保留）要求出现 `FAIL:` 行且 `rc=1` —— 断言落在**输出级别**上，只看 `rc` 会像上一版那样对「WARN 其实是 PASS」视而不见。只测「好状态通过」的门禁等于没有门禁。

## 5. 回滚

纯工具 + 清单变更，不含业务代码，回滚 = `git revert` 本 PR 的提交。回滚后恢复的是「清单不再被机检」的状态，即 VOCA-400 的故障面，因此**不建议单独回滚校验脚本**；若必须止血，只回滚 `tools/deploy/deploy.sh` 的硬门禁（恢复 `--skip-verify` 可绕过），`--regen` 与清单本身可保留。

## 6. 与发布分支的对读、未覆盖项、移交

**与 `release/voca-186-v1.6`（`eba7a61`）：内容不矛盾，但合并会撞两处文件。** 该分支已把 5 个历史包移入 `_archive/` 并列出 9 条（含 `ispeech-internal-v1.6.apk` = `9cddcec5…43f42`）。本 ADR 重生成的 8 条 sha256 值与其中对应的 8 条**逐位相同**，差异只有路径前缀（因为本 PR 基线上的 main 还没移动文件）。

**合并窗口的冲突面（实测，别当「无冲突」）**：

```
$ git merge-tree --write-tree <本 PR> origin/chore/voca-121-dist-readme-gate
CONFLICT (content): Merge conflict in README.md          # 既有，与本 PR 无关
CONFLICT (content): Merge conflict in dist/README.md     # ← 本 PR 新增
CONFLICT (content): Merge conflict in dist/SHA256SUMS    # ← 本 PR 新增
```

`origin/main` 单相对该分支只有 `README.md` 一处冲突，`dist/README.md` 与 `dist/SHA256SUMS` 两处是本 PR 带进去的。收敛方式**不同**，不要一律手工调和：

- `dist/SHA256SUMS` 是生成物 → **不手工调和**，取任一侧后在合并窗口按当时 main 的实际布局跑一次 `python3 tools/verify/check_dist_manifest.py --regen`，全量覆盖即可；多分支并行各自 `--regen` 必然在这条文件上互相冲突，**单点 regen 是唯一收敛法**。
- `dist/README.md` 是手写文档（该分支携带 v1.6 归档重排）→ **需人工按「后合入者适配先合入者」处理**，重点是包表格与「校验」一节的路径口径，不能整份取 `--ours`。

**未覆盖 / 移交**：

1. → **VOCA-159**：重签变体的命名与记录策略（待人类拍板，已回写该单）。
2. → **VOCA-292**：`main` 跟踪 8 个 `dist/*.apk` 与 baseline 收尾扫进大二进制。本 PR 只处理了直接卡住交付的那一个文件（见下），通用防护归 VOCA-292。判定**按跟踪状态分流**（与 §4 一致）：**未跟踪**且 >100 MB 判 `FAIL` 并中止出包（处置见 §4 那张表）；**已跟踪**且 >100 MB 只给 `WARN` 不给 `FAIL`，就是为了不抢那条的判定权；**无 git 元数据**（`git archive` 解出来的包）同样只 `WARN`，写明「跟踪状态未知」。
3. `dist/README.md` 的打包段引用仓库根 `README.md` 与 `./ispeech-remote.sh`，**后者在本仓库不存在**（仓库根没有 `*.sh`）。本次只替换其中产生漂移的 `>>` 那一行，远端构建链路的文档失效另开单。
4. 清单不含**构建 commit** 与 `versionCode/versionName`。有 sha256 才能定「哪一包」，但定「这包是 main 哪个提交打的」仍需人工。建议后续把构建 commit 写进 `dist/README.md` 的包表格（VOCA-186 已有此要求，缺执行载体）。
