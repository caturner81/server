/*
 * Copyright 2017 Pronghorn Technology LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.pronghorn.server

import tech.pronghorn.coroutines.core.CoroutineApplication
import tech.pronghorn.plugins.concurrentSet.ConcurrentSetPlugin
import tech.pronghorn.server.config.HttpServerConfig
import tech.pronghorn.server.requesthandlers.HttpRequestHandler
import tech.pronghorn.server.services.*
import java.net.InetSocketAddress

class HttpServer(val config: HttpServerConfig) : CoroutineApplication<HttpServerWorker>() {
    override val workers = spawnWorkers(config.workerCount)

    private fun spawnWorkers(workerCount: Int): Set<HttpServerWorker> {
        val workerSet = ConcurrentSetPlugin.get<HttpServerWorker>()
        for (x in 1..workerCount) {
            workerSet.add(HttpServerWorker(this, config))
        }
        return workerSet
    }

    constructor(address: InetSocketAddress) : this(HttpServerConfig(address))

    constructor(host: String,
                port: Int) : this(HttpServerConfig(InetSocketAddress(host, port)))

    override fun onStart() {
        if(config.reusePort){
            addService { worker -> MultiSocketManagerService(worker) }
        }
        else {
            val socketManager = SingleSocketManager(config.address, config.listenBacklog, RoundRobinConnectionDistributionStrategy(workers))
            addService { worker -> SingleSocketManagerService(worker, socketManager) }
        }

        logger.info { "Starting server with configuration: $config" }
    }

    override fun onShutdown() {
        logger.info { "Shutting down server at ${config.address}." }
    }

    fun registerUrlHandlerGenerators(handlers: Map<String, (HttpServerWorker) -> HttpRequestHandler>) {
        workers.forEach { worker ->
            worker.sendInterWorkerMessage(RegisterUrlHandlersMessage(handlers))
        }
    }

    fun registerUrlHandlerGenerator(url: String,
                                    handlerGenerator: (HttpServerWorker) -> HttpRequestHandler) {
        registerUrlHandlerGenerators(mapOf(url to handlerGenerator))
    }

    fun registerUrlHandler(url: String,
                           handler: HttpRequestHandler) {
        registerUrlHandlerGenerator(url, { handler })
    }
}
