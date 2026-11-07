package com.dzienisz.zureqshopper.api

/**
 * A failure talking to ZUREQ. [serverMessage] is the shop/API text when there is one; the UI maps [code] to
 * localized text otherwise.
 */
class ZureqException(val code: Code, val serverMessage: String? = null) : Exception(serverMessage ?: code.name) {
    enum class Code {
        NO_KEY, BAD_KEY, RATE_LIMIT, CREDITS_EXHAUSTED, INVALID_ARGS, UNAVAILABLE, API_ERROR, NETWORK_ERROR,
        PARSE_ERROR, AUTH_CANCELLED, AUTH_FAILED, SIGN_IN_ENDED, INVALID_PRICE,
    }

    /** Errors that will fail every following call too, so batch work should stop. */
    val stopsBatch: Boolean get() = code in setOf(Code.NO_KEY, Code.SIGN_IN_ENDED, Code.BAD_KEY, Code.CREDITS_EXHAUSTED)

    /** Per docs.zureq.io: retry once on rate limits, 503/-32603, shop timeouts and "temporarily unavailable". */
    val isRetryable: Boolean
        get() = when (code) {
            Code.RATE_LIMIT, Code.UNAVAILABLE -> true
            Code.API_ERROR -> {
                val text = serverMessage.orEmpty().lowercase()
                "temporarily unavailable" in text || "did not answer in time" in text || "timed out" in text
            }
            else -> false
        }

    companion object {
        val noKey = ZureqException(Code.NO_KEY)
        val signInEnded = ZureqException(Code.SIGN_IN_ENDED)
        val unavailable = ZureqException(Code.UNAVAILABLE)
        val rateLimit = ZureqException(Code.RATE_LIMIT)

        fun fromRpc(code: Int?, message: String?): ZureqException = when (code) {
            -32001 -> ZureqException(Code.BAD_KEY)
            -32002 -> rateLimit
            -32003 -> ZureqException(Code.CREDITS_EXHAUSTED)
            -32602 -> ZureqException(Code.INVALID_ARGS)
            -32603 -> unavailable
            else -> ZureqException(Code.API_ERROR, message)
        }
    }
}
