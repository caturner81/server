package tech.pronghorn.server

import mu.KotlinLogging
import tech.pronghorn.coroutines.awaitable.InternalQueue
import tech.pronghorn.http.HttpExchange
import tech.pronghorn.http.HttpResponse
import tech.pronghorn.http.HttpResponses
import tech.pronghorn.http.protocol.HttpVersion
import tech.pronghorn.plugins.spscQueue.SpscQueuePlugin
import tech.pronghorn.server.bufferpools.PooledByteBuffer
import tech.pronghorn.server.core.StaticHttpRequestHandler
import tech.pronghorn.server.services.HttpRequestHandlerService
import tech.pronghorn.server.services.ResponseWriterService
import tech.pronghorn.util.runAllIgnoringExceptions
import tech.pronghorn.util.write
import tech.pronghorn.websocket.core.ParsedHttpRequest
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.*

const val spaceByte: Byte = 0x20
const val carriageReturnByte: Byte = 0xD
const val newLineByte: Byte = 0xA
const val carriageReturnNewLineShort: Short = 3338
const val colonSpaceShort: Short = 14880
const val colonByte: Byte = 0x3A
const val tabByte: Byte = 0x9
const val forwardSlashByte: Byte = 0x2F
const val asteriskByte: Byte = 0x2A
const val percentByte: Byte = 0x25
const val questionByte: Byte = 0x3F
const val atByte: Byte = 0x40

private val genericNotFoundHandler = StaticHttpRequestHandler(HttpResponses.NotFound())

open class HttpServerConnection(val worker: HttpServerWorker,
                                val socket: SocketChannel,
                                val selectionKey: SelectionKey) {
    companion object {
        private const val responseQueueSize = 64
    }

    private var isClosed = false
    private val logger = KotlinLogging.logger {}
    var isReadQueued = false

    private var handshakeBuffer: PooledByteBuffer? = null
    private var readBuffer: PooledByteBuffer? = null
    private var writeBuffer: PooledByteBuffer? = null

    private val readyResponseQueue = SpscQueuePlugin.get<HttpResponse>(responseQueueSize)
    private val readyResponses = InternalQueue(readyResponseQueue)
    private val readyResponseWriter = readyResponses.queueWriter
    private val readyResponseReader = readyResponses.queueReader

    private val connectionWriter by lazy(LazyThreadSafetyMode.NONE) {
        worker.requestInternalWriter<HttpServerConnection, ResponseWriterService>()
    }

    private val requestsReadyWriter by lazy(LazyThreadSafetyMode.NONE) {
        worker.requestInternalWriter<HttpServerConnection, HttpRequestHandlerService>()
    }


    init {
        selectionKey.attach(this)
        selectionKey.interestOps(SelectionKey.OP_READ)
    }

    fun releaseReadBuffer() {
        readBuffer?.release()
        readBuffer = null
    }

    fun releaseWriteBuffer() {
        writeBuffer?.release()
        writeBuffer = null
    }

    fun releaseHandshakeBuffer() {
        handshakeBuffer?.release()
        handshakeBuffer = null
    }

    fun getReadBuffer(): ByteBuffer {
        if (readBuffer == null) {
            readBuffer = worker.connectionBufferPool.getBuffer()
        }
        return readBuffer!!.buffer
    }

    fun getWriteBuffer(): ByteBuffer {
        if (writeBuffer == null) {
            writeBuffer = worker.connectionBufferPool.getBuffer()
        }
        return writeBuffer!!.buffer
    }

    private fun releaseBuffers() {
        releaseHandshakeBuffer()
        releaseReadBuffer()
        releaseWriteBuffer()
    }

    open fun close(reason: String? = null) {
        logger.debug { "Closing connection : $reason" }
        isReadQueued = false
        isClosed = true
        selectionKey.cancel()
        runAllIgnoringExceptions(
                { if (reason != null) socket.write(reason) },
                { socket.close() }
        )
        worker.removeConnection(this)
        releaseBuffers()
    }

    suspend fun appendResponse(response: HttpResponse) {
        val empty = readyResponseReader.isEmpty()
        // TODO: is this better than just the addAsync?
        if (!readyResponseWriter.offer(response)) {
            readyResponseWriter.addAsync(response)
        }

        if (empty) {
            // TODO: is this better than just the addAsync?
            if (!connectionWriter.offer(this)) {
                connectionWriter.addAsync(this)
            }
        }
    }

//    private val queuedRequestsQueue = SpscQueuePlugin.get<HttpExchange>(1024)
//    private val queuedRequests = InternalQueue(queuedRequestsQueue)
//    private val queuedRequestsWriter = queuedRequests.queueWriter
//    private val queuedRequestsReader = queuedRequests.queueReader

    val queuedRequests = ArrayDeque<HttpExchange>(1024)

    suspend fun queueRequest(exchange: HttpExchange) {
        val wasEmpty = queuedRequests.isEmpty()
        queuedRequests.add(exchange)
        if (wasEmpty) {
            if (requestsReadyWriter.offer(this)) {
                requestsReadyWriter.addAsync(this)
            }
        }

//        if (queuedRequestsReader.isEmpty()) {
//            requestsReadyWriter.addAsync(this)
//        }
//        queuedRequestsWriter.addAsync(exchange)
    }

    suspend fun handleRequests() {
        var request = queuedRequests.poll()
        //var request = queuedRequestsReader.poll()
        while (request != null) {
            val handler = worker.getHandler(request.requestUrl.getPathBytes()) ?: genericNotFoundHandler
            handler.handle(request)
            request = queuedRequests.poll()
//            request = queuedRequestsReader.poll()
        }
    }

    fun writeResponse(response: HttpResponse): Boolean {
        if (isClosed) {
            return true
        }

        val buffer = getWriteBuffer()
        renderResponse(buffer, response)
        buffer.flip()
        try {
            socket.write(buffer)
        }
        catch (ex: ClosedChannelException) {
            close()
            return true
        }
        if (!buffer.hasRemaining()) {
            releaseWriteBuffer()
            return true
        }
        else {
            return false
        }
    }

    fun writeResponses(): Boolean {
        val buffer = getWriteBuffer()
        if (!buffer.hasRemaining()) {
            logger.error("FULL BUFFER")
            TODO()
            val wrote = socket.write(buffer)
            if (wrote < 0) {
                logger.error("DISCONNECTED DURING WRITE")
                return true
            }
            else if (wrote == 0) {
                logger.error("WROTE 0 BYTES")
                return false
            }
            else {
                logger.info("Wrote $wrote bytes")
                if (!buffer.hasRemaining()) {
                    return true
                }
            }
        }
        else {
            var response = readyResponseReader.poll()
            while (buffer.hasRemaining() && response != null) {
                renderResponse(buffer, response)
                response = readyResponseReader.poll()
            }
            buffer.flip()
            val wrote = socket.write(buffer)
            logger.debug { "Wrote $wrote bytes to socket." }
            //logger.error("buffer after write: position: ${buffer.position()}, limit: ${buffer.limit()}, remaining: ${buffer.remaining()}, hasRemaining: ${buffer.hasRemaining()}")
//            println("Wrote $wrote bytes")
            if (!buffer.hasRemaining()) {
                releaseWriteBuffer()
                return true
            }
            else {
//                logger.error("INCOMPLETE WRITE")
                return false
//                System.exit(1)
            }
        }
    }

    fun renderResponse(buffer: ByteBuffer,
                       response: HttpResponse): Boolean {
        val size = response.getOutputSize() + worker.commonHeaderSize

        if (buffer.remaining() < size) {
            return false
        }

        buffer.put(HttpVersion.HTTP11.bytes)
        buffer.put(spaceByte)
        buffer.put(response.code.bytes)
        buffer.putShort(carriageReturnNewLineShort)

        buffer.put(worker.getCommonHeaders())

        response.writeHeaders(buffer)

        buffer.putShort(carriageReturnNewLineShort)

        response.writeBody(buffer)
        return true
    }

//    protected fun finalize() {
//        if (socket.isOpen || socket.isConnected) {
//            println("FAILED TO CLOSE SOCKET HttpConnection")
//            println("${socket.isOpen} / ${socket.isConnected}")
//            System.exit(1)
//        }
//        else if (readBuffer != null || writeBuffer != null || handshakeBuffer != null) {
//            println("FAILED TO RELEASE BUFFER HttpConnection")
//            System.exit(1)
//        }
//    }
}

/*
class HttpClientConnection(worker: HttpClientWorker,
                           socket: SocketChannel,
                           selectionKey: SelectionKey,
                           private val readyPromise: InternalFuture.InternalPromise<HttpClientConnection>) : HttpConnection(worker, socket, selectionKey) {
    override val shouldSendMasked: Boolean = false
    override val requiresMasked: Boolean = true
    private val sendQueue = SpscQueuePlugin.get<WebsocketFrame>(1024)

    override fun close(reason: String?) {
        if (!isHandshakeComplete) {
            readyPromise.completeExceptionally(ConnectException(reason))
        }
        super.close(reason)
    }

//    fun getFrameWriter(): QueueWriter<WebsocketFrame> {
//        TODO()
    //ExternalQueueWriter<WebsocketFrame>(sendQueue,
//    }

    suspend fun send(frame: WebsocketFrame): Unit {
        if (!sendQueue.offer(frame)) {
            TODO()
        }
    }

    fun sendHandshake(handshaker: WebsocketHandshaker,
                      randomGenerator: Random) {
        val address = socket.remoteAddress
        if (address is InetSocketAddress) {
            val keyBytes = ByteArray(16)
            randomGenerator.nextBytes(keyBytes)
            val handshake = handshaker.getClientHandshakeRequest(address.hostName, keyBytes)
            try {
                socket.write(handshake)
            }
            catch (e: IOException) {
                close("Unexpected error replying to initial handshake.")
            }
        }
        else {
            throw Exception("Unexpected socket address, should be InetSocketAddress")
        }
    }

    override fun handleHandshakeRequest(request: ParsedHttpRequest,
                                        handshaker: WebsocketHandshaker): Boolean {
        // TODO: validate the server sent some sensible handshake response
        readyPromise.complete(this)
        return true
    }
}
*/

internal class DummyConnection(worker: HttpServerWorker,
                               socket: SocketChannel,
                               selectionKey: SelectionKey) : HttpServerConnection(worker, socket, selectionKey) {

//    override fun handleHandshakeRequest(request: ParsedHttpRequest, handshaker: WebsocketHandshaker): Boolean = false
//    override val shouldSendMasked: Boolean = false
//    override val requiresMasked: Boolean = false

}
