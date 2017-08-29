package tech.pronghorn.http

import tech.pronghorn.http.protocol.ContentType
import tech.pronghorn.http.protocol.HttpResponseCode
import tech.pronghorn.http.protocol.HttpResponseHeader

private val emptyBytes = ByteArray(0)
private val noContentHeaders = mutableMapOf<HttpResponseHeader, HttpResponseHeaderValue<*>>(
        HttpResponseHeader.ContentLength to HttpResponseHeaderValue.valueOf(0)
)

object HttpResponses {
    class OK(override val body: ByteArray) : HttpResponse(HttpResponseCode.OK) {
        override val headers = mutableMapOf<HttpResponseHeader, HttpResponseHeaderValue<*>>(
                HttpResponseHeader.ContentLength to HttpResponseHeaderValue.valueOf(body.size)
        )

        constructor(body: ByteArray,
                    contentType: ContentType) : this(body) {
            addHeader(HttpResponseHeader.ContentType, contentType.bytes)
        }
    }

    class NoContent : HttpResponse(HttpResponseCode.NoContent) {
        override val body = emptyBytes
        override val headers = noContentHeaders
    }

    class NotFound(override val body: ByteArray = emptyBytes) : HttpResponse(HttpResponseCode.NotFound) {
        override val headers = mutableMapOf<HttpResponseHeader, HttpResponseHeaderValue<*>>(
                HttpResponseHeader.ContentLength to HttpResponseHeaderValue.valueOf(body.size)
        )

        constructor(body: ByteArray,
                    contentType: ContentType) : this(body) {
            addHeader(HttpResponseHeader.ContentType, contentType.bytes)
        }
    }
}
