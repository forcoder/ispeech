# Design

## Context

动机见 proposal.md（Why）。本设计只给「怎么做」，建立在以下已核实现状上：

- 语音入口只有「按住说话」手势语义（`SoftKeyboardView.kt:737` 按下即回调、`:765` 点击回调置空），无单击语义。
- 三处未授权分支都会 `requestMicPermission()` 自动跳设置页：系统引擎 `toggleVoiceInput`（`IspeechImeService.kt:1570-1574`）、FunASR 点按 `toggleFunAsrVoice`（`:1683-1687`）、FunASR 按住 `startFunAsrListening`（`:1777-1781`）。跳页用 `FLAG_ACTIVITY_NEW_TASK`，`startActivity` 抛 `ActivityNotFoundException` 时仅 `Log.w`（`:1874-1878`）。
- 候选条是两行结构：第 1 行 `candidate_code`（渲染 `statusText ?: buffer`），第 2 行「`candidate_items` 横向候选 + 翻页器 + 齿轮」。`CandidateView.update()` 只清 `itemContainer`，不清兄弟控件。
- 空格/回车/数字键 1-9 走 `commitTop`/`pickCandidate`（`:582-595`、`handleSpace():712-735`）——授权动作绝不可进候选列表。
- IME 窗口不接收焦点（`:379`），`shouldShowRequestPermissionRationale` 需要 Activity 上下文，IME 侧无法自行判定「永久拒绝」。
- 设置页 `MainActivity.kt:38-46` 结果回调为空实现、页面不 `finish()`，QA 实证拒绝后停在设置页登录页回不去。

约束（外部已定，本设计遵循）：UX 12 态矩阵与文案（`docs/ux/VOCA-166-mic-permission-flow.md`）、BA AC-1…AC-10（`docs/requirements/VOCA-166-acceptance.md`）。

## Goals / Non-Goals

**Goals:**
- 未授权触发语音入口后，输入法键盘与宿主前台不变，候选条给出可读提示与用户主动触发的授权入口（AC-1/2/3/6/9/10）。
- 授权入口 ≤2 步可达且仅由显式点击触发；拒绝后回场原输入框；永久拒绝给系统设置路径文案；全程不崩溃（AC-4/5/8）。
- 组合中触发语音入口不丢字（S7，`commitPending` 前置）。

**Non-Goals:**
- 不改已授权语音面板语义（S4/AC-7）；不改模型下载链路（S5/S6，只保证键盘不消失）；不改 `AsrEngine.SYSTEM` 识别接口。
- 不做单击/按住语义矩阵、74MB 下载顺序、设置页登录落点、D-2（VOCA-170）——逐项单开。

## Decisions

### D1 未授权分支去自动跳页，改为「渲染 blocked 态 + 用户触发入口」
三处未授权分支统一删除自动 `requestMicPermission()`，改为：置一个服务侧显式状态 `micBlocked`（含第一行状态文本 + 第二行动作文案），调用 `renderCandidateView()`。前台 Activity 不变。系统引擎与 FunASR 两条引擎均可判 `checkSelfPermission`，同路径同改（BA §8 要求两条引擎回归各跑一次）。

### D2 候选条第 2 行新增独立授权按钮（`itemContainer` 之外）
`candidate_view.xml` 第 2 行新增一个 `TextView`（`candidate_mic_action`），位置在 `candidate_scroll` 与 `candidate_settings` 之间、`itemContainer` 之外。`CandidateView.update()` 增加显式入参「授权动作可见 + 按钮文案」，**不由第一行文本推断**（常态 `statusText` 是用户正在打的组合码，不可被误判为授权态）。触发仅指针 `click`，不绑定空格/回车/数字键。触摸目标补 padding 到 ≥44dp 高、≥64dp 宽，与相邻控件净间距 ≥8dp；`contentDescription` 显式（TalkBack 语义）。按钮文字色用近黑 `#1A1A1A`（`:candidate_view` 底色 `#F2F3F5` 上 15.68:1），禁用首选候选橙 `#FF6A00`（2.59:1 不达 AA）。

### D3 状态文本用「合并文案」，AC-5 不依赖跨 Activity 标记
IME 侧无法区分「首次未授权 / 拒绝一次 / 永久拒绝」。采用 UX §10 U2 R1：候选条第 1 行使用一条**对三种情况都成立**的合并文案，同时给出「去授权」动作与系统设置路径兜底，使得 AC-5（给路径文案 + 不静默跳页）在无 `SettingsStore` 标记时即可判定通过。按钮统一为「去授权」。S1/S2 点击后系统弹框；S3（`USER_FIXED`）点击后系统不弹框，文案的「若系统不再弹出请到系统设置…」即为兜底指引，用户在设置页也有 E3 常驻入口。评审通过后，若架构师补了「一次性/永久拒绝」标记（R2 精化），再把 S3 按钮升级为「去设置」并直跳应用信息页——这是精化，不冲突、不阻塞本批。

### D4 `commitPending()` 前置（S7，防丢字）
`startFunAsrListening` 在未授权分支 return 之前补 `commitPending()`，与 `:1584`/`:1694`/`:1790` 的其它语音入口对齐。`toggleVoiceInput`、`toggleFunAsrVoice` 的未授权分支同样前置（组合码缓冲非空时先上屏，再进提示态）。

### D5 跳页异常给界面错误态（X1）
`requestMicPermission()`（改名为用户触发语义）的 `catch(ActivityNotFoundException)` 不再只 `Log.w`：设置状态文本为 `voice_permission_open_failed`（打不开授权页面，给系统设置路径），按钮保留允许再试一次，并加一条结构化日志。不自动重试、不循环弹。

### D6 回场闭环（AC-4 体验核心）
设置页 `MainActivity` 在**由 IME 发起的**授权流程（`REQUEST_MIC_EXTRA=true`）结束时 `finish()`，让前台回到宿主输入框。授权结果回调只做一件事：**无论允许/拒绝，流程结束即 `finish()` 回场**。**不在 Activity 侧用 `shouldShowRequestPermissionRationale` 判定「永久拒绝」**——该方法在「从未申请过」与「永久拒绝（不再询问）」两种情况下都返回 false，无法可靠区分，硬判定会把首次申请也误标成永久拒绝；这正是候选条采用合并文案（D3）的原因。永久拒绝的全路径指引统一由候选条合并文案承担。键盘是否自动重显由宿主决定，回场后候选条 S7 采用「按住麦克风说话」文案，若平台不回显键盘则在文案尾带「点一下输入框即可继续」兜底（UX S8），真机行为由 QA 补测确认（§8-3）。

### D7 文案（zh/en 同步）
新增/修订：`voice_permission_denied_forever`（合并路径文案，允许换行、不截断）、`voice_action_grant`（去授权 / Grant）、`voice_permission_open_failed`（打不开授权页）、`voice_permission_granted`（权限已开启，按住说话）、`voice_model_ready`（改「点击」为「按住」）。长路径文案**不接 `shortText()`**（`:2234`）。

### D8 E2/E3 副入口
E2：`SettingsPanel.buildChoiceRow` 新增常驻一行「麦克风权限：未开启 › / 已开启」（复用现有控件，读实时权限）。E3：设置页 `AsrSettingsScreen` 状态行 + 「开启麦克风」入口（唯一键盘/TalkBack 焦点可达路径，满足 WCAG 2.1.1）。E1(1 步)/E2(2 步) 满足 AC-6，E3 只是补充。

## Risks / Trade-offs

- [合并文案在 S1 首次用户看偏长、带了设置路径] → 文本分两短句（「请点击去授权」+「若不再弹请到系统设置…」），短态 ≤12 CJK 优先，路径句允许换行；首次用户面对的是可读指引而非跳页，符合 AC-1。
- [去掉跳页后「点了没反应」] → 按钮按下有 `haptic()` + 现有按下底色反馈；服务侧保证任何按键立即清除提示态并恢复正常打字（AC-9），杜绝「像坏了」。
- [S3 用户点「去授权」后系统不弹框] → 合并文案已明确「若系统不再弹出请到系统设置…」，且跳设置页后 MainActivity 判定不弹框即就地给全路径；非静默。
- [模拟器绿 ≠ 真机绿（EMUI 可能拦截 IME 跳页，X2）] → 真机 `FMR0224913042917` 必跑 AC-1…AC-8；X2 未跑出来不算覆盖。
- [授权动作误成候选导致空格踢出输入场景] → D2 硬约束：按钮在 `itemContainer` 之外、不绑定键盘键；回归加「提示挂着按空格/数字键必须不跳页」反例。

## Open Questions

- [U1] S3「去设置」是跳系统应用信息页（`ACTION_APPLICATION_DETAILS_SETTINGS`，推荐）还是不跳只展开文案：**需架构师定契约**，两案都不动 S1/S2 主路径，先按「合并文案 + 去授权」落，`去设置` 作为 R2 精化后补。
- [U2] 「一次性/永久拒绝」跨 Activity 标记的字段与写入方：**归架构师**；AC-5 在合并文案下已可判定，标记到齐只提升 S3 按钮精度。
- [U3] 授权结束后键盘是否自动重显（宿主相关）：真机 QA 补测确定，文案带「点一下输入框即可继续」兜底，不影响实现。