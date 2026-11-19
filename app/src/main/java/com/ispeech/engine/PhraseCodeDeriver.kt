package com.ispeech.engine

import com.ispeech.data.codePointLength

/**
 * 五笔双字词组码推导（ADR-0001 §2）：纯函数，不查词库、不依赖 Android，可直接 JVM 单测。
 *
 * 规则：`code(AB) = 首2(A的全码) + 首2(B的全码)`，其中「全码」由调用方注入的 [codeOf] 提供，
 * 实现侧由 [WubiEngine.fullCodeOf] 给出（同字多条记录时取最长码、同长取权重最高、再同取字典序最小）。
 *
 * **为什么必须用最长码而不是最高权重码**（ADR §0 实测）：`产` 的最高权重码是 1 位的 `u`，
 * 取前 2 位只能得到 `u`，会让 92 个以「产」结尾的词静默算错（`共产` 应为 `awut`，算成 `awu`）。
 * 因此 [codeOf] 返回长度 < 2 的码时一律放弃（见 [derive]），绝不用短码凑。
 */
internal object PhraseCodeDeriver {

    /**
     * 推导两字词组的 4 位五笔码。
     *
     * @param first 第一字（单个**码点**的 String，不是 Char；词库里有 42,870 个非 BMP 单字）
     * @param second 第二字（同上）
     * @param codeOf 单字码点串 → 全码；查不到返回 null
     * @return 4 位小写码；任一前置条件不满足返回 null（宁可放弃，不写半成品条目）
     */
    fun derive(first: String, second: String, codeOf: (String) -> String?): String? {
        if (!isSingleCjk(first) || !isSingleCjk(second)) return null
        val head = prefix(codeOf(first) ?: return null) ?: return null
        val tail = prefix(codeOf(second) ?: return null) ?: return null
        return head + tail
    }

    /** 取全码前 2 位；码不足 2 位或含非 a-z 字符时返回 null。 */
    fun prefix(code: String): String? {
        if (code.length < 2) return null
        val head = code.substring(0, 2)
        for (ch in head) if (ch !in 'a'..'z') return null
        return head
    }

    /** 是否为单个 CJK 统一表意文字（含扩展区）；`T恤`/`U盘` 这类首字不在汉字区的词直接放弃。 */
    fun isSingleCjk(text: String): Boolean {
        if (codePointLength(text) != 1) return false
        val cp = text.codePointAt(0)
        return cp in 0x4E00..0x9FFF ||        // CJK 统一表意文字
            cp in 0x3400..0x4DBF ||           // 扩展 A
            cp in 0xF900..0xFAFF ||           // 兼容表意文字
            cp in 0x20000..0x2A6DF ||         // 扩展 B
            cp in 0x2A700..0x2B73F ||         // 扩展 C
            cp in 0x2B740..0x2B81F ||         // 扩展 D
            cp in 0x2B820..0x2CEAF ||         // 扩展 E
            cp in 0x2CEB0..0x2EBEF ||         // 扩展 F
            cp in 0x2F800..0x2FA1F           // 兼容表意文字补充
    }
}