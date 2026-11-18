# VOCA-443 取证工装：T9 态 -/= 横滚等价性 + 上屏后偏移复位

规格 §9.8 ③④ 的两条挂账 AC，只测这两条，不承接 VOCA-47 的 go/no-go。

## 用法

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
# 先构建被测包（JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug）
python3 t9ac.py <app-debug.apk> --leg all     # t9 / wubi / all
```

- 环境：emulator-5554（qa183_ac45，1080×2340@440dpi，API34），adb 独占。
- 退出码：0=测量完成（行为结论看 RESULT 行）；非 0=闸门失败（GATE reason= rc=），不出测量。
  rc 表见 t9ac.py `RC`（host_not_ready=10 / frame_none=11 / readback=12 / bg_low=13 /
  pool_low=14 / nav_fail=15 / not_at_origin=16 / ime_not_ours=17 / ident=19）。
- 证据：stdout 全量落 `evidence/ev_all_run*.txt`，首尾 `IDENT match=True`（逐 entry
  sha256 比对 classes*.dex + resources.arsc，本地包 vs `pm path` 拉回的已安装包）。

## 判据口径

| 项 | 口径 |
|---|---|
| 步长期望 | 72dp × 2.75 = **198px ±6**（CandidateView.kt:199 SCROLL_STEP_DP=72，:90-92 smoothScrollBy） |
| 位移测量 | 列剖面互相关 drive.shift()，正=内容左移=视口向右；err>5 的步不可判 |
| 回读（T9） | T9 组合态 chip 恒 GONE（renderCandidateView code=statusText?:buffer，IspeechImeService.kt:1131-1135），改读**宿主组合文本**（refreshT9Candidates :850 setComposingText(digits)），与 chip 同源 t9Digits |
| 回读（上屏后下一字） | 上屏词留在框内，新组合文本在词尾 → `endswith`，不全等 |
| 池 ≥8 | '=' 扫到右端夹止（连续 3 次零位移），帧投内容坐标按项右沿聚簇去重（band.count_items_content_space），'-' 扫回左端且 \|back+cum\|≤10 才开测 |
| AC-a2 offset=0 | 可判定探针=左边界探针：'-' 像素静止 **且** '=' 走出 +198±6 → 视口在左端；候选 ≤1 时退静态判据（首选高亮盒左沿 == paddingStart 8dp）。字面 Then（scrollX==0）无 IME 节点可 dump（§9.9），像素探针是等价可判形式，**UX 裁定前按风险挂账** |
| 清场 | T9 缓冲只能软键 del 清（handleBackspace :674-677）；硬件 DEL 在 :568 放行宿主、硬件 ESC 在 :575 早退，都清不掉 t9Digits（run7 实测残留拼成 '7474'） |

## 与 VOCA-47 工装（1b7cf02）的差异

1. `band.py analyze(chip_required=False)`：无 chip 帧（T9 组合/联想）采样起点退到
   首选高亮盒左沿或 paddingStart；返回 dict 补 `x0` 别名、chip_right/chip_gate None-safe。
2. `drive.py` 宿主导航针补英文 locale 的 `search_action_bar`（Settings 首页是可点条不是 EditText）。
3. 导航全截图驱动：字母→123→9键 进 T9，T9→*→符号→ABC 回字母；键帽行按
   高度窗 [0.75,1.35]×52dp + 排除近黑（手势导航条）+ ≥3 键格（排除恒 46dp 空候选带）识别。
4. 面板宽度取截图自身宽度（band.py 第七轮口径沿用），不写死 1080。

## 已知边界

- IME 窗口不可 uiautomator dump（NODEPROBE 实测 ime_window_dumpable=False），
  一切判定走像素；§9-11 挂载层运行时检查因此做不了，见 REPORT。
- 联想态候选池异步补词，跨 run 上屏词可能不同（run8 '到' / run9 '来'），不影响 AC 判定。