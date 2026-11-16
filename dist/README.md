# 智语 iSpeech 1.6 — 内测安装包（Android）

搜狗五笔 86 复刻原型 · Kotlin + Jetpack Compose · 五笔86 引擎 + 拼音混输 / 模糊音 + 九宫格 T9 +
简繁输出 + 颜文字 / 更多符号 + 输入统计 + csBaby 快捷短语（收藏 / 最近）+ 触屏软键盘 + AI 回复。

**本版定位**：内测分发 · Android Debug 证书签名 · 含 FunASR 离线语音运行时（4 ABI）· 默认模型源为 OSS HTTPS。
本包是**试包**：版本 / 签名 / 模型源 / 产物内容可验。语音入口修复（VOCA-166）与联想去重修复（VOCA-121）**均未合入本包**——
前者不在本包保证范围内，后者见「已知限制」；两条都随 VOCA-186 在 11-19 统一窗口重打的**最终包**生效，最终包会替换本文件。

## 本版文件（v1.6）

| 文件 | 说明 |
| --- | --- |
| `ispeech-internal-v1.6.apk` | **内测交付包（试包）**：versionCode 6 / versionName 1.6，Android Debug 证书（SHA-256 `85:7C:E6:4E:EB:E7:FE:D7:C5:15:96:ED:05:70:30:F7:0D:38:10:2E:30:01:16:CD:5A:CA:8B:4F:9F:66:A0:ED`），含 4 ABI `libsherpa-onnx-jni.so` |
| `SHA256SUMS` | 完整性校验（含 `_archive/` 历史包） |
| `_archive/` | 历史包 v1.0–v1.5，**均不含语音**（无 sherpa-onnx / FunASR 运行时），仅用于回归对比 |

## 取包方式（产物必须可被他人取到）

内测包以 **git 发布分支**交付，任何人可用一条命令取到字节并自查：

```bash
git clone --single-branch --branch release/voca-186-v1.6 <repo-url>
cd voca-186-v1.6/dist && shasum -a 256 ispeech-internal-v1.6.apk
# 期望：9cddcec5a88d53017c5680eb07ba52e643d523d273f5d3649180df44f8d43f42（98416118 字节）
```

- 发布分支 `release/voca-186-v1.6` = 出包提交 `199b972`（版本提交 `a9d1dae`、出包基线合并 `bc81c30`）+ 本文档；APK 与 `199b972` 里的是同一个 blob 对象，字节未变。
- 不走任务分支 `agent/sre/voca-186`：其历史含一个 132.72 MB 的 `dist/ispeech-release-1.5-20261115-1004.apk`（非本 issue 产物），超过 GitHub 100 MB 硬限制，`git push` 被 pre-receive 以 GH001 拒收；v1.6 包 93.86 MB 只触发 >50 MB 警告，可正常推送。
- 最终包门禁 = VOCA-190（FunASR 接线）+ VOCA-170（设置页必崩）+ VOCA-121（联想去重，PR #10，**必要项·不可裁剪**）+ VOCA-166（语音入口/麦克风）四条都合入 main 后重打，覆盖发布分支 `release/voca-186-v1.6` 并同步更新本节 sha256。验收判据 = VOCA-183 交付计划 六·五 / AC-1…AC-10；缺任一条不出包。
  - PR #10 当前 head = `980b496f524af51134e3773fca12a4a1fcbc1aa7`（本文此前写的 `e8dc6f1` 是其旧 head，11-16 并入 main `fa57a07` 解冲突后前移）；状态 OPEN / draft / MERGEABLE，计划 11-19 窗口 W③ 转 ready 后 squash 进 main。
- **判据以包内字节为准，不以本文件的变更清单为准**：本试包的「v1.6 变更」曾把 VOCA-121 记成已修复，而 dex 里 0 命中（取证见「入包字节断言」）。最终包出完必须先跑那条断言，**不命中即包不合规、不发**；
  发布分支的 main 指针 = 窗口当天 `git log -1 --format=%H origin/main` 记下的 commit，不要凭记忆写。

## v1.6 变更（相对 v1.5）

- 版本号 `versionCode 6` / `versionName 1.6`，装机后可凭版本号区分「含语音」的内测包与旧包
- 默认 `asrModelUrl` 切到 OSS HTTPS（`delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com`），
  构建**不带** `-PasrModelUrl`；dex 内不再出现 `cvm.agentai0.com`
- 内置 FunASR 离线识别运行时：sherpa-onnx + Paraformer-zh-small，arm64-v8a / armeabi-v7a / x86 / x86_64
- 签名：Android Debug 证书（非 release 自签证书），可与既有内测包同证书覆盖安装，不用先卸载
- 出包基线：提交 `bc81c30`（合并 `origin/main` 的 `76d178c`）+ 版本提交 `a9d1dae`；APK 由该提交 `assembleRelease` 产物用本机 debug keystore 重签
- **不含** VOCA-121（联想去重）修复——该条此前误记为「已修复」，2026-11-16 按包内字节更正：本试包 dex 里 `AssociationCommit` 0 命中，修复随 11-19 最终包生效，本包上仍会复现「你」+「你好」→「你你好」、`zhidao`→「指导老师」+「师专」→「指导老师师专」

## 出包门禁（2026-11-14 实测，全部通过）

| 门禁 | 结果 |
| --- | --- |
| 原生库 ABI 数 | `unzip -l … \| grep -c libsherpa-onnx-jni.so` = **4** |
| 模型源在 dex | 命中 `delivery-service-smzk` = **1**；`cvm.agentai0.com` = **0** |
| 签名证书 | `apksigner verify --print-certs` → `85:7C:E6:…:A0:ED`（Android Debug） |
| 版本元数据 | `aapt2 dump badging` → `versionCode='6'` / `versionName='1.6'` |
| 模型源可用 | `HEAD` 200 / `Content-Length: 77920048` / `Range: 0-1023` → 206 |

## 入包字节断言（最终包必做；本试包已实测不通过）

上面的门禁只证明「包是怎么签的」，不证明「某个修复在包里」——1.6 试包就是在这里翻的车。
所以最终包（以及任何被声称「含某修复」的包）出完后，先跑下面这段：把它存成 `assert_apk_fix.sh`，`bash assert_apk_fix.sh <apk>`，**exit 非 0 = 包不合规，不发**。

```bash
#!/usr/bin/env bash
# 入包字节断言：APK 里必须出现「修复项」的 dex 符号；缺任一项 → exit 1（包不合规，不发）
# 用法：bash assert_apk_fix.sh dist/ispeech-internal-v1.6.apk
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

断言的 token 与判据（VOCA-121，来源 = PR #10 的 `app/src/main/java/com/ispeech/engine/AssociationCommit.kt` 与 `IspeechImeService.kt:119/1132/1134`；`isMinifyEnabled = false`，符号名在 dex 里原样保留）：

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

1. `adb install -r ispeech-internal-v1.6.apk`，或将 APK 拷进手机点击安装（要求 Android 8.0+ / minSdk 26）。
   已装旧内测包（同 debug 证书）可直接覆盖，不需要先卸载；若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 说明证书不同，**不要卸载清数据**，先回报。
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
- **语音**：本试包已带 FunASR 运行时与默认 OSS 模型源，但语音入口修复（VOCA-166）未合入，语音键行为以真机验收（VOCA-188）结论为准
- **联想词重复末字未修复（VOCA-121）**：点候选词上屏后再点以末字开头的联想词，本包仍会写成「你你好」「指导老师师专」。原因是出包基线 `bc81c30` 合的是 `origin/main` 的 `76d178c`，而该修复只在 PR #10（head `980b496f…1aa7`，draft、未合并）里，`git ls-tree -r --name-only origin/main | grep -ci association` = 0，本包 dex 同样 0 命中（见「入包字节断言」）。
  **验收 / 复测 AC-1、AC-2 不要使用本试包**——用它只能得出「修复无效」的错误结论；统一等 11-19 窗口 W⑤ 的最终包，且先确认断言命中再装机
- **设置 → 键盘与输入 必崩（VOCA-170）**：`KeyboardViewModel.kt` 的 `init{}`（`:29`，写入在 `:35` / `:38`）先于属性声明（`:102` / `:105`）执行，
  对尚未初始化的 `MutableStateFlow` 调 `setValue` → 确定性 NPE，崩的是 IME 主进程 `com.ispeech.ime`。1.5/5 与 v1.6 试包同源，均 100% 复现（取证见 VOCA-228）。
  **这不是 v1.6 回归**，装机与验收请勿按新缺陷重复上报；最终包门禁要求 VOCA-170 先合入 main
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
git checkout a9d1dae                            # 出包提交（含 76d178c 与 v1.6 版本号）
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties   # 本机文件，不入库
./gradlew assembleRelease                       # 不带 -PasrModelUrl，走 OSS 默认源

BT=$ANDROID_HOME/build-tools/34.0.0
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-type JKS \
  --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out dist/ispeech-internal-v1.6.apk app/build/outputs/apk/release/app-release.apk
cd dist && shasum -a 256 ispeech-internal-v1.6.apk _archive/*.apk > SHA256SUMS
```

## 校验

```bash
shasum -a 256 -c SHA256SUMS
```

## 最终包出包顺序（11-19 窗口 W⑤，SRE 执行）

1. 前提：VOCA-190 / 170 / 121 / 166 四条已按顺序 squash 进 `main`；记下基线 `git log -1 --format=%H origin/main`，写进本文件「出包基线」。
2. 从该基线 `assembleRelease` + 本机 debug keystore 重签（同上 recipe，只把 `git checkout a9d1dae` 换成新基线；`versionCode` 若不再递增仍为 6，靠 sha256 区分试包与最终包）。
3. `shasum -a 256 ispeech-internal-v1.6.apk` → 更新本文件「取包方式」的期望值与 `SHA256SUMS`。
4. **跑「入包字节断言」，exit 0 才算包合规**；不命中不覆盖发布分支、不装机，回到第 1 步查合并漏了哪条。
5. 装机（`adb install -r`，同 debug 证书不用卸载、不丢词库）→ 交装机专员执行、测试工程师判定 AC-1…AC-5。

**回滚点**：包 = 本分支当前 blob `dist/ispeech-internal-v1.6.apk`（sha256 `9cddcec5a88d…`，98,416,118 字节），`git checkout release/voca-186-v1.6 -- dist/` 取回后 `adb install -r` 覆盖即可，不需要卸载；
main = 窗口起点 `fa57a07`（四条各一个 squash 提交，`git revert` 可逐条退、互不牵连）；本文件 = 本文档纠正提交（VOCA-121 口径 + 入包断言）。

