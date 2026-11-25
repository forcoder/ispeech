# 智语 iSpeech 1.6 — 内测安装包（Android）

搜狗五笔 86 复刻原型 · Kotlin + Jetpack Compose · 五笔86 引擎 + 拼音混输 / 模糊音 + 九宫格 T9 +
简繁输出 + 颜文字 / 更多符号 + 输入统计 + csBaby 快捷短语（收藏 / 最近）+ 触屏软键盘 + AI 回复。

## 11-25 重打（最终包作废替换：BUILD `84d2a2b` / MAIN `d9690f9`）

11-24 那份最终包（`88062bdb…0d820`，BUILD `f1d9124`）**已作废**：它的基线不含 main 的 `9564cf7`(#42) 与 `d9690f9`(#36)，
装它跑 VOCA-208 用例 4（同步后列表刷新）必被误判成修复失败。本轮按 `d9690f9` 重打：

- 新基线：merge 提交 **`84d2a2b`**（发布分支 `98df9d1` + `origin/main` `d9690f9`，无冲突；版本号维持 6 / 1.6）
- 新包：`ispeech-internal-v1.6-final-20261125.apk`，sha256 **`b44c5ec17f5e9470834ace5e796d99a8e8c43b90f2e350d9cffd24244e18bcce`**，139,299,145 字节
- 门禁：`assert_apk_fix.sh <包> 84d2a2b d9690f9` → **exit 0**（C1…C5 / C9 / dex 字节全 PASS）；4 ABI、Android Debug 证书、6/1.6 逐项复验通过
- 装机：模拟器 `emulator-5554` 装入后 `versionName=1.6`、`versionCode=6`，设备 `base.apk` sha256 与构建侧逐字节一致（**真机仍未达成，AC-DIST 不记交付**）
- 配置变化：**零配置变化**（`asrModelUrl` 默认值未动，构建不带 `-PasrModelUrl`；仅版本号沿用 6/1.6 与文档登记）

**本版定位**：内测分发 · Android Debug 证书签名 · 含 FunASR 离线语音运行时（4 ABI）· 默认模型源为 OSS HTTPS。
**11-24 W-B 重打**：旧最终包 `ispeech-internal-v1.6-final-20261119.apk`（出包提交 `1597c80`）**已作废**，三个理由：① 落后 main 5 个提交（`0168e59` / `7eb60a4` / `52ae190` / `d33d025` / `bb1bf2b`），缺 VOCA-127 自学习、#30 读盘断言、#24 键盘规格、#31 下载校验、#22 新门禁；② #22 带入的 `check_dist_manifest.py` 判 release 树不合法（缺文件 `ispeech-internal-v1.6-final-20261119.apk`）；③ main 仍是 5/1.5，6/1.6 只在发布分支。新基线 **MAIN_NEW = `bb1bf2b2`**（旧 `a9c5109` 作废）。
**11-24 16:0x CST 重打已完成**：`assembleRelease` @ `f1d9124` → 本机 debug keystore 重签 → `assert_apk_fix.sh` **final 与 probe404 两模式均 exit 0** → 装机实测 `versionName=1.6` 且设备侧 `base.apk` sha256 与构建侧逐字节一致。下方 sha256 / 文件名 / 字节数为本批次实测值。试包 `ispeech-internal-v1.6.apk` 保留作基线证据，**不作为交付包**。

## 本版文件（v1.6）

| 文件 | 说明 |
| --- | --- |
| `ispeech-internal-v1.6-final-20261125.apk` | **内测交付包（最终包·11-25 重打）**：versionCode 6 / versionName 1.6，Android Debug 证书（SHA-256 `85:7C:E6:4E:EB:E7:FE:D7:C5:15:96:ED:05:70:30:F7:0D:38:10:2E:30:01:16:CD:5A:CA:8B:4F:9F:66:A0:ED`），含 4 ABI `libsherpa-onnx-jni.so`，构建基线 **BUILD = merge 提交 `84d2a2b`**（吸收 main `d9690f9`）。**sha256 `b44c5ec17f5e9470834ace5e796d99a8e8c43b90f2e350d9cffd24244e18bcce` / 139,299,145 字节**；字节不在 git 树内，取件见下方「取包方式」 |
| `ispeech-internal-v1.6.apk` | 试包（11-15 基线证据，sha256 `9cddcec5…43f42`）：路径与字节不动，不含窗口修复，勿用于验收 |
| `SHA256SUMS` | 完整性校验，**生成物**：唯一写入路径是 `python3 tools/verify/check_dist_manifest.py --regen`（口径见 `docs/adr/VOCA-400-dist-checksum-manifest.md`）。树外最终包**不入本表**（无文件即无行，写了会判 FAIL），其期望值只登记在下方「取包方式」 |
| `_archive/` | 历史包 v1.0–v1.5，**均不含语音**（无 sherpa-onnx / FunASR 运行时），仅用于回归对比 |

## 取包方式（产物必须可被他人取到）

**最终包字节既不在 git 树内，也不在 issue 附件**（11-19 三次实测：单包 413、双包 413、278 MB 上传超时未建评论；>139 MB 超平台单请求上限。PM 11-19 05:23 裁定「不裁剪、不分卷」，按交付计划六·四第 5 条走 adb 直传退路）。本批次（11-25）已实测**装机通道成立**：`adb install` 后设备侧 `base.apk` sha256 = 构建侧 `b44c5ec1…8bcce`，逐字节一致（**在模拟器 `emulator-5554` 上取的读数；AC-DIST 要求真机，此项仍未达成**）。字节**只在出包机单副本、离机不可核**：表中 `~/ispeech-artifacts/` 与 `refs/archive/` **都在同一台出包机上**，远端 `refs/archive` 实测 0 条（`git ls-remote origin | grep -c refs/archive` = 0）—— 这不是离机可取的第二副本：

| 包 | ref | git blob（40 位 object id） | sha256 | 字节 |
| --- | --- | --- | --- | --- |
| `ispeech-internal-v1.6-final-20261125.apk`（**当前交付包**） | `refs/archive/voca-186/v1.6-final-20261125` | `8557c633f2f2d49e9f1a0b28bd73a0b6196eb5e5` | `b44c5ec17f5e9470834ace5e796d99a8e8c43b90f2e350d9cffd24244e18bcce` | 139,299,145 |
| ~~`ispeech-internal-v1.6-final-20261124.apk`~~（作废：基线 `f1d9124` 不含 #36/#42） | `refs/archive/voca-186/v1.6-final-20261124` | `4ec138d4ea100b6f21f4a260c77fe363a0016e54` | `88062bdba552fd1c…b32319750d820` | 139,295,049 |
| ~~`ispeech-internal-v1.6-ac6-probe404-20261124.apk`~~（作废） | `refs/archive/voca-186/v1.6-ac6-probe404-20261124` | `78c88c55ad5b64d1b234cdb9462bd7f27847f8f4` | `cee7e1ef7770e194…c6445ad374f1` | 139,295,049 |
| ~~`ispeech-internal-v1.6-final-20261119.apk`~~（作废，仅回滚用） | `refs/archive/voca-186/v1.6-final-20261119` | `564911634317238d23493bee95dc2a548e2eab9c` | `736236292856ada2…d1b430a6` | 139,258,185 |
| ~~`ispeech-internal-v1.6-ac6-probe404.apk`~~（作废） | `refs/archive/voca-186/v1.6-ac6-probe404` | `a182868bd48c7f279c3cfb0349da7ee345c1d920` | `3e46f92630ead588…2bffef5a` | 139,258,185 |

```bash
# 在出包机的 ispeech 仓库内执行（blob id 不是文件 sha256，两者哈希体系不同，别拿 64 位 sha256 去喂 git）
git cat-file blob refs/archive/voca-186/v1.6-final-20261125 > ispeech-internal-v1.6-final-20261125.apk
shasum -a 256 ispeech-internal-v1.6-final-20261125.apk   # 期望 b44c5ec17f5e9470834ace5e796d99a8e8c43b90f2e350d9cffd24244e18bcce（139299145 字节）
cd dist && shasum -a 256 -c SHA256SUMS                   # 校验的是清单里那 9 行（试包 + _archive），不含最终包
```

- **分发与验收通道**：装机现场 `adb install -r` 直传 + 设备侧 `base.apk` sha256 与构建侧登记值**双向核对**，两向都对上才算字节交付（PM 11-19 05:23 裁定口径；完整规程见 VOCA-186 附件 `v1.6-final-build-20261119.md`「adb 退路执行规程」）。AC-6 包同规程，**仅取证装机，禁进内测通道**。
- **残余风险（外链 go/no-go 在 VOCA-419 待人类裁决）**：上表各 ref 仍只存在于出包机本机对象库（`refs/archive` 远端 0 条，未推远端），`~/ispeech-artifacts/` 同在出包机上，仍无他人可独立拉取的离机外链；出包机重装或对象库被清理即彻底丢失。补副本的两条路已排除：本仓为**公开仓**，把 `refs/archive/voca-186/*` 推上远端等于对外发布内测包，且 139,299,145 B 超 GitHub 100,000,000 B 单文件硬限（11-19 该仓 pre-receive 已实测拒绝 139,171,786 B blob）；issue 附件通道 11-19 三次实测被拒（413 两次 + 278 MB 超时）。唯一剩余方案 = OSS 私有读对象 + 在 `SHA256SUMS` 同行登记 URL 与 sha256（花钱 + 对外发布，需人类明确 go）。
- **为什么不在 git 树内**：窗口后 main 含 Vosk 引擎（`07214d6`），4 ABI `libvosk.so` 使整包从试包 98,416,118 B 增至
  139,299,145 B，超过 GitHub 100,000,000 B 单文件硬限（runbook §6 R1，触发点 = 第 8 步构建后 stat）。
  按 R1 口径「发布分支只承载元数据」：最终包**不入 `dist/SHA256SUMS`**（它是 >100 MB 未跟踪包，按 ADR-0003 §4 三态判 FAIL，登记了反而让 `check_dist_manifest.py` 判孤儿行）；期望值登记见上方取包表，字节按 ref 取回后自行 `shasum -a 256` 对读。口径出处：ADR-0003 §4「最终包不走清单」那一段。
- 出包 commit = merge 提交 `84d2a2b`（发布分支 `release/voca-186-v1.6` 上，吸收 main `d9690f9`）；构建基线 = `84d2a2b` 树，`git merge-base --is-ancestor d9690f9 84d2a2b` = YES（C9）。
  门禁与证据可在发布分支上复算：`git show origin/release/voca-186-v1.6:docs/voca-186-rebuild-20261125.log`。
  门禁命令：`bash tools/deploy/assert_apk_fix.sh <包> 84d2a2b d9690f9` → exit 0（17 项 PASS / 0 FAIL）。
- **试包**仍可从发布分支取（字节未变）：

```bash
git clone --single-branch --branch release/voca-186-v1.6 <repo-url>
cd voca-186-v1.6/dist && shasum -a 256 ispeech-internal-v1.6.apk
# 期望：9cddcec5a88d53017c5680eb07ba52e643d523d273f5d3649180df44f8d43f42（98416118 字节）
```

- 最终包门禁 = VOCA-190（FunASR 接线）+ VOCA-170（设置页必崩）+ VOCA-121（联想去重，**必要项·不可裁剪**）+ VOCA-166（语音入口/麦克风）四条都合入 main 后重打；本窗口实际合入六条（另含 ④b VOCA-233 版本显示 PR #14、⑥ VOCA-496 就绪标记 PR #33）。**11-24 W-B 批次 WINDOW_HEAD = `bb1bf2b2`**（旧 `a9c5109` 作废）；`bb1bf2b2` 之上另有 `0168e59` 自学习、`7eb60a4` #30、`52ae190` #22、`d33d025` #24、`bb1bf2b` #31 五条。验收判据 = VOCA-183 交付计划 六·五 / AC-1…AC-10；缺任一条不出包。
- **判据以包内字节为准，不以本文件的变更清单为准**：最终包已跑 `assert_apk_fix.sh`（在树断言 C1…C5 + C9 + dex 字节），exit 0；不命中即包不合规、不发。

## v1.6 变更（相对 v1.5）

- 版本号 `versionCode 6` / `versionName 1.6`；应用内主页与「关于智语」显示读 `BuildConfig.VERSION_NAME`（VOCA-233 已入包），与系统「应用信息」页一致
- 默认 `asrModelUrl` 切到 OSS HTTPS（`delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com`），
  构建**不带** `-PasrModelUrl`；dex 内不再出现 `cvm.agentai0.com`
- 内置 FunASR 离线识别运行时：sherpa-onnx + Paraformer-zh-small，arm64-v8a / armeabi-v7a / x86 / x86_64
- 签名：Android Debug 证书（非 release 自签证书），可与既有内测包同证书覆盖安装，不用先卸载
- 出包基线：**BUILD = `f1d9124`**（merge 提交，父 `c960914` + main `bb1bf2b2`，11-24 W-B 重打；旧 `2b393b2` / `a9c5109` / `1597c80` 作废）；APK 由该提交 `assembleRelease` 产物用本机 debug keystore 重签
- **含** VOCA-121（联想去重）修复：dex 四 token（`AssociationCommit` / `textToCommit` / `suffixPrefixOverlap` / `ASSOCIATION_ANCHOR_CHARS`）全命中；「你」+「你好」→「你好」、`zhidao`→「指导老师」+「师专」→「指导老师专」
- **含** VOCA-170（设置页 NPE）修复：`KeyboardViewModel` 声明先于 `init{}`，护栏单测在树
- **含** VOCA-166（语音入口/麦克风）修复：未授权不再顶掉输入法，候选条提示 + 主动「去授权」（`MicGrantAction`）
- **含** VOCA-496（模型就绪判定）修复：专用完成标记 `.ready-<modelId>` + 原子解压 + 零流量自愈
- 另含 main 原有 Vosk 离线引擎（`07214d6`，设置页可选 VOSK；其 4 ABI 原生库是整包超 100 MB 的原因）

## 出包门禁（2026-11-24 最终包实测，全部通过）

| 门禁 | 结果 |
| --- | --- |
| 出包门禁脚本 | `bash tools/deploy/assert_apk_fix.sh <最终包> 84d2a2b d9690f9` → **exit 0**（C9 祖先 YES + C1…C5 在树断言 + dex 字节 + 交付文档） |
| 原生库 ABI 数 | `unzip -l … \| grep -c libsherpa-onnx-jni.so` = **4**（arm64-v8a / armeabi-v7a / x86 / x86_64） |
| 模型源在 dex | 命中 `delivery-service-smzk`；`cvm.agentai0.com` = **0** |
| 签名证书 | `apksigner verify --print-certs` → `85:7C:E6:4E:EB:E7:FE:D7:C5:15:96:ED:05:70:30:F7:0D:38:10:2E:30:01:16:CD:5A:CA:8B:4F:9F:66:A0:ED`（Android Debug） |
| 版本元数据 | `aapt2 dump badging` → `versionCode='6'` / `versionName='1.6'` |
| 装机双向核对（AC-DIST，**仅模拟器**） | `adb install` Success → `dumpsys package com.ispeech.ime` = `versionName=1.6` / `versionCode=6`；设备 `base.apk` sha256 = `b44c5ec1…8bcce` = 构建侧（两向一致）。设备为 `emulator-5554`（`ro.kernel.qemu=1`），**AC-DIST 要求 ≥1 台真机，该项仍未达成** |
| dex 字节断言（本批次） | `AssociationCommit`=23 / `textToCommit`=6 / `suffixPrefixOverlap`=3 / `ASSOCIATION_ANCHOR_CHARS`=1 / `FUNASR`=12 / `delivery-service-smzk`=2 / `cvm.agentai0.com`=0 |
| 模型源可用 | `HEAD` 200 / `Content-Length: 77920048` / `Range: 0-1023` → 206（11-19 复测） |

## 入包字节断言（最终包必做；试包历史取证保留）

上面的门禁只证明「包是怎么签的」，不证明「某个修复在包里」——1.6 试包就是在这里翻的车。
所以最终包（以及任何被声称「含某修复」的包）出完后，先跑下面这段：把它存成 `assert_apk_fix.sh`，`bash assert_apk_fix.sh <apk>`，**exit 非 0 = 包不合规，不发**。
（完整出包门禁 = 本发布分支 `tools/deploy/assert_apk_fix.sh`，出包提交自带，含在树断言与交付文档检查；下面这份是 VOCA-121 单修复的最小断言。）

```bash
#!/usr/bin/env bash
# 入包字节断言：APK 里必须出现「修复项」的 dex 符号；缺任一项 → exit 1（包不合规，不发）
# 用法：bash assert_apk_fix.sh dist/ispeech-internal-v1.6-final-20261124.apk
APK="${1:?用法: bash assert_apk_fix.sh <apk 路径>}"
BT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/34.0.0"
DEXDUMP="${BT}/dexdump"
[ -x "$DEXDUMP" ] || DEXDUMP="$(command -v dexdump || true)"
[ -n "$DEXDUMP" ] || { echo "缺 dexdump：装 Android build-tools，或用兜底命令：unzip -p '$APK' 'classes*.dex' | grep -ac 'Lcom/ispeech/engine/AssociationCommit;'"; exit 2; }
D="$(mktemp -d)"; trap 'rm -rf "$D"' EXIT
unzip -o -q "$APK" 'classes*.dex' -d "$D"
: > "$D/dex.txt"
for f in "$D"/classes*.dex; do "$DEXDUMP" -d "$f"; done > "$D/dex.txt" 2>/dev/null
printf 'APK：%s\n大小：%s 字节 dex 数：%s\nsha256：%s\n' "$APK" "$(wc -c < "$APK" | tr -d ' ')" "$(ls "$D"/classes*.dex | wc -l | tr -d ' ')" "$(shasum -a 256 "$APK" | awk '{print $1}')"
MISS=""
for t in 'Lcom/ispeech/engine/AssociationCommit;' textToCommit suffixPrefixOverlap ASSOCIATION_ANCHOR_CHARS; do
  n="$(grep -c -- "$t" "$D/dex.txt" || true)"
  printf '%-40s %s\n' "$t" "$n"
  [ "${n:-0}" -gt 0 ] || MISS="$MISS $t"
done
if [ -z "$MISS" ]; then echo "入包断言 PASS"; else echo "入包断言 FAIL，未命中:$MISS → 包不合规，不发"; exit 1; fi
```

断言的 token 与判据（VOCA-121，来源 = PR #10 的 `app/src/main/java/com/ispeech/engine/AssociationCommit.kt` 与 `IspeechImeService.kt`；`isMinifyEnabled = false`，符号名在 dex 里原样保留）：

| token | 期望 | v1.6 试包 `9cddcec5…`（3 dex） | 121 被测包 `ccdbfa83…`（17 dex，正对照） |
| --- | --- | --- | --- |
| `Lcom/ispeech/engine/AssociationCommit;` | ≥ 1 | **0** | 23 |
| `textToCommit` | ≥ 1 | **0** | 6 |
| `suffixPrefixOverlap` | ≥ 1 | **0** | 3 |
| `ASSOCIATION_ANCHOR_CHARS` | ≥ 1 | **0** | 1 |
| 脚本 exit code | 0 | **1（FAIL）** | 0（PASS） |

- 两列数字都是 2026-11-16 实测，不是推算：试包列取自本分支 git blob `6607aafa`（`git cat-file blob` 导出），
  并与 `FMR0224913042917` 现装的 `base.apk` 逐字节相同——`adb pull` 后 `shasum -a 256` = `9cddcec5…`、98,416,118 字节、
  `versionCode=6 / versionName=1.6`，所以「仓库里那份」和「手机上装的这份」是同一份字节，任一边取证都覆盖另一边。
  对照包列取自 VOCA-121 附件 `ispeech-v1.5-voca121-arm64.apk`（装机专员在 `23F0224224000950` 上验证过的 `ccdbfa83…`）。
- 正对照包 = 装机专员在 `23F0224224000950` 上验证过的那份（VOCA-192 `01a1da16`），它是 v1.5/5；**不要拿它覆盖已装的 1.6/6**（版本号方向不对，会触发降级），复跑 AC-1…AC-5 只用最终包。
- 新增修复若带来新类 / 新常量，出包前把它的 dex 符号追加进上面 `for t in …` 与本表；只用 `grep -a` 兜底时判「有没有命中」，不要拿它的行数当计数。

## AC-6 取证变体包（不是交付包，禁止进内测通道）

- 文件：`ispeech-internal-v1.6-ac6-probe404-20261125.apk`（取件走 `refs/archive/voca-186/v1.6-ac6-probe404-20261125`，blob `4ee85ee8fc72629523a2c9f8bd977700c9121201`，非 issue 附件）
  sha256 `b347a3939d9263e78685b00c4155e1b53dc432271ca45e468fc892b777dedb45`（139,299,145 字节）
- 与最终包**同一出包 commit / 同 versionCode 6 / 同 debug 证书 / 同 ABI**，仅构建时命令行覆盖
  `-PasrModelUrl=https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/v1.6-ac6-404-probe-does-not-exist.tar.bz2`
  （HEAD/GET/Range 三段全 404 + `NoSuchKey`，11-19 复测；override 不落 main，`app/build.gradle.kts` 兜底值未动）。
- 用途：11-20 装机时跑 AC-6（模型源 404 → 可读报错）。**模型永远下不下来**：不进内测通道、不覆盖试包与最终包、
  不进 `SHA256SUMS` 交付行（本节即其单独登记）；装机后请卸载或覆盖回最终包。
- 门禁：`APK_MODE=probe404 bash tools/deploy/assert_apk_fix.sh <该包> 84d2a2b d9690f9` → exit 0（变体构建正确；实测 404 探针 URL 命中 2 处、正式 object key 0 命中）。

## v1.5 变更（历史，相对 v1.4）

- **拼音混输**：`assets/pinyin86.dict`（44684 行，三源 MIT 数据 + sha256 固定）+ `PinyinEngine`
  （全拼 / 简拼 / 前缀三层，兜底档位排在五笔之后）；设置页开关
- **模糊音**：z=zh、c=ch、s=sh、n=l、an=ang、en=eng、in=ing；设置页开关
- **简繁输出**：`HanConversion` + OpenCC（Apache-2.0）裁剪表；设置页开关
- **九宫格 T9**：数字页底行「9键」进入，数字进独立缓冲 → 展开字母组合 → 查九宫格专用拼音引擎；
  `1` 标点页、`*` 符号页、`#` 更多符号、`☺` 颜文字直达页
- **颜文字 / 更多符号**：颜文字 7 类 103 条（追加在表情页末尾）、更多符号 6 页（符号页共 8 页）
- **切换系统输入法**：工具栏第 1 键「⌨输入法」→ 输入法列表面板 + 系统选择器兜底
- **输入统计**：今日 / 累计字数、上屏次数、候选命中率、Top 词（设置页「输入统计」）
- **候选豆腐块过滤**：字体缺字形的生僻字（如 `wqiy` 的 `𠉎`）不再出现在候选条
- **快捷短语收藏 / 最近**：面板 ★ 收藏与最近胶囊、条目星标、使用计数
- **修复**：设置页改「拼音混输 / 模糊音 / 词库」后，运行中的输入法无需重启即生效（引擎代次重取）

## 安装

1. `adb install -r ispeech-internal-v1.6-final-20261119.apk`，或将 APK 拷进手机点击安装（要求 Android 8.0+ / minSdk 26）。
   已装旧内测包（同 debug 证书，含试包）可直接覆盖，不需要先卸载；若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 说明证书不同，**不要卸载清数据**，先回报。
2. 系统设置 → 语言与输入 → 管理输入法 → 启用「智语」。
3. 任意输入框：通过输入法切换器（长按空格 / 通知栏）切到「智语」，或直接在键盘工具栏点「⌨输入法」。

## 使用要点

- 五笔 86：`wqiy`→你（词组「仰泳」带「词」标签在候选里）、`trnt`→我、`vbg`→好；`z` 通配符
- 拼音兜底：`nerf`→扰动 / 哪儿 这类打不出五笔码的输入（需开「拼音混输」）
- 九宫格：数字页「9键」→ `64`→你、`983`→说；空格上屏首选，无候选原样上屏数字串
- 快捷短语：`hi` →「你好问候」→ 空格整段上屏；工具栏「短语」打开面板（★收藏 / 最近 / 分类筛选）
- 上屏键：空格 / 回车 / 数字 1–9；退格删码；Esc / 清 清空组合；候选条「‹ 1/3 ›」翻页
- **AI 回复**：输入框写好要回复的消息 → 点「AI」键 → 候选条出现「AI回复」→ 空格上屏并替换原文（需先登录 csBaby）
- 设置：候选条右侧齿轮 / 键盘工具栏齿轮 / 桌面「智语」图标

## 已知限制

- 无双拼 / 手写 / 五笔 98 / 云词库；皮肤仅主题色
- **语音整链本版无运行时验收判据**（AC-4/5/7 待 11-20 真机 + AC-5 真人时段）：FunASR 接线、模型就绪判定已入包，
  但「识别准确率 / 端到端可用」未经真机验证；灰度全绿不等于语音已验证
- **无结构化语音日志**（VOCA-231 未批准进本窗口）：语音异常请附机型 + 录屏，`logcat` 此时给不出 ASR 链路信息；
  失败只有可读文案、`pidof`、`logcat -b crash` 三类观测
- 九宫格无音节切分（组合枚举上限 192 组、数字串 ≤9 位），>6 位数字时候选变少
- 简繁为字符级转换，不做词汇消歧
- 输入统计不含 AI 回复上屏
- 云端同步与 AI 回复需先在设置页登录 csBaby（`http://api.agentai0.com`）

## 构建与重签（可复现）

本机（macOS arm64，JDK 17 + Android commandline tools 34）直接出包并重签；远端构建机自带 debug keystore 与本机不同，
所以**产物必须在本机重签**，否则装不上已装旧包的手机。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
git checkout 84d2a2b                             # 11-25 重打 BUILD = 吸收 main d9690f9 的 merge 提交（旧 f1d9124 / 2b393b2 / a9c5109 / 1597c80 作废）
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties   # 本机文件，不入库
./gradlew assembleRelease                        # 不带 -PasrModelUrl / -PvoskModelUrl，走默认源

BT=$ANDROID_HOME/build-tools/34.0.0
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-type JKS \
  --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out ispeech-internal-v1.6-final-<本轮日期>.apk app/build/outputs/apk/release/app-release.apk
# 产物 >100 MB：不 commit 进 git（R1），走出包机 ref 保底 + 本文件「取包方式」登记（issue 附件与 GitHub 均实测拒收）
python3 tools/verify/check_dist_manifest.py --regen   # 清单按当前字节重写；禁止 `>> SHA256SUMS` 追加（#22 / ADR-0003）
```

## 校验

**问「我手上这包是不是仓库那包」，只用这一条命令：**

```bash
# 最终包字节先从出包机 ref 取回放入 dist/（取件口径见「取包方式」，不在 git 树内也不在 issue 附件），再：
cd dist && shasum -a 256 -c SHA256SUMS
```

`SHA256SUMS` 是**生成物**，口径与门禁见 `docs/adr/VOCA-400-dist-checksum-manifest.md`（ADR-0003）：

- 唯一写入路径是 `python3 tools/verify/check_dist_manifest.py --regen`，不要手改、不要 `>>` 追加
  （追加会让同一路径两行并存，`shasum -c` 只认后一行）；
- 它同时校验「记的数 == 实际字节」与「`dist/` 下每个**该入册**的 APK 都有记录」——后者是 main 上曾经
  静默漏掉 `_archive/` 三个历史包的那半个漏洞；
- 「该入册」= git 里已跟踪 + 会被 `git add -A` 扫进来的；**`.gitignore` 排除的包既不入册也不被要求入册**，
  所以**树外的最终包不出现在清单里**（写了会判「缺文件」FAIL），它的期望值只登记在本文件「取包方式」；
- 已接入 `tools/deploy/deploy.sh` 出包门禁（③ 硬门禁 + ⑤ 归档后 `--regen`），**不受 `--skip-verify` 管辖**；
- 清单只能证明**仓库字节**。装机时如果在仓库外重签过，那一包不在清单覆盖范围内，签名 / 版本号策略见 VOCA-159。

## 重出路线（门禁事后 FAIL / 基线错时）

1. 前提：本轮五条新合入已在 `main`（MAIN_NEW `bb1bf2b2`：`0168e59` 自学习 / `7eb60a4` #30 / `52ae190` #22 清单门禁 / `d33d025` #24 键盘规格 / `bb1bf2b` #31 下载校验）；发布分支已含吸收 main 的 merge 提交 **BUILD = `f1d9124`**（父 `c960914` + `bb1bf2b2`；已在远端分支 `agent/sre/voca-186-rebuild`，并经 cfg_gate PASS 后 FF 进 `release/voca-186-v1.6`）。旧 WINDOW_HEAD `a9c5109` 与旧出包提交 `1597c80` **作废**。
2. 从 BUILD `f1d9124` `assembleRelease` + 本机 debug keystore 重签（同上 recipe；`versionCode` 仍为 6，靠**新文件名 + 新 sha256** 区分批次）。
3. 更新本文件「本版定位 / 本版文件 / 取包方式」的包名、BUILD 与 sha256 三处；`SHA256SUMS` 只用 `--regen`（树外最终包不入表）；试包路径与字节不动。
4. **跑 `tools/deploy/assert_apk_fix.sh <apk> f1d9124 bb1bf2b2`（final 模式）+ `APK_MODE=probe404` 变体，均 exit 0 才算包合规**（11-24 两模式实测均 exit 0）；另跑 `python3 tools/verify/check_dist_manifest.py`（failed=0）与 `python3 tools/verify/check_keyboard_ui.py`（19/19）。不命中不覆盖发布分支、不装机，回第 1 步查合并漏了哪条。
5. 装机（`adb install -r`，同 debug 证书不用卸载、不丢词库）→ 交装机专员执行、测试工程师判定 AC-1…AC-5。

**回滚点**：新最终包 = 出包后本文件登记的 sha256（出包机 ref 取回，通道见「取包方式」）；**旧包回滚 = 出包机 ref `refs/archive/voca-186/v1.6-final-20261119`（sha256 `73623629…430a6`，blob `5649116…ab9c`）**，仅在新包门禁 FAIL 时退回；试包 = 本分支 `dist/ispeech-internal-v1.6.apk`（sha256 `9cddcec5a88d…`，98,416,118 字节），`git checkout release/voca-186-v1.6 -- dist/` 取回后 `adb install -r` 覆盖即可，不需要卸载；
main = 本轮起点 `bb1bf2b2`（其上五条各一个 squash 提交，逆序 `git revert bb1bf2b d33d025 52ae190 7eb60a4 0168e59` 可逐条退）；降级 v1.5 需卸载重装（清私有数据，须人类确认），包从本分支 `_archive/ispeech-release-v1.5.apk` 取（sha256 `490b152a…d7b6`）。
