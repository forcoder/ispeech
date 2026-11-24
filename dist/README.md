# 智语 iSpeech 1.5 — 测试安装包（Android）

搜狗五笔 86 复刻原型 · Kotlin + Jetpack Compose · 五笔86 引擎 + 拼音混输 / 模糊音 + 九宫格 T9 +
简繁输出 + 颜文字 / 更多符号 + 输入统计 + csBaby 快捷短语（收藏 / 最近）+ 触屏软键盘 + AI 回复。

## 本版文件（v1.5）

| 文件 | 说明 |
| --- | --- |
| `ispeech-release-v1.5.apk` | **release（本次交付）**，versionCode 5 / versionName 1.5，本工程自签测试证书（CN=iSpeech Prototype） |
| `ispeech-debug.apk` | debug（抓日志：`adb logcat -s IspeechIme`），与 release 同一份源码 |
| `ispeech-release-v1.3.apk` / `ispeech-release-v1.0.apk` | 历史版本，保留用于回归对比 |
| `SHA256SUMS` | 完整性校验，覆盖本版 + `debug` + `_archive/` 全部历史包（生成物，见「校验」节） |

## v1.5 新增（相对 v1.4）

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

1. `adb install -r ispeech-release-v1.5.apk`，或将 APK 拷进手机点击安装（要求 Android 8.0+ / minSdk 26）。
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

- 无双拼 / 手写 / 语音 / 五笔 98 / 云词库；皮肤仅主题色
- 九宫格无音节切分（组合枚举上限 192 组、数字串 ≤9 位），>6 位数字时候选变少
- 简繁为字符级转换，不做词汇消歧
- 输入统计不含 AI 回复上屏
- 云端同步与 AI 回复需先在设置页登录 csBaby（`http://api.agentai0.com`）

## 构建与实测环境

本工程开发机已装 JDK 17（Temurin 17.0.20.1）但没有 Android SDK，编译与真机冒烟都在远端构建机（`cookie@43.136.44.72:10010`，
JDK 17 + Android SDK 34 + emulator-5554 / Android 14）执行，命令见仓库根 `README.md` 的
「远端构建机」与「v1.5 真机实测记录」两节。

打包命令：

```bash
./ispeech-remote.sh sync
ssh -p 10010 cookie@43.136.44.72 'cd ~/workspace/iSpeech && export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools && export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/usr/bin:/bin && ./gradlew assembleRelease'
scp -P 10010 cookie@43.136.44.72:~/workspace/iSpeech/app/build/outputs/apk/release/app-release.apk dist/ispeech-release-v1.5.apk
python3 tools/verify/check_dist_manifest.py --regen   # 整份按当前字节重写；禁止 `>> SHA256SUMS` 追加
```

## 校验

**问「我手上这包是不是仓库那包」，只用这一条命令：**

```bash
cd dist && shasum -a 256 -c SHA256SUMS
```

`SHA256SUMS` 是**生成物**，口径与门禁见 `docs/adr/VOCA-400-dist-checksum-manifest.md`（ADR-0003）：

- 唯一写入路径是 `python3 tools/verify/check_dist_manifest.py --regen`，不要手改、不要 `>>` 追加
  （追加会让同一路径两行并存，`shasum -c` 只认后一行）；
- 它同时校验「记的数 == 实际字节」与「`dist/` 下每个**该入册**的 APK 都有记录」——后者是 main 上曾经
  静默漏掉 `_archive/` 三个历史包的那半个漏洞；
- 「该入册」= git 里已跟踪 + 会被 `git add -A` 扫进来的，因此上面那条 `scp` 到 `dist/` 的新包
  立刻要求一行记录；但 **`.gitignore` 排除的 `dist/ispeech-debug-*.apk` 不在其中**：
  `tools/deploy/deploy.sh` 不带 `--release` 打出的默认包不入库、也不入册，
  清单答不了「手机上那个 debug 包是谁」——自查用出包日志 `dist/deploy-<ts>.log` 里的 `sha256=` 行，
  加设备侧 `adb shell dumpsys package com.ispeech.ime`；
- 已接入 `tools/deploy/deploy.sh` 出包门禁（③ 硬门禁 + ⑤ 归档后 `--regen`），**不受 `--skip-verify` 管辖**；
- 清单只能证明**仓库字节**。装机时如果在仓库外重签过，那一包不在清单覆盖范围内，
  签名 / 版本号策略见 VOCA-159。
