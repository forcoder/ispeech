package com.ispeech.data

/** csBaby 请求统一异常，便于 UI 层区分处理。 */
sealed class CsBabyException(message: String) : Exception(message) {

    /** 业务错误：HTTP 成功但业务失败（error 非空或缺少关键字段）。 */
    class Api(message: String) : CsBabyException(message)

    /** HTTP 错误：响应码非 2xx。 */
    class Http(val code: Int, val body: String?) : CsBabyException(
        if (body.isNullOrBlank()) "HTTP $code" else "HTTP $code：$body"
    )

    /** 网络错误：连接失败、超时等 IO 异常。 */
    class Network(message: String) : CsBabyException(message)
}
