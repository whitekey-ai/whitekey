package ai.whitekey.proxy;

import ai.whitekey.proxy.AgentProxyService.ProxyResponse;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

final class ProxyResponseWriter {
    private static final int BUFFER_SIZE = (int) DataSize.ofKilobytes(64).toBytes();

    private ProxyResponseWriter() {}

    public static void write(
            ChannelHandlerContext ctx,
            String requestMethod,
            ProxyResponse response,
            boolean keepAlive) {
        write(ctx, requestMethod, response, keepAlive, null);
    }

    public static void write(
            ChannelHandlerContext ctx,
            String requestMethod,
            ProxyResponse response,
            boolean keepAlive,
            Runnable beforeFinalFlush) {
        boolean head = HttpMethod.HEAD.name().equalsIgnoreCase(requestMethod);
        boolean noContent = response.statusCode() == HttpResponseStatus.NO_CONTENT.code()
                || response.statusCode() == HttpResponseStatus.NOT_MODIFIED.code();

        if (response.bodyStream() != null && !head && !noContent) {
            HttpResponse nettyResponse = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.statusCode()));
            copyHeaders(response.headers(), nettyResponse);
            HttpUtil.setTransferEncodingChunked(nettyResponse, true);
            HttpUtil.setKeepAlive(nettyResponse, keepAlive);
            ctx.writeAndFlush(nettyResponse);

            boolean streamFailed = false;
            try (InputStream in = response.bodyStream()) {
                byte[] chunk = new byte[BUFFER_SIZE];
                int read;
                while (ctx.channel().isOpen() && (read = in.read(chunk)) != -1) {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(chunk, 0, read)));
                }
            } catch (Exception ignored) {
                streamFailed = true;
            }

            boolean abortStream = streamFailed || !ctx.channel().isOpen();
            boolean closeAfter = !keepAlive;
            runOnEventLoop(ctx, () -> {
                if (beforeFinalFlush != null) {
                    beforeFinalFlush.run();
                }
                if (abortStream) {
                    ctx.close();
                } else {
                    ChannelFuture future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    if (closeAfter) {
                        future.addListener(ChannelFutureListener.CLOSE);
                    }
                }
            });
        } else {
            closeQuietly(response.bodyStream());
            byte[] body = (head || noContent || response.body() == null) ? new byte[0] : response.body();
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.statusCode()),
                    Unpooled.wrappedBuffer(body));
            copyHeaders(response.headers(), nettyResponse);
            if (!head && !noContent) {
                HttpUtil.setContentLength(nettyResponse, body.length);
            }
            boolean keepOpen = keepAlive && ctx.channel().isOpen();
            HttpUtil.setKeepAlive(nettyResponse, keepOpen);
            runOnEventLoop(ctx, () -> {
                if (beforeFinalFlush != null) {
                    beforeFinalFlush.run();
                }
                ChannelFuture future = ctx.writeAndFlush(nettyResponse);
                if (!keepOpen) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
            });
        }
    }

    private static void runOnEventLoop(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.executor().inEventLoop()) {
            task.run();
        } else {
            ctx.executor().execute(task);
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copyHeaders(Map<String, List<String>> src, HttpResponse dest) {
        for (Map.Entry<String, List<String>> entry : src.entrySet()) {
            for (String value : entry.getValue()) {
                dest.headers().add(entry.getKey(), value);
            }
        }
    }
}
