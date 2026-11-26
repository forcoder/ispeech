#!/usr/bin/env python3
"""AI 建议回复（快捷短语优先 + 大模型生成 + 本地学习）链路校验。

纯静态检查，不编译、不联网。校验四段闭环：

    A. 模型配置：常用厂商预设齐全，设置页可读写，缺配置时有明确提示；
    B. 生成链路：快捷短语优先 → 未命中才调模型 → csBaby / 直连两种来源；
    C. 学习链路：采纳的建议写本机、下次作为 few-shot 带进提示词、采纳率可统计；
    D. 设置入口：首页卡片 + 路由 + 设置页可清空学习数据。

用法：``python3 tools/verify/check_ai_suggest.py``
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "app/src/main/java/com/ispeech/input/IspeechImeService.kt"
AI_SETTINGS = ROOT / "app/src/main/java/com/ispeech/settings/store/AiSettings.kt"
SETTINGS_STORE = ROOT / "app/src/main/java/com/ispeech/settings/store/SettingsStore.kt"
LEARNING = ROOT / "app/src/main/java/com/ispeech/data/AiLearningStore.kt"
DIRECT = ROOT / "app/src/main/java/com/ispeech/data/DirectLlmClient.kt"
CHAT_API = ROOT / "app/src/main/java/com/ispeech/data/remote/ChatApiService.kt"
AI_SCREEN = ROOT / "app/src/main/java/com/ispeech/settings/ai/AiSettingsScreen.kt"
AI_VM = ROOT / "app/src/main/java/com/ispeech/settings/ai/AiSettingsViewModel.kt"
HOME = ROOT / "app/src/main/java/com/ispeech/settings/HomeScreen.kt"
NAV = ROOT / "app/src/main/java/com/ispeech/settings/nav/IspeechNavGraph.kt"
PANEL = ROOT / "app/src/main/java/com/ispeech/input/AiReplyPanel.kt"
GUARD = ROOT / "app/src/main/java/com/ispeech/input/AiSourceGuard.kt"
ADB_SCRIPT = ROOT / "tools/verify/voca628_ai_one_tap_adb.py"
LAYOUT = ROOT / "app/src/main/res/layout/ime_input_view.xml"

_passed = 0
_failed = 0


def check(ok: bool, name: str, detail: str) -> None:
    global _passed, _failed
    if ok:
        _passed += 1
        print(f"PASS: {name}")
    else:
        _failed += 1
        print(f"FAIL: {name}\n      {detail}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def check_presets() -> None:
    text = read(AI_SETTINGS)
    for key in ("OPENAI(", "DEEPSEEK(", "QWEN(", "ZHIPU(", "MOONSHOT(", "CUSTOM(", "CSBABY("):
        check(key in text, f"模型预设：包含 {key[:-1]}", f"AiProvider 缺少 {key[:-1]} 选项")
    # 预设必须带 Base URL，否则用户仍要查文档
    check(
        text.count("https://") >= 5,
        "模型预设：常用厂商都带默认 Base URL",
        "预设 Base URL 少于 5 条",
    )
    check(
        "fun isReady()" in text and "apiKey.isNotBlank()" in text,
        "模型预设：缺 Base URL / 模型 / Key 时可在保存前判定",
        "AiSettings.isReady() 未校验三项",
    )


def check_settings_store() -> None:
    text = read(SETTINGS_STORE)
    for key in ("ai_provider", "ai_base_url", "ai_model", "ai_api_key", "ai_learning"):
        check(f'"{key}"' in text, f"设置存储：存在 {key}", f"SettingsStore 缺 {key}")
    check(
        "fun aiSettingsFlow(" in text and "suspend fun setAiSettings(" in text,
        "设置存储：AI 配置可读可写",
        "缺 aiSettingsFlow / setAiSettings",
    )


def check_generation_chain() -> None:
    text = read(SERVICE)
    phrase_pos = text.find("val phrases = matchQuickPhrases(message)")
    llm_pos = text.find("val aiSettings = SettingsStore.aiSettingsFlow")
    check(
        phrase_pos != -1 and llm_pos != -1 and phrase_pos < llm_pos,
        "生成链路：快捷短语优先于大模型",
        "短语匹配未前置于模型调用",
    )
    check(
        "if (!aiSettings.isReady())" in text and "请先在设置页配置大模型" in text,
        "生成链路：配置不全时给出可操作提示",
        "缺 isReady 判定或提示文案",
    )
    check(
        "if (aiSettings.provider.isDirect)" in text and ".generateReply(auth, message)" in text,
        "生成链路：直连与 csBaby 两条来源都在",
        "缺直连 / csBaby 分支",
    )
    direct = read(DIRECT)
    check(
        "chat/completions" in read(CHAT_API) or '"chat/completions"' in read(CHAT_API),
        "生成链路：直连走 OpenAI 兼容 chat/completions",
        "ChatApiService 未指向 chat/completions",
    )
    check(
        "Authorization" in read(CHAT_API) and "Bearer" in direct,
        "生成链路：直连请求带 Bearer 鉴权",
        "缺 Bearer 鉴权",
    )


def check_learning_chain() -> None:
    learning = read(LEARNING)
    check(
        "fun recordAccepted(" in learning and "fun recordGenerated(" in learning,
        "学习链路：记录生成与采纳",
        "AiLearningStore 缺 recordAccepted / recordGenerated",
    )
    check(
        "fun examples(" in learning and "thenByDescending { it.count }" in learning,
        "学习链路：示例按相似度 + 频次排序",
        "examples 未按相似度 / 频次排序",
    )
    check(
        "MAX_EXAMPLES" in learning,
        "学习链路：语料有条数上限，不会无界增长",
        "缺 MAX_EXAMPLES 上限",
    )
    direct = read(DIRECT)
    check(
        "examples.joinToString" in direct and "过去自己采纳过的回复" in direct,
        "学习链路：采纳记录作为 few-shot 进提示词",
        "DirectLlmClient 未使用 examples 组装提示词",
    )
    check(
        "findReusable(" in learning and "DEFAULT_REUSE_SCORE" in learning,
        "学习链路：提供高相似历史回复的复用入口",
        "AiLearningStore 缺 findReusable",
    )
    service = read(SERVICE)
    reuse_pos = service.find("aiLearning.findReusable(message)")
    llm_pos = service.find(".generateReply(auth, message)")
    check(
        reuse_pos != -1 and llm_pos != -1 and reuse_pos < llm_pos,
        "学习链路：复用判断发生在大模型调用之前",
        "findReusable 未前置于模型调用",
    )
    check(
        'codeOverride == "常用回复"' in service,
        "学习链路：复用候选的编码区标记可被清理",
        "dismissAiState 未清理「常用回复」",
    )
    check(
        "aiLearning.examples(DirectLlmClient.MAX_FEW_SHOT, message)" in service
        and "if (aiSettings.learning)" in service,
        "学习链路：学习开关生效（关闭则不喂示例）",
        "startAiReply 未按 learning 开关取示例",
    )
    check(
        "aiLearning.recordAccepted(source, reply)" in service
        and "if (aiReplyFromModel)" in service,
        "学习链路：只有模型产出 / 历史复用的建议才记采纳",
        "commitAiReply 未记录采纳或未区分来源",
    )
    check(
        re.search(r"withContext\(Dispatchers\.IO\)\s*\{\s*aiLearning\.examples", service) is not None
        and re.search(r"withContext\(Dispatchers\.IO\)\s*\{\s*aiLearning\.findReusable", service) is not None,
        "学习链路：学习库读写在 IO 线程，不占主线程",
        "学习库文件读写在主线程（会拖慢输入法）",
    )
    check(
        "aiReplyFromModel = false" in service,
        "学习链路：快捷短语命中不计入采纳率",
        "短语分支未清 aiReplyFromModel",
    )


def check_one_tap_fill() -> None:
    """VOCA-65 条目 5a（VOCA-628）：一步填入。

    面板必须先真的接上服务（历史缺陷：布局里声明了 AiReplyPanel，服务侧从未
    findViewById，等于死代码），再满足「点行即上屏 / 长按或…进详情 / 原文校验不复制」。
    """
    service = read(SERVICE)
    panel = read(PANEL)
    layout = read(LAYOUT)

    check(
        "com.ispeech.input.AiReplyPanel" in layout and "ai_reply_panel" in layout,
        "一步填入：布局里声明了 AI 结果面板",
        "ime_input_view.xml 缺 AiReplyPanel",
    )
    check(
        "findViewById<AiReplyPanel>(R.id.ai_reply_panel)" in service and "aiReplyPanel = replyPanel" in service,
        "一步填入：服务侧真正接线面板（不是死代码）",
        "IspeechImeService 未 findViewById / 未保存 aiReplyPanel",
    )
    check(
        "replyPanel.onPick = { index -> commitAiReply(index) }" in service,
        "一步填入：点行直接走 commitAiReply，不复制上屏路径",
        "面板 onPick 未接到 commitAiReply(index)",
    )
    check(
        "setOnClickListener { pick(index) }" in panel and "setOnLongClickListener" in panel,
        "一步填入：点行上屏、长按进详情（AC-5a.1 / 5a.2）",
        "AiReplyPanel 行点击/长按未分别接 pick / showDetail",
    )
    check(
        "private fun showDetail(" in panel and "more" in panel,
        "一步填入：详情页入口保留（长按 + 行尾…）",
        "详情页入口丢失",
    )
    # AC-5a.3：替换校验只此一份，面板不得自己删原文
    check(
        "AiSourceGuard.shouldReplace(" in service,
        "一步填入：原文替换校验走共享判定（AC-5a.3）",
        "commitAiReply 未使用 AiSourceGuard",
    )
    check(
        "deleteSurroundingText" in service and "deleteSurroundingText" not in panel,
        "一步填入：删除原文只发生在服务侧一条路径",
        "面板内出现 deleteSurroundingText 副本",
    )
    guard = read(GUARD)
    check(
        "beforeCursor == sourceText" in guard,
        "一步填入：校验是「逐字相等」而非仅比长度",
        "AiSourceGuard 未做逐字比较",
    )
    check(
        "if (panel == Panel.AI_REPLY) closePanel()" in service,
        "一步填入：上屏后关闭面板（AC-5a.1）",
        "commitAiReply 未在 AI 面板打开时关闭面板",
    )
    check(
        "aiReplyPanel?.submit(emptyList())" in service,
        "一步填入：无结果 / 失败时清空面板（AC-5a.4）",
        "dismissAiState 未清空面板结果",
    )
    # ── adb 取证脚本自身的判据（VOCA-628 质检三轮打回的地方）──
    # 脚本是 AC 的唯一复现入口，它的判据烂了会让「真机上通过」这件事无从核实，
    # 所以这里把三处已裁决的口径钉成静态断言，避免下一轮又按坐标猜 / 又空断言 PASS。
    adb = read(ADB_SCRIPT)
    check(
        "def find_result_rows(" in adb and "w - 210" in adb,
        "取证脚本：结果行与「…」按截图自扫定位，不按 W−16dp 猜",
        "voca628 脚本未自扫白底行带 / 行尾暗列",
    )
    check(
        "skipped: bool = False" in adb and "def skip(" in adb,
        "取证脚本：区分 PASS / FAIL / SKIP，未取证不冒充通过",
        "脚本缺 SKIP 档，快捷短语模式会把 5a.5 空断言报成 PASS",
    )
    check(
        "if not d.open_ai_panel():" in adb and "return 2" in adb,
        "取证脚本：5a.0 前置失败硬停，不继续判后续用例",
        "open_ai_panel 失败后仍往下执行",
    )
    check(
        "expect_results=False" in adb and "def panel_open(" in adb,
        "取证脚本：空态面板与「AI 键点不中」分开判",
        "空态面板会被误报成 AI 键定位失败",
    )
    check(
        "def emulator_reasons(" in adb and "ro.kernel.qemu" in adb
        and 'args.strict_device and "emulator" in args.serial' not in adb,
        "取证脚本：--strict-device 按设备属性判真机，不看 serial 字符串",
        "仍按 serial 子串判真机，换名接入的模拟器可冒充真机把 5a.3 打成 PASS",
    )
    import importlib.util
    spec = importlib.util.spec_from_file_location("voca628_adb", ADB_SCRIPT)
    adb_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adb_mod)
    emu_props = {"ro.kernel.qemu": "1", "ro.boot.qemu": "1", "ro.hardware": "ranchu",
                 "ro.build.characteristics": "emulator", "ro.product.device": "emu64a",
                 "ro.product.model": "sdk_gphone64_arm64", "ro.product.manufacturer": "Google"}
    real_props = {"ro.kernel.qemu": "", "ro.boot.qemu": "", "ro.hardware": "qcom",
                  "ro.build.characteristics": "nosdcard", "ro.product.device": "raven",
                  "ro.product.model": "Pixel 6 Pro", "ro.product.manufacturer": "Google"}
    check(
        bool(adb_mod.emulator_reasons(emu_props)) and not adb_mod.emulator_reasons(real_props),
        "取证脚本：emulator_reasons 模拟器拒、真机放",
        f"模拟器={adb_mod.emulator_reasons(emu_props)} "
        f"真机={adb_mod.emulator_reasons(real_props)}",
    )
    check(
        "submitAiResults(" in service,
        "一步填入：结果灌入面板带来源日志（AC-5a.5 可观测）",
        "缺少 submitAiResults 结构化日志",
    )
    test = ROOT / "app/src/test/java/com/ispeech/input/AiSourceGuardTest.kt"
    check(
        test.exists() and "shouldReplace" in read(test),
        "一步填入：替换校验有 JVM 单测覆盖",
        "缺 AiSourceGuardTest",
    )


def check_settings_ui() -> None:
    screen = read(AI_SCREEN)
    check(
        "AiProvider.entries.forEach" in screen,
        "设置页：列出全部模型来源",
        "设置页未遍历 AiProvider",
    )
    check(
        "PasswordVisualTransformation" in screen,
        "设置页：API Key 输入框做掩码",
        "Key 输入框未做掩码",
    )
    check(
        "清空学习数据" in screen and "clearLearning()" in screen,
        "设置页：可清空学习数据",
        "缺清空学习数据入口",
    )
    check(
        "采纳率" in screen,
        "设置页：展示采纳率",
        "设置页未展示采纳率",
    )
    vm = read(AI_VM)
    check(
        "fun clearLearning()" in vm and "learning.clear()" in vm,
        "设置页 ViewModel：清空学习数据落到存储层",
        "ViewModel 未调用 learning.clear()",
    )
    check(
        "private fun normalize(" in vm and "baseUrl.ifBlank { settings.provider.baseUrl }" in vm,
        "设置页：Base URL / 模型名默认值直接回填到输入框",
        "载入时未把预设回填成可见默认值",
    )
    check(
        "baseUrl = if (provider.isDirect) provider.baseUrl else \"\"" in vm
        and "model = if (provider.isDirect) provider.defaultModel else \"\"" in vm,
        "设置页：切换来源时默认值跟着换，避免串厂商",
        "切换来源未更新默认值",
    )
    check(
        "onOpenAi" in read(HOME) and "IspeechRoutes.AI" in read(NAV),
        "设置入口：首页卡片 + 路由接通",
        "首页卡片或路由缺失",
    )


def main() -> int:
    print("=" * 62)
    print("AI 建议回复链路校验")
    print("=" * 62)
    check_presets()
    check_settings_store()
    check_generation_chain()
    check_learning_chain()
    check_one_tap_fill()
    check_settings_ui()
    print("-" * 62)
    print(f"SUMMARY: passed={_passed} failed={_failed}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())