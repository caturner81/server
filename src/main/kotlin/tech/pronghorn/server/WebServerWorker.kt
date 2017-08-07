package tech.pronghorn.server

import mu.KotlinLogging
import org.jctools.maps.NonBlockingHashSet
import tech.pronghorn.coroutines.core.CoroutineWorker
import tech.pronghorn.coroutines.service.Service
import tech.pronghorn.util.runAllIgnoringExceptions
import tech.pronghorn.server.bufferpools.ConnectionBufferPool
import tech.pronghorn.server.bufferpools.HandshakeBufferPool
import tech.pronghorn.server.config.WebServerConfig
import tech.pronghorn.server.config.WebsocketClientConfig
import tech.pronghorn.server.core.HttpRequestHandler
import tech.pronghorn.server.services.*
import java.nio.channels.SelectionKey
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

abstract class WebWorker : CoroutineWorker() {
//    protected val pendingConnections = NonBlockingHashSet<HttpConnection>()
    protected val allConnections = NonBlockingHashSet<HttpConnection>()

    val handshakeBufferPool = HandshakeBufferPool()
    val connectionBufferPool = ConnectionBufferPool()

    fun getPendingConnectionCount(): Int {
        throw Exception("No longer valid")
//        pendingConnections.size
    }

    fun getActiveConnectionCount(): Int {
        throw Exception("No longer valid")
//        allConnections.size - getPendingConnectionCount()
    }

    fun getConnectionCount(): Int = allConnections.size

    fun clearPendingConnection(connection: HttpConnection) {
        throw Exception("No longer valid")
//        pendingConnections.remove(connection)
    }

    fun addConnection(connection: HttpConnection) {
        assert(isSchedulerThread())
//        if (!connection.isHandshakeComplete) {
//            pendingConnections.add(connection)
//        }
        allConnections.add(connection)
    }

    fun removeConnection(connection: HttpConnection) {
        assert(isSchedulerThread())
        allConnections.remove(connection)
    }

    override fun onShutdown() {
        logger.info("Worker shutting down ${allConnections.size} connections")
        runAllIgnoringExceptions({ allConnections.forEach({ it.close("Server is shutting down.") }) })
    }

//    protected val handshakeTimeoutService = HandshakeTimeoutService(this, pendingConnections, handshakeTimeout)
//    protected val frameHandlerService = FrameHandlerService(this, frameHandler)
    protected val handshakeService = HandshakeService(this)
    protected val connectionReadService = ConnectionReadService(this)

    protected val handshakeServiceQueueWriter by lazy {
        handshakeService.getQueueWriter()
    }

    protected val connectionReadServiceQueueWriter by lazy {
        connectionReadService.getQueueWriter()
    }

    protected val commonServices = listOf(
        handshakeService,
//        handshakeTimeoutService,
//        frameHandlerService,
        connectionReadService
    )

    private var dateCache = ByteArray(0)
    private var latestDate = System.currentTimeMillis() % 1000
    private val gmt = ZoneId.of("GMT")

    fun getDateHeaderValue(): ByteArray {
        val now = System.currentTimeMillis()
        if (latestDate == now / 1000) {
            return dateCache
        } else {
            latestDate = now / 1000
            dateCache = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(gmt)).toByteArray(Charsets.US_ASCII)
            return dateCache
        }
    }

    override fun processKey(key: SelectionKey): Unit {
        val attachment = key.attachment()
        when (attachment) {
            is HttpConnection -> {
                if (key.isReadable) {
                    /*if (!attachment.isHandshakeComplete) {
                        // Handshakes must be completed first
                        if (!handshakeServiceQueueWriter.offer(attachment)) {
                            // TODO: handle this properly
                            throw Exception("HandshakeService full!")
                        }
                        attachment.updateInterestOps(0)
                    }
                    else {*/
                        // Connections that have completed their handshake are forwarded to the ConnectionReadService
                        if (!connectionReadServiceQueueWriter.offer(attachment)) {
                            // TODO: handle this properly
                            throw Exception("ConnectionReadService full!")
                        }
                        attachment.removeInterestOps(SelectionKey.OP_READ)
//                    }
                }
                else {
                    throw Exception("Unexpected selection op.")
                }
            }
            else -> {
                throw Exception("Unexpected selection selectionKey attachment : $attachment")
            }
        }
    }
}

class WebClientWorker(config: WebsocketClientConfig) : WebWorker() {
    override val logger = KotlinLogging.logger {}

    private val connectionCreationService = ClientConnectionCreationService(this, selector, config.randomGeneratorBuilder())
    private val connectionFinisherService = WebsocketConnectionFinisherService(this, selector, config.randomGeneratorBuilder())
    private val connectionFinisherWriter by lazy {
        connectionFinisherService.getQueueWriter()
    }

    override val services: List<Service> = listOf(
            connectionCreationService,
            connectionFinisherService
    ).plus(commonServices)

    override fun processKey(key: SelectionKey): Unit {
        var handled = false
        val attachment = key.attachment()
        when (attachment) {
            is HttpClientConnection -> {
                if(key.isConnectable) {
                    handled = true
                    if (!connectionFinisherWriter.offer(attachment)) {
                        // TODO: handle this properly
                        throw Exception("HandshakeService full!")
                    }
                    key.interestOps(0)
                }
            }
        }

        if(!handled){
            super.processKey(key)
        }
    }
}



class WebServerWorker(config: WebServerConfig,
                      handler: HttpRequestHandler) : WebWorker() {
    override val logger = KotlinLogging.logger {}

    private val connectionCreationService = ServerConnectionCreationService(this, selector)
    private val httpRequestHandlerService = HttpRequestHandlerService(this, handler)
    private val responseWriterService = ResponseWriterService(this)

    override val services: List<Service> = listOf(
            connectionCreationService,
            httpRequestHandlerService,
            responseWriterService
    ).plus(commonServices)
}
