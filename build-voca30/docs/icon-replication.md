## 8. 更新：Android 版搜狗才是「工具栏图标」的正样本（2026-10-18 补充）

§1–§3 的结论（macOS 版没有软键盘图标）**成立，但不足以支撑「复刻所有图标」**。
连上的真机上恰好装着 Android 版搜狗输入法，这才是正样本：

```bash
ADB=/Users/mini/platform-tools/adb; D=FMR0224913042917
$ADB -s $D shell pm path com.sohu.inputmethod.sogou      # → /data/app/…/base.apk
$ADB -s $D pull <上面的路径> work/sogou-android/sogou.apk  # 98,646,955 bytes
```

APK 内是**皮肤化的图片资源**（非 macOS 那种「没有」）：

| 事实 | 证据（`unzip -l` 统计） |
|---|---|
| 皮肤资源总量 | `assets/keyboard/**` 下 PNG 共 **9753** 个 |
| 皮肤按厂商 / 分辨率 / 配色分层 | `assets/keyboard/honor_user/theme/theme_default/{1080,720}/res/{black,white}/` |
| 键帽形状（非图标） | `key.png` `key_qwerty.png` `key_func.png` `key_active_p.png` …（每套 20+ 个状态变体） |
| **工具栏功能图标** | `ime_function_*.png`（含 `_p` 按下态），如 `ime_function_ai(_p)`、`ime_function_expression(_p)`、`ime_function_voice(_p)`、`ime_function_clipboard`、`ime_function_phrases`、`ime_function_voice_helper`、`ime_function_chinese_setting`、`ime_function_cht(_on)`、`ime_function_edit_left/right/up/down(_s)`、`ime_function_keyboard_layout`、`ime_function_candidates_switch` |
| 候选区图标 | `back.png` `backspace_more_cand.png` `anser_search_icon.png` `assoc_*.png` … |

**这直接回答了 §0 里「工具栏图标到底是文件还是绘制」的遗留问题**：Android 版是**图片资源**，
且命名规整成对（`x` / `x_p` = 常态 / 按下态）。

要点回填（本仓库当前的落地与它的对应关系）：

| 搜狗 Android 资产 | 本仓库 | 说明 |
|---|---|---|
| `ime_function_ai.png` / `_p` | — | ❌ 不属于搜狗五笔输入法图标（搜狗 Android 版工具栏无 AI 键） |
| `ime_function_expression.png` / `_p` | `ic_emoji.xml` | ✅ |
| `ime_function_voice.png` / `_p` | — | ❌ 不属于搜狗五笔输入法图标（搜狗 Android 版工具栏无语音键） |
| `ime_function_clipboard.png` | `ic_clipboard.xml` | ✅ |
| `ime_function_phrases.png` | `ic_phrase.xml` | ✅ |
| `ime_function_chinese_setting.png` / `ime_function_cht` | `ic_lang_switch.xml` | ✅ |
| `ime_function_edit_left/right(_s)` | `ic_cursor_left/right.xml` | ✅ |
| `back.png` / `back_p.png` | `ic_back.xml`（矢量重绘） | ✅ 已对齐形状（左折线） |
| `anser_search_icon.png` | `ic_search.xml` | ✅ |
| `assoc_*.png`（联想来源角标） | 候选条来源标签（文字胶囊） | ✅ 差异：搜狗用图，本仓库用文字 |

> **注意**：以上 png 属搜狗**皮肤/品牌资源**，直接拷进本仓库会带来许可与商标问题（同 §5）。
> 合规做法是**按几何描述重绘成 VectorDrawable**（与本轮 `ic_page_prev/next` 的做法一致），
> 而不是把 png 原样打包。本节所有图标均已按此方式落地。