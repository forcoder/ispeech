package com.ispeech.input

import com.ispeech.R

/**
 * 语音输入（ASR）时限与超时文案：全链路**唯一**的超时定义处（VOCA-231）。
 *
 * 立项目的：真机上「语音识别中…」曾连续 75 秒不复位。当时全链路只有一处兜底超时
 * （松手后等收尾结果 6 秒），而它**只在收到抬起事件之后才挂表** —— 于是抬起事件没回来、
 * 识别服务收了会话不回话、离线引擎卡在读音频这三种情况都没有任何时限，UI 永久停住；
 * 之后每次再按语音键又都被「已在收音」挡回去，表现为「越点越没反应」。
 *
 * 现在的覆盖方式（三条时限把「识别中」夹成一段**有上界**的状态）：
 *
 * ```
 * 开始收音 ──[FIRST_CALLBACK_TIMEOUT_MS]──> 必须有回调（识别服务/引擎真的在干活）
 * 收音中   ──[SESSION_TIMEOUT_MS]────────> 引擎侧硬上限，到点按「松手」收尾（已说的字还能落）
 * 收尾中   ──[FINISH_TIMEOUT_MS]─────────> 拿不到结果就复位报错
 * 全程     ──[UI_SESSION_TIMEOUT_MS]─────> UI 侧最后一道：引擎没兜住也必须离开「识别中」
 * ```
 *
 * 取值口径：宁可先复位让用户重说一次，也不让语音键永久占住麦克风（独占资源）。
 * 任一超时都必须①复位状态②给出用户可读文案（见 [messageFor]）③打 `ev=timeout` 埋点，
 * 禁止静默卡住。
 */
object AsrTiming {

    // ── 时限（毫秒）──

    /** 已下发「开始收音」到等到第一个回调的上限：超过即判定识别服务/引擎没有响应。 */
    const val FIRST_CALLBACK_TIMEOUT_MS = 10_000L

    /** 松手（收尾）到拿到最终结果的上限：超过即按「没等到结果」复位。 */
    const val FINISH_TIMEOUT_MS = 6_000L

    /** 一次语音会话「收音中」阶段的硬上限：到点自动收尾，覆盖「抬起事件丢失 / 按住不放」。 */
    const val SESSION_TIMEOUT_MS = 60_000L

    /**
     * UI 侧最后一道兜底：比引擎侧上限再宽出一个收尾窗口，保证引擎「自动收尾」的正常路径先走完，
     * 本条只在引擎彻底没兜住时触发（触发即丢弃会话，所以不该是常规路径）。
     */
    const val UI_SESSION_TIMEOUT_MS = SESSION_TIMEOUT_MS + FINISH_TIMEOUT_MS

    /** 离线引擎收音期间连续拿不到任何音频样本的上限：超过即判定录音不可用（自旋没有意义）。 */
    const val NO_AUDIO_TIMEOUT_MS = 10_000L

    // ── 超时阶段：同时作为 [AsrTrace] 的 `stage=` 取值，日志与文案同一口径 ──

    /** 开始收音后没有任何回调。 */
    const val STAGE_START = "start"

    /** 已松手收尾，等不到最终结果。 */
    const val STAGE_FINISH = "finish"

    /** 一次会话超过硬上限（含抬起事件丢失）。 */
    const val STAGE_SESSION = "session"

    /** 收音阶段拿不到音频数据（麦克风被占用 / 权限被系统回收）。 */
    const val STAGE_AUDIO = "audio"

    /**
     * 超时后给用户看的文案，按触发阶段区分 —— 用户要知道该重试、该换引擎，还是该分短句说；
     * 集中在这里，避免三个引擎各写一句、口径漂移。
     */
    fun messageFor(stage: String): Int = when (stage) {
        STAGE_START -> R.string.voice_timeout_service
        STAGE_FINISH -> R.string.voice_timeout_result
        STAGE_SESSION -> R.string.voice_timeout_session
        STAGE_AUDIO -> R.string.voice_audio_error
        else -> R.string.voice_error
    }
}
