package org.infinispan.server.hotrod.transport

import java.util.concurrent.Executors

import io.netty.channel.{Channel, ChannelOutboundHandler}
import io.netty.util.concurrent.DefaultThreadFactory
import org.infinispan.server.core.ExecutionHandler
import org.infinispan.server.core.transport.{NettyChannelInitializer, NettyTransport}
import org.infinispan.server.hotrod.{AuthenticationHandler, ContextHandler, HotRodExceptionHandler, HotRodServer, _}

/**
  * HotRod specific channel initializer
  *
  * @author wburns
  * @since 9.0
  */
class HotRodChannelInitializer(val server: HotRodServer, transport: => NettyTransport,
                               val encoder: ChannelOutboundHandler, threadNamePrefix: String)
      extends NettyChannelInitializer(server, transport, encoder) {

   val executionHandler = new ExecutionHandler(Executors.newFixedThreadPool(server.getConfiguration.workerThreads(),
      new DefaultThreadFactory(threadNamePrefix + "ServerHandler")))

   override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      val authHandler = if (server.getConfiguration.authentication().enabled()) new AuthenticationHandler(server) else null
      if (authHandler != null) {
         // TODO: We need to move this to the executor thread as well
         ch.pipeline().addLast("authentication-1", authHandler)
      }
      ch.pipeline.addLast("local-handler", new LocalContextHandler(transport))

      // All inbound handlers after this are performed in the executor
      ch.pipeline.addLast("execution-handler", executionHandler)

      ch.pipeline.addLast("handler", new ContextHandler(server, transport))
      ch.pipeline.addLast("exception", new HotRodExceptionHandler)

   }
}