package com.example.glasstube

import android.content.Context
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object Errors {
    fun text(ctx: Context, e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is ReCaptchaException -> return ctx.getString(R.string.error_blocked)
                is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                    return ctx.getString(R.string.error_network)
            }
            t = t.cause
        }
        return ctx.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
    }
}
