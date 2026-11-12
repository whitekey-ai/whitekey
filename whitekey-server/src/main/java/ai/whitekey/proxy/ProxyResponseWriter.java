package ai.whitekey.proxy;

import ai.whitekey.proxy.AgentProxyService.ProxyResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
        boolean head = HttpMethod.HEAD.name().equalsIgnoreCase(requestMethod);
        boolean noContent = response.statusCode() == HttpResponseStatus.NO_CONTENT.code()
                || response.statusCode() == HttpResponseStatus.NOT_MODIFIED.code();

        if (response.bodyStream() != null && !head && !noContent) {
            HttpResponse nettyResponse = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.statusCode()));
            copyHeaders(response.headers(), nettyResponse);
            HttpUtil.setKeepAlive(nettyResponse, keepAlive);
            ctx.writeAndFlush(nettyResponse);

            try (InputStream in = response.bodyStream()) {
                byte[] chunk = new byte[BUFFER_SIZE];
                int read;
                while (ctx.channel().isOpen() && (read = in.read(chunk)) != -1) {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(chunk, 0, read)));
                }
            } catch (Exception ignored) {
            }

            if (keepAlive && ctx.channel().isOpen()) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            byte[] body = (head || noContent || response.body() == null) ? new byte[0] : response.body();
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.statusCode()),
                    Unpooled.wrappedBuffer(body));
            copyHeaders(response.headers(), nettyResponse);
            if (!head && !noContent) {
                HttpUtil.setContentLength(nettyResponse, body.length);
            }
            if (keepAlive && ctx.channel().isOpen()) {
                HttpUtil.setKeepAlive(nettyResponse, true);
                ctx.writeAndFlush(nettyResponse);
            } else {
                HttpUtil.setKeepAlive(nettyResponse, false);
                ctx.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE);
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
