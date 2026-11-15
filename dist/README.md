# 智语 iSpeech 1.6 — 内测安装包（Android）

搜狗五笔 86 复刻原型 · Kotlin + Jetpack Compose · 五笔86 引擎 + 拼音混输 / 模糊音 + 九宫格 T9 +
简繁输出 + 颜文字 / 更多符号 + 输入统计 + csBaby 快捷短语（收藏 / 最近）+ 触屏软键盘 + AI 回复。

**本版定位**：内测分发 · Android Debug 证书签名 · 含 FunASR 离线语音运行时（4 ABI）· 默认模型源为 OSS HTTPS。
语音入口修复（VOCA-166）尚未合入，本包是**试包**：版本 / 签名 / 模型源 / 产物内容可验，语音键是否可用不在本包保证范围内；
VOCA-166 合入 main 后会在 VOCA-186 重打最终包并替换本文件。

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
- 最终包门禁 = VOCA-190（FunASR 接线）+ VOCA-170（设置页必崩）+ VOCA-121（联想去重，PR #10 @ `e8dc6f1`，**必要项·不可裁剪**）+ VOCA-166（语音入口/麦克风）四条都合入 main 后重打，覆盖发布分支 `release/voca-186-v1.6` 并同步更新本节 sha256。验收判据 = VOCA-183 交付计划 六·五 / AC-1…AC-10；缺任一条不出包。

## v1.6 变更（相对 v1.5）

- 版本号 `versionCode 6` / `versionName 1.6`，装机后可凭版本号区分「含语音」的内测包与旧包
- 默认 `asrModelUrl` 切到 OSS HTTPS（`delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com`），
  构建**不带** `-PasrModelUrl`；dex 内不再出现 `cvm.agentai0.com`
- 内置 FunASR 离线识别运行时：sherpa-onnx + Paraformer-zh-small，arm64-v8a / armeabi-v7a / x86 / x86_64
- 签名：Android Debug 证书（非 release 自签证书），可与既有内测包同证书覆盖安装，不用先卸载
- 出包基线：提交 `bc81c30`（合并 `origin/main` 的 `76d178c`）+ 版本提交 `a9d1dae`；APK 由该提交 `assembleRelease` 产物用本机 debug keystore 重签
- **修复**：多字候选上屏后再点以末字开头的联想词不再重复末字（VOCA-121）

## 出包门禁（2026-11-14 实测，全部通过）

| 门禁 | 结果 |
| --- | --- |
| 原生库 ABI 数 | `unzip -l … \| grep -c libsherpa-onnx-jni.so` = **4** |
| 模型源在 dex | 命中 `delivery-service-smzk` = **1**；`cvm.agentai0.com` = **0** |
| 签名证书 | `apksigner verify --print-certs` → `85:7C:E6:…:A0:ED`（Android Debug） |
| 版本元数据 | `aapt2 dump badging` → `versionCode='6'` / `versionName='1.6'` |
| 模型源可用 | `HEAD` 200 / `Content-Length: 77920048` / `Range: 0-1023` → 206 |

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
