package com.ispeech.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `WubiEngine` 构建成本（QA 风险项：本分支在 `init` 里多建了 `byText` 与 `knownTexts` 两张表，
 * 而 main 把引擎构建放在 `onCreate` 主线程同步执行）。
 *
 * 这条用例给的是**数量级 + 回归护栏**，不是性能门禁：阈值放得很松（真机慢一个数量级也不会碰到），
 * 目的是让「索引结构失控」这种质变能被测出来。数字打在 JUnit 报告的 `<system-out>` 里：
 * `app/build/test-results/testDebugUnitTest/TEST-com.ispeech.engine.WubiEngineInitCostTest.xml`。
 *
 * main vs 本分支的对照数（同一台机、同一个 JDK）：把本文件原样拷进 `git archive origin/main`
 * 的导出目录，跑
 * `./gradlew :app:testDebugUnitTest --tests "com.ispeech.engine.WubiEngineInitCostTest" --rerun-tasks`。
 * **测法必须两树交替 A/B、每棵树丢弃第一遍**，各取三遍有效值再比中位数：单侧连跑会让先跑的那棵树
 * 独享 `wubi86.dict` 的冷文件缓存（实测比热缓存高出一倍以上），照「一边跑完三遍再跑另一边」得到的
 * 差值不是本次改动的成本。本文件在 main 上同样可编译（只用到了两版都有的四个计数属性）。
 */
class WubiEngineInitCostTest {

    @Test
    fun `真实词库的构建耗时与常驻内存有数`() {
        val dict = File("src/main/assets/wubi86.dict")
        assertTrue("找不到真实词库：${dict.absolutePath}", dict.exists())
        val text = dict.readText(Charsets.UTF_8)

        repeat(3) { WubiEngine(text) }                       // JIT 预热，不计入
        val runs = (1..5).map { index ->
            val t0 = System.nanoTime()
            val engine = WubiEngine(text)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("init[$index] = $ms ms")
            ms to engine
        }
        val sample = runs.last().second
        val sorted = runs.map { it.first }.sorted()
        println(
            "SUMMARY init: min=${sorted.first()} median=${sorted[sorted.size / 2]} max=${sorted.last()} ms" +
                " | entries=${sample.entryCount} codes=${sample.codeCount}" +
                " singles=${sample.singleCount} phrases=${sample.phraseCount}",
        )

        val runtime = Runtime.getRuntime()

        fun liveHeap(): Long {
            repeat(4) {
                System.gc()
                Thread.sleep(60)
            }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        val before = liveHeap()
        val engines = List(3) { WubiEngine(text) }
        val perEngine = (liveHeap() - before) / engines.size
        println("SUMMARY retained ~= ${perEngine / 1024 / 1024} MiB / engine（词库文本 `text` 三份共享，不计入）")

        assertTrue("构建耗时异常：中位数 ${sorted[sorted.size / 2]} ms", sorted[sorted.size / 2] < 3_000)
        assertTrue("常驻内存异常：$perEngine bytes", perEngine < 128L * 1024 * 1024)
    }
}
