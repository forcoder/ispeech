# 验收降级规则：真机设备不可用时怎么收口（VOCA-451）

> 一句话：**设备不在线时，单 issue 的「用户看到什么」降到人眼自述，其余证据一层不许省；真机截图统一挪到合并窗口补。**
> 本文件是本规则的**唯一出处**。`AGENTS.md` 如需收录，只允许保留一行指向本文件的指针，不再复写条文。

## 0. 状态与生效

| 项 | 值 |
|---|---|
| 起草 | 项目经理（VOCA-451，2026-11-27） |
| 待裁决 | 人类（qiaozhou）一句话：**认 / 不认** 下方 §2 的两个默认值 |
| 生效 | 人类回「认」即生效，不等文档二次修订；最迟由 2026-11-28T02:00Z 兜底复查推动 |
| 出处 | `docs/device-acceptance-degradation.md`（本条唯一出处，可 `grep`） |
| 不适用范围 | 功能正确性（上屏、同步、下载、状态机）——功能必须另有可执行证据，本规则不豁免功能验证 |

为什么需要人类点头：§2 放宽的是「什么证据算数」，属《人类闸门清单》第三类（验收标准定稿），agent 不代批。

## 1. 四级证据

| 级 | 证据形态 | 能证明 | 能否单独收口 |
|---|---|---|---|
| **L0** | 出包字节码：`unzip` 出 `classes*.dex` + `dexdump -d` 看符号引用 | 「进包的是什么」，即代码/资源确实在包里 | 不能。只证明实现意图，不证明渲染结果 |
| **L1** | 项目机检门禁对应小节（`tools/verify/check_crosscut.py`） | 口径被长期锁死，属**常驻回归项** | 不能单独收口，但**任何降级都必须先有 L1 PASS** |
| **L2** | 模拟器渲染截图 | 仅当 AVD 确有该字形时作数 | **本项目不成立**，见 §3.3 |
| **L3** | 真机观感：人眼自述（一句）或真机截图 | 「用户看到什么」 | 人眼自述可作单 issue 证据；截图是归档件，不是唯一合法形态 |

## 2. 降级默认值（待人类一句话确认）

1. **N = 24 小时**。「设备离线」从下列两者中**较早**的时刻起算：① 最近一次 `adb devices -l` 看到真机的时刻；② 本单首次因设备挂起的时刻。由持单人起算并写进评论。
2. 离线超过 N 后，单 issue 级验收降到 **`L0 + L1 + L3（人眼自述）`**。
3. **真机截图这一项从单 issue 移到统一合并窗口**（项目交付规则第四条本来就把装机全量回归落在合并窗口，不在单 issue）。
4. 设备回线后，已按降级收口的单**不自动失效**，但合并窗口的真机回归必须补齐；未补齐不得进 `master`。

选 24h 而不是「一直等」的理由：24h 覆盖「今天插不上、明天能插」的常见情形；超过一天仍无设备说明不是偶发，继续等只会累积静默（本轮实证：3 天、9 张单同卡一条线）。

## 3. 逐级怎么取证

### 3.1 L0 —— 从 APK 读字节码（已验证可跑）

```bash
APK=dist/ispeech-release-1.5-20261119-1557.apk   # 换成待收口的包
unzip -l "$APK" | grep -E 'classes.*\.dex'        # 确认 dex 分片
mkdir -p /tmp/l0 && unzip -q -o "$APK" 'classes*.dex' -d /tmp/l0
DEXDUMP=$ANDROID_HOME/build-tools/<ver>/dexdump   # 本机：/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/dexdump
$DEXDUMP -d /tmp/l0/classes3.dex | grep -n 'Typeface;'   # 定位符号引用
```

判据写法：在评论里贴**类.方法 + 指令行 + 引用符号**三件套，不要只贴「我看了没问题」。例（VOCA-56 字重，实测）：

```
com.ispeech.input.CandidateView.createItemView
  001e: sget-object v4, Landroid/graphics/Typeface;.DEFAULT;
  0020: invoke-virtual {v3, v4}, Landroid/widget/TextView;.setTypeface
```

同时贴 APK 的 `shasum -a 256`，证明说的是**设备/制品上的那个包**，不是工作区源码。

### 3.2 L1 —— 机检门禁（必须先 PASS）

```bash
python3 tools/verify/check_crosscut.py    # 期望末尾 SUMMARY: passed=N failed=0
```

L1 小节归**测试工程师**（质量门禁 owner）维护；本规则不新增断言，也不豁免任何既有断言。

### 3.3 L2 —— 为什么在本项目不成立

模拟器只在 AVD **确有该字形**时才作数。实测本机 AVD（`emulator-5554`，API 34）：

```bash
adb -s emulator-5554 shell 'ls /system/fonts | grep -iE "CJK"'
# → NotoSansCJK-Regular.ttc / NotoSerifCJK-Regular.ttc   （只有 Regular，无中文粗体面）
```

中文没有 Bold 面时，加粗包与不加粗包**像素相同**，截图对字重类缺陷零信息量。所以 L2 只能用于「有没有渲染出来」，不能用于字重 / 观感判定。

### 3.4 L3 —— 人眼自述的记录格式

一句话即可，但必须含这四项，缺一项不算证据：

```
设备：无可用真机（最近一次 adb devices -l 见真机：<时间戳>）
包：<apk 文件名> sha256 <前 8 位>
看的是：<页面/控件>，<具体看到了什么>
结论：<符合预期 / 不符合，差在哪>
```

由**报单人本人**写；他人转述不算 L3。真机截图回线后补进合并窗口归档，不阻塞本单。

## 4. 与既有门禁的关系

- 本规则**只放宽「用户看到什么」这一项的证据形态**；功能正确性、L1 机检、发布巡检门禁、`develop → master` 人类验收门**全部不变**。
- 不回溯：已按原口径收口的单不改结论；本规则不自动解除任何在途单的 `blocked`，由持单人按新口径自行复跑。
- 放宽过头的对冲：L1 是常驻合并门禁，不随本规则免除；且合并窗口的真机全量回归是最后一道闸。

## 5. 机器可检点

```bash
grep -n "N = 24 小时" docs/device-acceptance-degradation.md
grep -n "L0 + L1 + L3" docs/device-acceptance-degradation.md
```

两行都在 = 条文未丢；缺任一行即视为规则被改坏。

## 6. 方法验证记录（2026-11-27，起草时实跑）

| 断言 | 命令 | 结果 |
|---|---|---|
| APK 可分片出 dex | `unzip -l dist/ispeech-release-1.5-20261119-1557.apk \| grep classes` | classes.dex / classes2.dex / classes3.dex |
| L0 能读到字重符号 | `dexdump -d classes3.dex` + `createItemView` 段 | `Typeface;.DEFAULT`（非 `DEFAULT_BOLD`） |
| 真机确实不可用 | `adb devices -l`、`adb mdns services`、`ioreg -p IOUSB` | 仅 `emulator-5554`；mdns 空；USB 无 Android 节点 |
| AVD 无中文粗体面 | `adb -s emulator-5554 shell 'ls /system/fonts \| grep CJK'` | 仅 `NotoSansCJK-Regular.ttc` |