# 智语 iSpeech

智语是一款基于五笔输入法的 Android 输入法（IME），集成 csBaby 后端实现快捷短语查找、快速应用与云端同步。

**v1.5 主题：在 v1.4 全功能键盘的基础上补齐搜狗五笔的「输入方案 + 切换 + 统计 + 语音」——
拼音混输 / 模糊音、简繁输出、九宫格 T9、颜文字、输入统计、语音输入，并加上 csBaby 快捷短语的收藏与最近使用。**

## 功能特性

### 键盘本体

- **多页布局**：字母页（中 / 英两套）、数字页、符号页 8 页（常用 2 + 数学 / 箭头 / 货币 / 单位 / 希腊 / 线框）、
  表情页 8 类（每类一屏 emoji）、颜文字 7 类（103 条）、九宫格 T9 2 页（数字 + 标点）
- **工具栏（9 键）**：输入法切换 · 中/英 · 符号 · 表情 · 快捷短语 · 剪贴板 · AI · **语音** · 设置
  （键序 `⌨ 中/英 符 ☺ 短语 剪贴 AI 语音 ⚙`；字母页底行另有「中/英」键；窄屏自动横滑，单键 ≥40dp）
- **页码条**：多页布局显示「符号 1/2 点此翻页」，点按即可翻页
- **九宫格 T9**：数字页底行「9键」进入；`1` 标点页、`*` 符号页、`#` 更多符号、`☺` 颜文字直达页
- **快捷切换系统输入法**（v1.5 新增）：工具栏第 1 键弹出输入法列表（当前项置顶），
  点条目 `setInputMethod` 直接切换，失败回落系统选择器（面板另有「系统选择器」按钮）
- **按键反馈**：按下放大预览气泡、长按出候选气泡（如数字键长按出 ①⑴）、长按退格连删、震动反馈
- **Shift 单击切一次大写，双击锁定 Caps Lock**；键面随大写状态切换
- **键盘高度三档**（紧凑 46dp / 标准 52dp / 加高 58dp），设置页改动实时生效

### 输入逻辑

- **五笔 86 输入**：内置 `wubi86.dict` 词库，支持单字 / 词组 / 简码，`z` 通配符
- **候选上屏**：空格 / 回车上首选，数字键 1-9 直选，`-` `=`（或候选条翻页器）翻页
- **中英切换**：英文态字母直接上屏，Shift 出大写；中文态 Shift+字母直接上屏大写英文
- **标点与全角**：中文标点 / 英文标点切换、全角 / 半角切换，引号成对输出 `“ ”` `‘ ’`
- **编辑效率**：光标左移 / 右移、Tab、全选 / 复制 / 粘贴、撤销上一次上屏、清空组合、收起键盘
- **词语联想**：上屏后按设置给出联想候选（词库词组 + 快捷短语 + 用户词），候选条显示「联想」
- **用户词频学习**：选过的词记入 `filesDir/user_dict.txt`（权重 `min(400, 120 + 10×次数)`），
  引擎侧统一加 10 万权重保证学到的词排在系统词前面；设置页可查看 / 删除 / 清空
- **自造词**（v1.5 新增）：候选条**长按** → 「记住这个词」直接写入用户词库（显式动作，不受「词频学习」开关限制）
- **用户词库分模式**（v1.5 新增）：`user_dict.txt` 第 4 列记 `wb`（五笔）/ `cn`（拼音），编码空间互不污染；
  拼音态因此也能自造词与词频学习（对齐搜狗 `wb_dict_user` / `cn_dict_user`）
- **词库导入 / 导出**（v1.5 新增）：词典页「用户词库导入 / 导出」，支持 `code<TAB>词条[<TAB>次数][<TAB>wb|cn]`
  与「编码 词条」两段式，走系统文件选择器（SAF），不申请存储权限
- **分类词库（细胞词库）**（v1.5 新增）：词典页「添加词库」安装本地文本词库到 `filesDir/cell_dicts/`，
  逐库启用 / 停用 / 删除（对齐搜狗 `InitializeWithMode:CateDictArray:` + `pfnAddDict` / `pfnDelDict`）；
  正文格式与用户词库一致，可加 `# category: 分类名` 头；启用后并入候选，
  权重 8 万——排在用户学到的词之后、系统词库之前，五笔态取 wb 码、拼音态取 cn 码
- **拼音混输**（v1.5 新增）：`assets/pinyin86.dict`（71097 行：单字 15511 行 / 词组 55575 行，其中 26413 条
  由 jieba 高频词注音补全）+ `PinyinEngine`，全拼 / 简拼 / 前缀三层匹配，
  排在五笔之后做兜底（`nerf` 这类打不出五笔码的输入）；设置页开关
- **模糊音**（v1.5 新增）：z=zh、c=ch、s=sh、n=l、an=ang、en=eng、in=ing；设置页开关
- **简繁输出**（v1.5 新增）：`HanConversion` + `han_s2t.txt` / `han_t2s.txt`（字符级），设置页「繁体输出」开关
- **九宫格 T9**（v1.5 新增）：2-9 数字进独立缓冲 → 展开字母组合 → 查九宫格专用拼音引擎（与「拼音混输」开关解耦），
  空格上屏首选、无候选原样上屏数字串（电话号 / 验证码场景）
- **字形过滤**（v1.5 新增）：候选展示前逐码点 `hasGlyph`，字体缺字形的生僻字（如 `wqiy` 的 `𠉎`）不再显示成豆腐块
- **输入统计**（v1.5 新增）：`filesDir/input_stats.txt`，设置页「输入统计」看今日 / 累计字数、
  上屏次数、候选命中率与 Top 词（最多保留 400 天）
- **语音输入**（v1.5 新增）：工具栏语音键（`ic_voice`）→ **按住说话 → 语音面板（实时文字 + 音量波形）→ 松手自动上屏**，
  上滑进取消区丢弃；两种引擎同形态（FunASR 离线 / 系统 `SpeechRecognizer`）；未授权时拉起设置页弹系统授权框，
  取消即销毁识别器释放麦克风
- **ASR 配置**（v1.5 新增）：设置页「ASR 模型配置」或输入法内置 ⌨ 面板可选引擎（系统 / 指定识别服务组件）、
  识别语言（普通话 / 繁中 / 粤语 / English / 日本語）、实时部分结果、优先离线、模型名透传

### 快捷短语与 csBaby

- **快捷短语面板**（工具栏「短语」）：分类胶囊（全部 / 内置 / 远端 / 用户 / 快速应用 / 短语）+ 关键词筛选 +
  点条目整段上屏；数据来自内置 `quick_phrases.txt`、csBaby 远端同步、用户自定义规则三处
- **收藏与最近**（v1.5 新增）：面板顶部 ★ 收藏 / 最近胶囊（`filesDir/phrase_usage.txt`），
  条目右侧星标一键收藏，使用次数越多越靠前
- **快捷字母**（自定义简写）：管理页新增 / 编辑短语时可指定 2–4 位小写字母（如 `ycsf` → 游泳池换水要收费的），
  五笔态敲这串字母，该短语**直接排在候选第 1 位**，空格整段上屏；面板行内以角标显示快捷字母，保存后无需重启输入法
- **远端短语可管**：csBaby 拉取的规则同样出现在列表里（角标「远端」），可查看内容并**给它设快捷字母**——
  改动以「覆盖」形式写进本地用户规则（权重 +1000，排序必胜），随时「恢复默认」；远端文件本身不被修改，下次同步不会冲掉
- **快速应用**：打字命中触发码时，候选条用「应用 / 短语」标签提示，选中后把详细内容整段上屏
- **候选档位**：应用 < 短语 < 词库单字 < 词库词组 < 拼音；`wqiy` 首选是「你」、`trnt` 首选是「我」，
  词组（带「词」标签）用数字键选，符合五笔肌肉记忆
- **剪贴板面板**（工具栏「剪贴）：读取系统剪贴板并入历史（≤50 条）、点条目上屏、单条删除、清空、撤销上屏
- **云端同步**：对接 csBaby 后端（`http://api.agentai0.com`），支持登录、注册、令牌刷新、关键词规则同步
- **AI 回复**：软键盘「AI」键调用后端 `api/ai/generate`（优先命中关键词规则，未命中走已配置的 LLM），
  结果作为候选一键上屏并替换原文

### 设置界面（全图形化，无需命令行）

- **键盘与输入**：键盘高度、每页候选数（5 / 7 / 9）、震动反馈、中文标点、全角输出、词语联想、词频学习、用户词库管理
- **引擎与词典**：词典统计与开关（启用快捷短语 / 远程规则 / 单字母防误触）
- **快捷短语管理**：新增 / 编辑 / 删除规则
- **csBaby 账号与同步**：登录、注册、立即同步
- **主题外观**：浅色 / 深色 / 跟随系统

## 项目结构

```
app/src/main/java/com/ispeech/
├── IspeechApp.kt                  # Application 入口
├── data/                          # 数据层
│   ├── CsBabyClient.kt            # csBaby API 客户端
│   ├── CsBabyAuthStore.kt         # 登录凭证本地存储
│   ├── SyncRepository.kt          # 远端规则同步仓库
│   ├── RemoteKeywordRule.kt       # 远端规则模型
│   ├── UserDictStore.kt           # 用户词频 / 自造词存储
│   ├── CellDictStore.kt           # 分类词库（细胞词库）安装 / 启停 / 索引（v1.5）
│   ├── ClipboardHistory.kt        # 剪贴板历史
│   ├── Punctuation.kt             # 中英标点映射 + 全角转换
│   ├── PhraseUsageStore.kt        # 短语收藏 / 最近 / 使用计数（v1.5）
│   ├── PhraseSearch.kt            # 短语搜索 / 首字母匹配（v1.5）
│   ├── HanConversion.kt           # 简繁字符映射（v1.5）
│   ├── InputStats.kt              # 输入统计（v1.5）
│   └── remote/                    # Retrofit 接口与 DTO
├── engine/                        # 输入引擎
│   ├── WubiEngine.kt              # 五笔引擎（含词语联想索引）
│   ├── QuickPhraseEngine.kt       # 快捷短语引擎（含短语联想）
│   ├── UserDictEngine.kt          # 用户词库引擎
│   ├── CellDictEngine.kt          # 分类词库（细胞词库）引擎（v1.5）
│   ├── PinyinEngine.kt            # 拼音引擎：全拼 / 简拼 / 前缀 / 模糊音（v1.5）
│   ├── PinyinDictLoader.kt        # pinyin86.dict 加载与解析（v1.5）
│   ├── CompositeEngine.kt         # 组合引擎（候选合并 + 联想合并）
│   ├── QuickLocator.kt            # 规则 / 词库文件定位与组装
│   └── ...                        # LookupEngine / LookupCandidate / QuickEntry 等
├── input/                         # 输入法本体
│   ├── IspeechImeService.kt       # IME 服务（状态机 + 面板 + AI）
│   ├── SoftKeyboardView.kt        # 多页软键盘视图
│   ├── CandidateView.kt           # 候选条（来源标签 + 翻页器）
│   ├── QuickPhrasePanel.kt        # csBaby 快捷短语面板
│   ├── ClipboardPanel.kt          # 剪贴板面板
│   ├── InputMethodPanel.kt        # 切换系统输入法面板（v1.5）
│   ├── VoiceInputController.kt    # 语音输入（系统 SpeechRecognizer 封装，v1.5）
│   ├── SettingsPanel.kt           # 输入法内置设置面板（含 ASR 分项，v1.5）
│   └── keyboard/                  # 键位定义与布局数据
│       ├── KeyDef.kt              # KeyAction / KeyStyle / KeyDef / KeyboardPage
│       ├── KeyboardLayouts.kt     # 字母 / 数字 / 符号 / 表情布局
│       ├── EmojiData.kt           # 8 类 emoji 数据
│       ├── T9Layouts.kt           # 九宫格 T9 键位 + 数字串→字母串（v1.5）
│       └── KaomojiData.kt         # 颜文字 7 类 + 更多符号 6 类（v1.5）
├── di/AppContainer.kt             # 全局依赖容器（引擎、客户端、用户词库、剪贴板、统计）
└── settings/                      # 设置界面 (Jetpack Compose)
    ├── MainActivity.kt / HomeScreen.kt / IspeechNavGraph.kt
    ├── KeyboardScreen.kt          # 键盘与输入设置
    ├── StatsScreen.kt / stats/StatsViewModel.kt  # 输入统计（v1.5）
    ├── asr/AsrSettingsScreen.kt / AsrSettingsViewModel.kt  # ASR 模型配置（v1.5）
    ├── DictionaryScreen.kt / QuickPhrasesScreen.kt
    ├── SyncScreen.kt / CsBabyLoginScreen.kt / ThemeScreen.kt / AboutScreen.kt
    ├── store/AsrSettings.kt        # 语音识别设置模型（引擎 / 语言 / 参数，v1.5）
    └── store/SettingsStore.kt     # DataStore 偏好项（含键盘设置）
```

## 构建方法

1. 用 **Android Studio** 打开本目录
2. 等待 Gradle 同步完成（需 Android SDK 34+）
3. 连接真机或模拟器，点击 Run

命令行构建（本机已装 JDK 17 + Android SDK 时；JDK 17 现已装在本机，见下文）：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # Temurin 17.0.20.1
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 检测到 keystore/keystore.properties 时自动签名
```

### 远端构建机（本机缺 Android SDK 时）

开发机（本项目日常使用的那台 Mac）**已装 JDK 17（Temurin 17.0.20.1，2026-10-18 安装），但没有 Android SDK
（`local.properties` 指向 `/opt/homebrew/share/android-commandlinetools`，本机无 brew）**，
所以编译 / 装机 / 冒烟测试仍放在远端构建机上做：

```bash
./ispeech-remote.sh sync     # 本地源码 → 远端（排除 build/ .gradle/）
./ispeech-remote.sh build    # 远端 assembleDebug
./ispeech-remote.sh install  # 装到远端模拟器
./ispeech-remote.sh test     # 选中智语输入法 + 截图
./ispeech-remote.sh pull     # 取回 debug APK 到 dist/
./ispeech-remote.sh sh       # 远端交互 shell
```

远端：`cookie@43.136.44.72:10010`，JDK 17 + Android SDK + `emulator-5554`（Android 14）。

> 本轮（v1.5 四项修复 + 图标复刻）的编译与真机验证证据、可复现命令、以及踩到的坑
> （重装后必须先 `ime enable` 再 `ime set`；`ImageView` 没有 `minWidth/minHeight`）
> 都记在 `docs/v1.5-verify-log.md`。
脚本里的远端环境变量必须写成 `\$JAVA_HOME` 转义形式，否则本地 shell 会把 `$PATH` 展开进 ssh 命令导致失败。

## 安装测试（APK）

1. **安装**：`adb install -r dist/ispeech-release-v1.5.apk`，或点击 APK 安装（要求 Android 8.0+；v1.5 已签名，versionCode 5）
2. **启用**：系统设置 → 语言与输入 → 管理输入法 → 启用「智语」
3. **切换**：在输入框通过输入法切换器（长按空格 / 通知栏）选择「智语」
4. **自测**：`wqiy`→你、`trnt`→我；`hi`→「你好问候」空格上屏详细内容；退格删码、Esc 清空组合
5. **键盘自测**：点工具栏「符 / ☺」切换符号与表情页（符号页现在有 8 页，翻到「数学 / 箭头 / …」）；
   数字页长按 `1` 出 `①⑴`；Shift 单击 / 双击看大小写；输入若干字后观察「联想」候选
6. **九宫格自测**：数字页底行「9键」→ 九宫格；按 `6` `4` → 候选「你 / 年 / 米…」，空格上屏；
   `#` 直达「数学 3/8」、`☺` 直达「颜·开心 9/15」、`1` 翻标点页
7. **切换输入法自测**：工具栏第 1 键「⌨输入法」→ 列表显示系统里所有已启用输入法（当前项带「当前」标签）→
   点另一项即切换；失败会弹系统选择器
8. **拼音 / 简繁自测**：设置页打开「拼音混输」「模糊音」「繁体输出」→ 键盘直接打 `nerf`（拼音兜底）、
   `zongguo`（模糊音 z=zh）、九宫格 `983` → 上屏「說」（繁体）
9. **统计自测**：设置页 → 输入统计，看今日 / 累计字数、上屏次数、候选命中率、Top 词
10. **面板自测**：工具栏「短语」打开 csBaby 快捷短语面板（分类胶囊 + ★收藏 / 最近 + 硬件键盘打字筛选）；
    「剪贴」打开剪贴板历史
11. **AI 回复自测**：输入框写一句话 → 点「AI」键 → 候选条出现「AI回复」→ 空格 / 点击候选上屏（自动替换原文），需先登录 csBaby
12. **语音输入自测**：**按住**工具栏语音键（未授权会拉起设置页弹麦克风授权，先授予）→ 语音面板出现「正在聆听」+ 波形，
    朗读一句后**松手即上屏**；按住时上滑到取消区再松手 = 丢弃（面板提示「松开取消」）
13. **设置**：候选条右侧齿轮、键盘工具栏齿轮，或桌面「智语」图标

## v1.5 真机实测记录（远端构建机 emulator-5554 / Android 14）

| 用例 | 结果 |
| --- | --- |
| 五笔基本输入 `wqiy` + 空格 | 候选「1 你 2 仰泳(词)」→ 空格上屏「你」；`filesDir/user_dict.txt` 记 `wqiy 你 2` |
| 九宫格 `64` + 空格 | 候选「年 / 你 / 米 / 呢」→ 上屏「年」 |
| 九宫格 `983` + 空格（繁体输出开） | 候选「说 / 月 / 约 / 许多」→ 上屏「說」 |
| T9 跳页 `#` / `☺` | 直达「数学 3/8」（更多符号首页）/「颜·开心 9/15」（颜文字首页） |
| 工具栏「⌨输入法」 | 面板列出 iSpeech（当前）+ Google Voice Typing；点后者切走成功（`settings get secure default_input_method` 已变） |
| 候选豆腐块过滤 | `wqiy` 候选不再出现 `𠉎`（非 BMP 无字形），只剩「你 / 仰泳(词)」 |
| 拼音混输开关 A/B | 关闭后同一 `ze` 的「拼」标签候选消失（只剩五笔），重新打开后恢复 → 设置改动当场生效，无需重启输入法进程 |
| 输入统计 | 今日字数 4 / 上屏 7 次 / 命中率 30.4%（查询 23 次），数值与本次实测操作一致 |
| 语音输入 | 真机 Huawei：`phone8.png` 状态条「语音识别中...」+ logcat 绑定 `com.huawei.vassistant` 识别服务；模拟器复验：麦克风指示 + 5s 超时优雅收尾（`docs/v1.5-verify-log.md` ① / §5）；**真实转写准确率未验证**（未朗读话筒） |
| release 打包 | `assembleRelease` 成功，`aapt2 dump badging` = `versionCode 5 / versionName 1.5`，`apksigner verify` 签名有效 |

## csBaby 后端对接

- **登录/注册**：`http://api.agentai0.com/api/auth/login` / `register`
- **规则同步**：`http://api.agentai0.com/api/sync/all` / `sync/changes`
- **认证**：Bearer Token，存储于 DataStore `csbaby_auth`
- 明文 HTTP 已在 `network_security_config.xml` 中为 `api.agentai0.com` 放开
- **AI 回复**：`POST http://api.agentai0.com/api/ai/generate`（body `{message, context}`），
  后端先做关键词规则匹配，未命中时调用该账号在 `model_configs` 配置的 LLM

## 已知限制

- **无双拼 / 手写 / 五笔 98 / 云词库**（缺对应码表或识别能力）；皮肤仅主题色
- **语音输入**：两种引擎统一为搜狗式「按住说话 + 语音面板 + 松手上屏」；面板放在键盘**上方**（不替换键盘区域），
  因为被按住的语音键一旦 GONE，系统会补发 `ACTION_CANCEL`，`ACTION_UP` 丢失就会「松手不上屏」。详见 `docs/voice-input.md`
- **拼音 / 模糊音 / 九宫格的范围**：拼音是「五笔查不到时的兜底档位」，不替代五笔；模糊音只做 7 组常见归一化
- **九宫格没有音节切分**：靠字母组合枚举（单次上限 192 组、数字串上限 9 位），>6 位数字时候选会变少
- **简繁是字符级转换**：不做词汇消歧（「后台」不会变成「後臺」）
- **输入统计不含 AI 回复上屏**（只统计五笔 / 拼音 / 标点 / 数字等常规上屏）；语音上屏按候选档计入字数
- 用户自造词目前只做「词频学习」；长文本请用快捷短语规则管理
- 快捷短语面板的文本筛选依赖硬件键盘（软键盘在面板打开时让位给列表）
- 云端同步与 AI 回复需先在设置页登录 csBaby
- 首次使用需手动启用输入法
- **本机（这台 Mac）没有 Android SDK**（JDK 17 已装）：编译、装机、冒烟测试仍在远端构建机执行（见上文）；
  本仓库里的 APK 与实测结论都产自远端环境

## 文档

- `docs/sogou-feature-matrix.md` — 搜狗五笔功能对照表（已实现 / 差异 / Roadmap）
- `docs/t9-and-symbols.md` — 九宫格 T9、颜文字 / 更多符号、输入法切换（键位设计与服务侧接线）
- `docs/stats-and-hanconversion.md` — 输入统计与简繁转换（数据来源、许可、接线）
- `docs/quick-phrase-enhancement.md` — 快捷短语收藏 / 最近使用
- `docs/voice-input.md` — 语音输入（搜狗对照 / 复刻义务清单 / 接线与已知差异）

## 校验脚本

全部为纯 Python3 静态检查（无第三方依赖、不联网、不编译），改动后本地直接跑：

```bash
for s in static input_logic t9 stats phrase_usage pinyin crosscut quick_trigger; do
  python3 tools/verify/check_$s.py
done
```

当前状态（v1.5）：`check_static 5/5`、`check_input_logic 40/40`、`check_t9 87/87(+1 known warn)`、
`check_stats 53/53`、`check_phrase_usage 31/31`、`check_pinyin 66/66`、`check_crosscut 141/141`、
`check_quick_trigger 29/29(+1 known warn)`。**全部通过**；分类词库（细胞词库）第 10 节 24 项全绿，
候选条字重（VOCA-56「候选词不加粗」）第 11 节 3 项全绿。
另外 `tools/verify/check_crosscut.py` 专门做「接线体检」（枚举 / 资源 / 服务分支 / 设置闭环是否一致）；
`tools/verify/check_quick_trigger.py` 校验快捷短语「简写（触发码）」规则 —— 尤其防止**单字母简写误用
prefix 模式导致静默失效**（见 `docs/quick-phrase-enhancement.md` 的「简写」一节）。

## 品牌设计

- Logo：`docs/logo.svg`（512×512，渐变蓝紫对话气泡）

## 开发者工具

本项目的「复刻搜狗」不是靠猜，而是靠**已发布制品的证据**：搜狗五笔 macOS 版
（`/Library/Input Methods/SogouWBInput.app`）被反汇编 + 词库解码后，码表与字频才落到
`app/src/main/assets/wubi86.dict` 里。

- **REA** — [morluto/rea](https://github.com/morluto/rea)：MCP 逆向工程**服务**（`npx rea-agents setup`，npm 包 `rea-agents`，
  官网 rea.tools），配套上游技能 `reverse-engineer-anything`。用途：用 shipped artifact / 运行时证据解释「已发布软件的这个功能是怎么实现的」。
- **reverse-skill** — [zhaoxuya520/reverse-skill](https://github.com/zhaoxuya520/reverse-skill)：逆向与安全研究的**技能路由包**
  （`skills/MASTER-ROUTING.md`、`skills/routing.md`），负责「挑工具 → 搭环境 → 记经验」。
- **PentAGI** — [vxcontrol/pentagi](https://github.com/vxcontrol/pentagi)：自托管的多智能体**渗透测试平台**
  （Docker + Web UI + GraphQL API，许可为上游 EULA）。注意：本项目**没有部署也没有运行 PentAGI**，
  只是借鉴它「多智能体分工 + 证据留痕」的编排思路。要做安全体检请先按上游文档部署。

**复刻任务统一入口**：`skills/replicate-software/SKILL.md`（复刻三件套路由器）——
任何「复刻 / 克隆 / 对齐某产品」的活都按「REA 取证据 → reverse-skill 选工具 → PentAGI 编排与验收」执行，
产物是行为矩阵 + 复刻义务清单 + 真机实测记录。安装：`sh skills/install-skills.sh`。

> 已安装到 `~/.codex/skills/` 的复刻相关技能：`rea`、`reverse-engineer-anything`、`reverse-skill`、`pentagi`、
> `replicate-software`（三件套路由器）。

### 词库与排序的证据链

| 事实 | 证据 |
| --- | --- |
| `wubi86.dict` 的 weight 是**搜狗字频**，不是自造 | 搜狗 `wubi.dic` 载荷 → `_G_HP_HAN_ITEM_CJK[val*4]` → u16 码位 + u16 频率；与 rime/rime-wubi 合并 |
| 词库词组权重高于常用单字 | `wqiy`：仰泳 57816 > 你 22976；`trnt`：选择性 57028 > 我 35620 |
| 所以词组必须**单独占一档**，排在单字之后 | 否则 `trnt` 首选会从「我」变成「选择性」；回归测试见 `tools/verify/check_input_logic.py` 的 `check_candidate_rank_real_dict()` |
| 候选档位：应用 < 短语 < 词库单字 < 词库词组 | `LookupCandidate.kt` 的 `LookupKind.order`，真机截图验证见 `docs/` |
| `pinyin86.dict` 的词频来自真实上游三源（MIT，commit + sha256 固定） | `tools/pinyin/build_pinyin_dict.py` 头部逐文件记录上游与 sha256，构建校验失败即退出 |
| `phrase-pinyin-data` 漏收的高频词（`jiudian` 的「酒店」）用注音补全，不是手写拼音 | jieba 词频 × pinyin-data 单字读音合成；多音字按语料证据定音（如 明 = ming/meng，语料只有 ming），佐证多个读音即跳过；回归见 `tools/verify/check_pinyin.py` 的「补全词」4 条 |
| 简繁映射来自 OpenCC（Apache-2.0）并逐条记录来源 | `tools/gen/build_han_assets.py`、`docs/stats-and-hanconversion.md` 的上游 sha256 表 |
| 颜文字 / 更多符号是数据表不是拍脑袋 | `KaomojiData.kt`，`tools/verify/check_t9.py` 断言分类数与全局去重 |

