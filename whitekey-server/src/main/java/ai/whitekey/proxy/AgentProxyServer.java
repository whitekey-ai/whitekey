package ai.whitekey.proxy;

import ai.whitekey.config.ProxyProperties;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class AgentProxyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyServer.class);
    private static final int MAX_INITIAL_LINE_LENGTH = (int) DataSize.ofKilobytes(8).toBytes();
    private static final int MAX_HEADER_SIZE = (int) DataSize.ofKilobytes(32).toBytes();
    private static final int MAX_CHUNK_SIZE = (int) DataSize.ofKilobytes(16).toBytes();
    private static final long SHUTDOWN_QUIET_PERIOD_SECONDS = 0L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;

    private final ProxyProperties properties;
    private final AgentProxyService proxyService;
    private final ApplicationContext applicationContext;

    private volatile Channel serverChannel;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile ExecutorService virtualExecutor;
    private volatile boolean running = false;

    public AgentProxyServer(ProxyProperties properties, AgentProxyService proxyService, ApplicationContext applicationContext) {
        this.properties = properties;
        this.proxyService = proxyService;
        this.applicationContext = applicationContext;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.enabled() || !(applicationContext instanceof WebServerApplicationContext)) {
            return;
        }
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            this.bossGroup = boss;
            this.workerGroup = worker;
            this.virtualExecutor = executor;

            ServerBootstrap bootstrap =
                    new ServerBootstrap()
                            .group(boss, worker)
                            .channel(NioServerSocketChannel.class)
                            .childOption(ChannelOption.TCP_NODELAY, true)
                            .childOption(ChannelOption.AUTO_READ, true)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel ch) {
                                            ch.pipeline()
                                                    .addLast(new HttpServerCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_CHUNK_SIZE))
                                                    .addLast(new HttpServerExpectContinueHandler())
                                                    .addLast(new ProxyInboundHandler(proxyService, virtualExecutor, properties.address(), getBoundPort()));
                                        }
                                    });

            InetSocketAddress bindAddress =
                    new InetSocketAddress(properties.address(), properties.port());
            Channel boundChannel = bootstrap.bind(bindAddress).syncUninterruptibly().channel();

            this.serverChannel = boundChannel;
            this.running = true;

            log.info(
                    "WhiteKey Agent Proxy listening on http://{}:{}",
                    properties.address(),
                    getBoundPort());
        } catch (RuntimeException error) {
            executor.close();
            worker.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            throw error;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (virtualExecutor != null) {
            virtualExecutor.close();
            virtualExecutor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int getBoundPort() {
        Channel current = this.serverChannel;
        return current != null && current.localAddress() instanceof InetSocketAddress addr
                ? addr.getPort()
                : -1;
    }
}
