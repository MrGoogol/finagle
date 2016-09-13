package com.twitter.finagle.netty3

import com.twitter.conversions.time._
import com.twitter.finagle.Stack
import com.twitter.finagle.client.Transporter.Credentials
import com.twitter.finagle.client.{LatencyCompensation, Transporter}
import com.twitter.finagle.httpproxy.HttpConnectHandler
import com.twitter.finagle.netty3.channel.{
  ChannelRequestStatsHandler, ChannelStatsHandler, IdleChannelHandler}
import com.twitter.finagle.netty3.socks.SocksConnectHandler
import com.twitter.finagle.netty3.ssl.SslConnectHandler
import com.twitter.finagle.netty3.transport.ChannelTransport
import com.twitter.finagle.param.Label
import com.twitter.finagle.ssl.Engine
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration
import java.net.{InetSocketAddress, SocketAddress}
import javax.net.ssl.{SSLEngine, SSLEngineResult, SSLSession}
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.IdleStateHandler
import org.junit.runner.RunWith
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

@RunWith(classOf[JUnitRunner])
class Netty3TransporterTest extends FunSuite with MockitoSugar with Eventually {

  private[this] val unresolvedAddr = InetSocketAddress.createUnresolved("supdog", 0)
  private[this] val loopbackSockAddr = new InetSocketAddress("127.0.0.1", 9999)
  private[this] val linkLocalSockAddr = new InetSocketAddress("169.254.0.1", 9999)
  private[this] val routableSockAddr = new InetSocketAddress("8.8.8.8", 9999)

  private[this] def findHandlerInPipeline[T <: ChannelHandler : ClassTag](
    pipeline: ChannelPipeline
  ): Option[T] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass

    pipeline.toMap.asScala.values.find {
      case h if clazz.isInstance(h) => true
      case _ => false
    }.map(_.asInstanceOf[T])
  }

  private[this] def makeTransporterPipeline(
    params: Stack.Params,
    addr: SocketAddress = new InetSocketAddress(0),
    statsReceiver: StatsReceiver = NullStatsReceiver
  ): ChannelPipeline = {
    val pipeline = Channels.pipeline()
    val pipelineFactory = Channels.pipelineFactory(pipeline)
    val transporter = Netty3Transporter.make(pipelineFactory, params)

    transporter.newPipeline(addr, statsReceiver)
  }

  test("create a Netty3Transporter instance based on Stack params") {
    val inputParams =
      Stack.Params.empty +
        Label("test") +
        Netty3Transporter.TransportFactory.param.default +
        Transporter.ConnectTimeout(1.seconds) +
        LatencyCompensation.Compensation(12.millis) +
        Transporter.TLSHostname(Some("tls.host")) +
        Transporter.HttpProxy(Some(new InetSocketAddress(0)), Some(Credentials("user", "pw"))) +
        Transporter.SocksProxy(Some(new InetSocketAddress(0)), Some(("user", "pw"))) +
        Transport.BufferSizes(Some(100), Some(200)) +
        Transport.TLSClientEngine.param.default +
        Transport.Liveness(1.seconds, 2.seconds, Some(true)) +
        Transport.Verbose(true)

    val pipelineFactory = Channels.pipelineFactory(Channels.pipeline())
    val transporter = Netty3Transporter.make(pipelineFactory, inputParams)
    assert(transporter.name == inputParams[Label].label)
    assert(transporter.pipelineFactory == pipelineFactory)
    assert(
      transporter.tlsConfig ==
        inputParams[Transport.TLSClientEngine].e.map(
          Netty3TransporterTLSConfig(_, inputParams[Transporter.TLSHostname].hostname)))
    assert(transporter.httpProxy == inputParams[Transporter.HttpProxy].sa)
    assert(transporter.httpProxyCredentials == inputParams[Transporter.HttpProxy].credentials)
    assert(transporter.socksProxy == inputParams[Transporter.SocksProxy].sa)
    assert(transporter.socksUsernameAndPassword == inputParams[Transporter.SocksProxy].credentials)
    assert(transporter.channelReaderTimeout == inputParams[Transport.Liveness].readTimeout)
    assert(transporter.channelWriterTimeout == inputParams[Transport.Liveness].writeTimeout)
    assert(transporter.channelOptions.get("sendBufferSize") == inputParams[Transport.BufferSizes].send)
    assert(transporter.channelOptions.get("receiveBufferSize") == inputParams[Transport.BufferSizes].recv)
    assert(transporter.channelOptions.get("keepAlive") == inputParams[Transport.Liveness].keepAlive)
    assert(transporter.channelOptions.get("connectTimeoutMillis").get ==
      inputParams[Transporter.ConnectTimeout].howlong.inMilliseconds +
      inputParams[LatencyCompensation.Compensation].howlong.inMilliseconds)
    assert(transporter.channelSnooper.nonEmpty == inputParams[Transport.Verbose].enabled)
  }

  test("newPipeline handles unresolved InetSocketAddresses") {
    val pipeline = Channels.pipeline()
    val pipelineFactory = new ChannelPipelineFactory {
      override def getPipeline(): ChannelPipeline = pipeline
    }

    val params = Stack.Params.empty + Label("name") +
      Transporter.SocksProxy(Some(InetSocketAddress.createUnresolved("anAddr", 0)), None)

    val transporter = Netty3Transporter.make(pipelineFactory, params)
    val pl = transporter.newPipeline(unresolvedAddr, NullStatsReceiver)
    assert(pl == pipeline) // mainly just checking that we don't NPE anymore
  }

  test("IdleStateHandler is not added by default") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleStateHandler](pipeline)
    assert(idleHandler.isEmpty)
  }

  test("IdleStateHandler is not added when the reader and writer timeouts are not finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(Duration.Top, Duration.Top, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleStateHandler](pipeline)
    assert(idleHandler.isEmpty)
  }

  test("IdleStateHandler is added when the reader timeout is finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(1.second, Duration.Top, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleStateHandler](pipeline)
    assert(idleHandler.nonEmpty)
  }

  test("IdleStateHandler is added when the writer timeout is finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(Duration.Top, 1.second, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleStateHandler](pipeline)
    assert(idleHandler.nonEmpty)
  }

  test("should track connections with channelStatsHandler on different connections") {
    val sr = new InMemoryStatsReceiver
    def hasConnections(scope: String, num: Int) {
      assert(sr.gauges(Seq(scope, "connections"))() == num)
    }

    val firstPipeline = Channels.pipeline()
    val secondPipeline = Channels.pipeline()
    val pipelineFactory = new ChannelPipelineFactory {
      override def getPipeline(): ChannelPipeline = firstPipeline
    }
    val transporter = Netty3Transporter.make(pipelineFactory,
      Stack.Params.empty + Label("name"))

    val firstHandler = transporter.channelStatsHandler(sr.scope("first"))
    val secondHandler = transporter.channelStatsHandler(sr.scope("second"))

    firstPipeline.addFirst("channelStatsHandler", firstHandler)
    secondPipeline.addFirst("channelStatsHandler", secondHandler)

    val firstChannel = Netty3Transporter.channelFactory.newChannel(firstPipeline)

    hasConnections("first", 1)
    hasConnections("second", 0)

    val secondChannel = Netty3Transporter.channelFactory.newChannel(secondPipeline)
    Channels.close(firstChannel)

    eventually { hasConnections("first", 0) }
    hasConnections("second", 1)

    Channels.close(secondChannel)
    eventually { hasConnections("second", 0) }
  }

  test("IdleChannelHandler is not added by default") {
    // The statsReceiver used is the one passed in to newPipeline.
    // It is not determined by the Stats params.
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleChannelHandler](pipeline)
    assert(idleHandler.isEmpty)
  }

  test("IdleChannelHandler is not added when the reader and writer timeouts are not finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(Duration.Top, Duration.Top, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleChannelHandler](pipeline)
    assert(idleHandler.isEmpty)
  }

  test("IdleChannelHandler is added when the reader timeout is finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(1.second, Duration.Top, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleChannelHandler](pipeline)
    assert(idleHandler.nonEmpty)
  }

  test("IdleChannelHandler is added when the writer timeout is finite") {
    val params = Stack.Params.empty + Label("name") +
      Transport.Liveness(Duration.Top, 1.second, None)
    val pipeline = makeTransporterPipeline(params)
    val idleHandler = findHandlerInPipeline[IdleChannelHandler](pipeline)
    assert(idleHandler.nonEmpty)
  }

  test("ChannelStatsHandler is added by default") {
    // The statsReceiver used is the one passed in to newPipeline.
    // It is not determined by the Stats params.
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params)
    val statsHandler = findHandlerInPipeline[ChannelStatsHandler](pipeline)
    assert(statsHandler.nonEmpty)
  }

  test("ChannelRequestStatsHandler is added by default") {
    // The statsReceiver used is the one passed in to newPipeline.
    // It is not determined by the Stats params.
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params)
    val statsHandler = findHandlerInPipeline[ChannelRequestStatsHandler](pipeline)
    assert(statsHandler.nonEmpty)
  }

  test("SocksConnectHandler is not added if no proxy address is given") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params, loopbackSockAddr)
    val socksConnectHandler = findHandlerInPipeline[SocksConnectHandler](pipeline)
    assert(socksConnectHandler.isEmpty)
  }

  test("SocksConnectHandler is not added if proxy address is given but address isLoopback") {
    val params = Stack.Params.empty + Label("name") +
      Transporter.SocksProxy(Some(loopbackSockAddr), None)
    val pipeline = makeTransporterPipeline(params, loopbackSockAddr)
    val socksConnectHandler = findHandlerInPipeline[SocksConnectHandler](pipeline)
    assert(socksConnectHandler.isEmpty)
  }

  test("SocksConnectHandler is not added if proxy address is given but address isLinkLocal") {
    val params = Stack.Params.empty + Label("name") +
      Transporter.SocksProxy(Some(loopbackSockAddr), None)
    val pipeline = makeTransporterPipeline(params, linkLocalSockAddr)
    val socksConnectHandler = findHandlerInPipeline[SocksConnectHandler](pipeline)
    assert(socksConnectHandler.isEmpty)
  }

  test("SocksConnectHandler is added if proxy address is given and addr is routable") {
    val params = Stack.Params.empty + Label("name") +
      Transporter.SocksProxy(Some(loopbackSockAddr), None)
    val pipeline = makeTransporterPipeline(params, routableSockAddr)
    val socksConnectHandler = findHandlerInPipeline[SocksConnectHandler](pipeline)
    assert(socksConnectHandler.nonEmpty)
  }

  test("ChannelSnooper is not added by default") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val channelSnooper = findHandlerInPipeline[ChannelSnooper](pipeline)
    assert(channelSnooper.isEmpty)
  }

  test("ChannelSnooper is added when Verbose param is true") {
    val params = Stack.Params.empty + Label("name") + Transport.Verbose(true)
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val channelSnooper = findHandlerInPipeline[ChannelSnooper](pipeline)
    assert(channelSnooper.nonEmpty)
  }

  test("HttpConnectHandler is not added by default") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val httpConnectHandler = findHandlerInPipeline[HttpConnectHandler](pipeline)
    assert(httpConnectHandler.isEmpty)
  }

  test("HttpConnectHandler is not added when HttpProxy is configured and transporter uses an unresolved address") {
    val params = Stack.Params.empty + Label("name") +
      Transporter.HttpProxy(Some(unresolvedAddr), None)
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val httpConnectHandler = findHandlerInPipeline[HttpConnectHandler](pipeline)
    assert(httpConnectHandler.isEmpty)
  }

  test("HttpConnectHandler is added when HttpProxy is configured and transporter uses a resolved address") {
    val params = Stack.Params.empty + Label("name") +
      Transporter.HttpProxy(Some(unresolvedAddr), None)
    val pipeline = makeTransporterPipeline(params, linkLocalSockAddr)
    val httpConnectHandler = findHandlerInPipeline[HttpConnectHandler](pipeline)
    assert(httpConnectHandler.nonEmpty)
  }

  test("SslConnectHandler is not added by default") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val sslHandler = findHandlerInPipeline[SslConnectHandler](pipeline)
    assert(sslHandler.isEmpty)
  }

  test("SslConnectHandler is added when the TLS client engine param is configured") {
    val engine = mock[SSLEngine]
    val params = Stack.Params.empty + Label("name") +
      Transport.TLSClientEngine(Some(Function.const(new Engine(engine))))
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val sslHandler = findHandlerInPipeline[SslConnectHandler](pipeline)
    assert(sslHandler.nonEmpty)
  }

  test("SslHandler is not added by default") {
    val params = Stack.Params.empty + Label("name")
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val sslHandler = findHandlerInPipeline[SslHandler](pipeline)
    assert(sslHandler.isEmpty)
  }

  test("SslHandler is added when the TLS client engine param is configured") {
    val engine = mock[SSLEngine]
    val params = Stack.Params.empty + Label("name") +
      Transport.TLSClientEngine(Some(Function.const(new Engine(engine))))
    val pipeline = makeTransporterPipeline(params, unresolvedAddr)
    val sslHandler = findHandlerInPipeline[SslHandler](pipeline)
    assert(sslHandler.nonEmpty)
  }

  test("SslHandler should close the channel if the remote peer closed TLS session") {
    val result = new SSLEngineResult(
      SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NEED_UNWRAP, 0, 0
    )

    val session = mock[SSLSession]
    val engine = mock[SSLEngine]
    when(engine.getSession) thenReturn session
    when(session.getApplicationBufferSize) thenReturn 1024
    when(engine.unwrap(any[java.nio.ByteBuffer], any[java.nio.ByteBuffer])) thenReturn result
    when(engine.getUseClientMode) thenReturn true
    when(engine.isInboundDone) thenReturn true
    when(engine.getEnableSessionCreation) thenReturn true

    val params = Stack.Params.empty + Label("tls-enabled") +
      Transport.TLSClientEngine(Some(Function.const(new Engine(engine)))) +
      Transporter.TLSHostname(Some("localhost"))

    val pipelineFactory = Channels.pipelineFactory(Channels.pipeline())
    val transporter = Netty3Transporter.make(pipelineFactory, params)

    // 21 - alert message, 3 - SSL3 major version,
    // 0 - SSL3 minor version, 0 1 - package length, 0 - close_notify
    val cb = ChannelBuffers.copiedBuffer(Array[Byte](21, 3, 0, 0, 1, 0))
    cb.readerIndex(0)
    cb.writerIndex(6)

    val pipeline = transporter.newPipeline(null, NullStatsReceiver)
    val channel = transporter.newChannel(pipeline)
    new ChannelTransport[Any, Any](channel) // adds itself to the channel's pipeline
    val closeNotify = new UpstreamMessageEvent(channel, cb, null)

    assert(channel.isOpen)

    pipeline.sendUpstream(closeNotify)

    // We wait for I/O thread closing the channel
    assert(channel.getCloseFuture.await(3000)) // timeout 3s
  }

}
