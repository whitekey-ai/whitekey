package ai.whitekey.proxy;

import ai.whitekey.proxy.AgentProxyService.ProxyResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import org.springframework.util.unit.DataSize;

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
        SPOOLING
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
                            ProxyResponse finalResp = response;
                            sendResponseFromVirtualThread(
                                    ctx, currentMethod, finalResp, keepAlive);
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

    private void handleHttpContent(
            ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpContent content) {
        if (state == State.DISCARDING) {
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
                try {
                    if (spoolOut != null) {
                        spoolOut.close();
                        spoolOut = null;
                    }
                } catch (IOException ignored) {
                }

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
                                if (file != null) {
                                    try {
                                        Files.deleteIfExists(file);
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                            ProxyResponse finalResp = response;
                            sendResponseFromVirtualThread(
                                    ctx, currentMethod, finalResp, keepAlive);
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
                try {
                    if (pipedOut != null) {
                        pipedOut.close();
                        pipedOut = null;
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void cleanup(ChannelHandlerContext ctx) {
        state = State.IDLE;
        currentMethod = null;
        preparedRequest = null;
        if (pipedOut != null) {
            try {
                pipedOut.close();
            } catch (IOException ignored) {
            }
            pipedOut = null;
        }
        if (spoolOut != null) {
            try {
                spoolOut.close();
            } catch (IOException ignored) {
            }
            spoolOut = null;
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            tempFile = null;
        }
        if (clientDisconnected != null && closeListener != null) {
            ctx.channel().closeFuture().removeListener(closeListener);
            closeListener = null;
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
