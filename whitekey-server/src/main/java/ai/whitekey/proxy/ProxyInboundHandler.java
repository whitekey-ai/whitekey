package ai.whitekey.proxy;

import ai.whitekey.proxy.AgentProxyService.ProxyResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import org.springframework.util.unit.DataSize;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

class ProxyInboundHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger log = LoggerFactory.getLogger(ProxyInboundHandler.class);
    private static final int PIPE_BUFFER_SIZE = (int) DataSize.ofKilobytes(64).toBytes();

    public static final String ERROR_MALFORMED_REQUEST = "Malformed HTTP request";
    public static final String ERROR_PIPELINING_UNSUPPORTED = "pipelining_unsupported";
    public static final String MSG_PIPELINING_UNSUPPORTED = "Concurrent pipelined requests on the same connection are not supported";
    public static final String ERROR_TEMP_FILE = "temp_file_error";
    public static final String MSG_TEMP_FILE = "Failed creating temp file";
    public static final String ERROR_INTERNAL = "internal_error";
    public static final String MSG_INTERNAL_ERROR = "Internal proxy error";
    public static final String TEMP_FILE_PREFIX = "whitekey-body-";
    public static final String TEMP_FILE_SUFFIX = ".tmp";

    private enum State {
        IDLE,
        DISCARDING,
        STREAMING,
        SPOOLING,
        TUNNELING
    }

    private final AgentProxyService proxyService;
    private final ExecutorService virtualExecutor;
    private final String defaultAddress;
    private final int boundPort;

    private State state = State.IDLE;
    private String currentMethod;
    private boolean keepAlive = false;
    private String proxyBaseUrl;
    private AgentProxyService.PreparedRequest preparedRequest;

    private PipedOutputStream pipedOut;

    private Path tempFile;
    private OutputStream spoolOut;

    private CompletableFuture<Void> clientDisconnected;
    private ChannelFutureListener closeListener;

    ProxyInboundHandler(
            AgentProxyService proxyService,
            ExecutorService virtualExecutor,
            String defaultAddress,
            int boundPort) {
        this.proxyService = proxyService;
        this.virtualExecutor = virtualExecutor;
        this.defaultAddress = defaultAddress;
        this.boundPort = boundPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest req) {
            handleHttpRequest(ctx, req);
        } else if (msg instanceof io.netty.handler.codec.http.HttpContent content) {
            handleHttpContent(ctx, content);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            ProxyResponseWriter.write(
                    ctx,
                    "GET",
                    ProxyResponse.jsonError(HttpStatus.BAD_REQUEST.value(), AgentProxyService.ERROR_INVALID_REQUEST, ERROR_MALFORMED_REQUEST),
                    false);
            state = State.DISCARDING;
            return;
        }
        if (state != State.IDLE) {
            ProxyResponseWriter.write(
                    ctx,
                    req.method().name(),
                    ProxyResponse.jsonError(
                            HttpStatus.BAD_REQUEST.value(),
                            ERROR_PIPELINING_UNSUPPORTED,
                            MSG_PIPELINING_UNSUPPORTED),
                    false);
            state = State.DISCARDING;
            return;
        }

        currentMethod = req.method().name();
        keepAlive = HttpUtil.isKeepAlive(req);

        String rawUri = req.uri();
        int hashIdx = rawUri.indexOf('#');
        String withoutFragment = hashIdx >= 0 ? rawUri.substring(0, hashIdx) : rawUri;
        int queryIdx = withoutFragment.indexOf('?');
        String rawPath =
                queryIdx >= 0 ? withoutFragment.substring(0, queryIdx) : withoutFragment;
        String rawQuery = queryIdx >= 0 ? withoutFragment.substring(queryIdx + 1) : null;

        HttpHeaders requestHeaders = new HttpHeaders();
        for (String name : req.headers().names()) {
            requestHeaders.put(name, List.copyOf(req.headers().getAll(name)));
        }

        String hostHeader = req.headers().get(HttpHeaderNames.HOST);
        proxyBaseUrl =
                hostHeader != null && !hostHeader.isBlank()
                        ? "http://" + hostHeader
                        : "http://" + defaultAddress + ":" + boundPort;

        clientDisconnected = new CompletableFuture<>();
        closeListener = future -> clientDisconnected.complete(null);
        ctx.channel().closeFuture().addListener(closeListener);

        if (HttpMethod.CONNECT.name().equalsIgnoreCase(currentMethod)) {
            handleConnectRequest(ctx, rawUri, requestHeaders);
            return;
        }

        preparedRequest = proxyService.prepare(currentMethod, rawPath, rawQuery, requestHeaders);

        if (preparedRequest.immediateError() != null) {
            ProxyResponseWriter.write(ctx, currentMethod, preparedRequest.immediateError(), false);
            state = State.DISCARDING;
            return;
        }

        if (preparedRequest.needsBody()) {
            try {
                tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
                spoolOut = Files.newOutputStream(tempFile);
                state = State.SPOOLING;
            } catch (IOException e) {
                ProxyResponseWriter.write(
                        ctx,
                        currentMethod,
                        ProxyResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_TEMP_FILE, MSG_TEMP_FILE),
                        false);
                state = State.DISCARDING;
            }
        } else {
            try {
                pipedOut = new PipedOutputStream();
                PipedInputStream pis = new PipedInputStream(pipedOut, PIPE_BUFFER_SIZE);
                state = State.STREAMING;

                virtualExecutor.execute(
                        () -> {
                            ProxyResponse response;
                            try {
                                response =
                                        proxyService.handleStream(
                                                preparedRequest,
                                                proxyBaseUrl,
                                                pis,
                                                clientDisconnected);
                            } catch (Exception unexpected) {
                                log.error("Unexpected error in streaming proxy", unexpected);
                                response =
                                        ProxyResponse.jsonError(
                                                HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_INTERNAL, MSG_INTERNAL_ERROR);
                            }
                            sendResponseFromVirtualThread(
                                    ctx, currentMethod, response, keepAlive);
                        });
            } catch (IOException e) {
                ProxyResponseWriter.write(
                        ctx,
                        currentMethod,
                        ProxyResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "stream_init_error", "Failed initializing pipe"),
                        false);
                state = State.DISCARDING;
            }
        }
    }

    private void handleConnectRequest(
            ChannelHandlerContext ctx, String rawUri, HttpHeaders requestHeaders) {
        preparedRequest = proxyService.prepareConnect(rawUri, requestHeaders);
        if (preparedRequest.immediateError() != null) {
            ProxyResponseWriter.write(ctx, currentMethod, preparedRequest.immediateError(), false);
            state = State.DISCARDING;
            return;
        }

        state = State.TUNNELING;
        virtualExecutor.execute(
                () -> {
                    ProxyResponse error;
                    try {
                        error = proxyService.authorizeTunnel(preparedRequest, clientDisconnected);
                    } catch (Exception unexpected) {
                        log.error("Unexpected error authorizing CONNECT tunnel", unexpected);
                        error =
                                ProxyResponse.jsonError(
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_INTERNAL, MSG_INTERNAL_ERROR);
                    }
                    if (error != null) {
                        sendResponseFromVirtualThread(ctx, currentMethod, error, false);
                        return;
                    }
                    ProxyTarget target = preparedRequest.target();
                    ctx.executor().execute(() -> openConnectTunnel(ctx, target.host(), target.port()));
                });
    }

    private void openConnectTunnel(ChannelHandlerContext ctx, String host, int port) {
        if (!ctx.channel().isActive()) {
            cleanup(ctx);
            return;
        }
        int connectTimeoutMs = (int) AgentProxyService.UPSTREAM_CONNECT_TIMEOUT.toMillis();
        Bootstrap bootstrap =
                new Bootstrap()
                        .group(ctx.channel().eventLoop())
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .option(ChannelOption.AUTO_READ, false)
                        .handler(
                                new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel ch) {}
                                });

        bootstrap.connect(host, port).addListener(
                (ChannelFuture connectFuture) -> {
                    if (!connectFuture.isSuccess()) {
                        if (ctx.channel().isActive()) {
                            ProxyResponse fail =
                                    ProxyResponse.jsonError(
                                            HttpStatus.BAD_GATEWAY.value(),
                                            AgentProxyService.ERROR_UPSTREAM_CONNECT_FAILED,
                                            "Could not connect to upstream host");
                            ProxyResponseWriter.write(ctx, HttpMethod.CONNECT.name(), fail, false);
                        }
                        cleanup(ctx);
                        return;
                    }

                    Channel outbound = connectFuture.channel();
                    if (!ctx.channel().isActive()) {
                        outbound.close();
                        cleanup(ctx);
                        return;
                    }

                    removeCloseListener(ctx);

                    ctx.channel().config().setAutoRead(false);
                    DefaultFullHttpResponse established =
                            new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    new HttpResponseStatus(200, "Connection Established"));
                    ctx.writeAndFlush(established).addListener(
                            (ChannelFuture writeFuture) -> {
                                if (!writeFuture.isSuccess() || !outbound.isActive() || !ctx.channel().isActive()) {
                                    outbound.close();
                                    ctx.close();
                                    return;
                                }
                                ChannelPipeline pipeline = ctx.pipeline();
                                if (pipeline.get(HttpServerCodec.class) != null) {
                                    pipeline.remove(HttpServerCodec.class);
                                }
                                if (pipeline.get(HttpServerExpectContinueHandler.class) != null) {
                                    pipeline.remove(HttpServerExpectContinueHandler.class);
                                }
                                if (pipeline.get(ProxyInboundHandler.class) != null) {
                                    pipeline.remove(this);
                                }
                                pipeline.addLast(new TunnelRelayHandler(outbound));
                                outbound.pipeline().addLast(new TunnelRelayHandler(ctx.channel()));

                                outbound.config().setAutoRead(true);
                                ctx.channel().config().setAutoRead(true);
                            });
                });
    }

    private void handleHttpContent(
            ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpContent content) {
        if (state == State.DISCARDING || state == State.TUNNELING) {
            return;
        }

        ByteBuf data = content.content();
        boolean hasBytes = data.isReadable();

        if (state == State.SPOOLING) {
            if (hasBytes && spoolOut != null) {
                try {
                    data.readBytes(spoolOut, data.readableBytes());
                } catch (IOException e) {
                    cleanup(ctx);
                    ProxyResponseWriter.write(
                            ctx,
                            currentMethod,
                            ProxyResponse.jsonError(
                                    HttpStatus.INTERNAL_SERVER_ERROR.value(), "spool_error", "Failed writing to temp file"),
                            false);
                    state = State.DISCARDING;
                    return;
                }
            }
            if (content instanceof LastHttpContent) {
                closeQuietly(spoolOut);
                spoolOut = null;

                Path file = tempFile;
                tempFile = null;
                virtualExecutor.execute(
                        () -> {
                            ProxyResponse response;
                            try {
                                response =
                                        proxyService.handleWithBodyFile(
                                                preparedRequest,
                                                proxyBaseUrl,
                                                file,
                                                clientDisconnected);
                            } catch (Exception unexpected) {
                                log.error("Unexpected error in spooled body execution", unexpected);
                                response =
                                        ProxyResponse.jsonError(
                                                HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_INTERNAL, MSG_INTERNAL_ERROR);
                            } finally {
                                deleteQuietly(file);
                            }
                            sendResponseFromVirtualThread(
                                    ctx, currentMethod, response, keepAlive);
                        });
            }
        } else if (state == State.STREAMING) {
            if (hasBytes && pipedOut != null) {
                try {
                    data.readBytes(pipedOut, data.readableBytes());
                } catch (IOException e) {
                    cleanup(ctx);
                    state = State.DISCARDING;
                    return;
                }
            }
            if (content instanceof LastHttpContent) {
                closeQuietly(pipedOut);
                pipedOut = null;
            }
        }
    }

    private void cleanup(ChannelHandlerContext ctx) {
        state = State.IDLE;
        currentMethod = null;
        preparedRequest = null;
        closeQuietly(pipedOut);
        pipedOut = null;
        closeQuietly(spoolOut);
        spoolOut = null;
        deleteQuietly(tempFile);
        tempFile = null;
        removeCloseListener(ctx);
    }

    private void removeCloseListener(ChannelHandlerContext ctx) {
        if (closeListener != null) {
            ctx.channel().closeFuture().removeListener(closeListener);
            closeListener = null;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cleanup(ctx);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Agent client connection closed with error: {}", cause.getMessage());
        cleanup(ctx);
        ctx.close();
    }

    private void sendResponseFromVirtualThread(
            ChannelHandlerContext ctx,
            String requestMethod,
            ProxyResponse response,
            boolean keepAlive) {
        try {
            ProxyResponseWriter.write(ctx, requestMethod, response, keepAlive);
        } finally {
            ctx.executor().execute(() -> cleanup(ctx));
        }
    }
}
