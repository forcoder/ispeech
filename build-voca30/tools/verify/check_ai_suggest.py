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
    check_settings_ui()
    print("-" * 62)
    print(f"SUMMARY: passed={_passed} failed={_failed}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())