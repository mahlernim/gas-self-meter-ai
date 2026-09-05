package dev.mahlernim.gasselfmeter

import okhttp3.RequestBody
import okio.BufferedSink

/** Prevent HTTP follow-ups from replaying a mutation, including 503 Retry-After responses. */
internal fun RequestBody.oneShot(): RequestBody {
    val body = this
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
}
