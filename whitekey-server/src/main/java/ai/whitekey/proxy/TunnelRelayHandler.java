package ai.whitekey.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional raw TCP byte relay between an inbound agent channel and an upstream tunnel channel
 * after an HTTP {@code CONNECT} handshake succeeds.
 */
final class TunnelRelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TunnelRelayHandler.class);

    private final Channel peerChannel;

    TunnelRelayHandler(Channel peerChannel) {
        this.peerChannel = peerChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!peerChannel.isActive()) {
            ReferenceCountUtil.release(msg);
            closeOnFlush(ctx.channel());
            return;
        }
        peerChannel.writeAndFlush(msg);
        if (!peerChannel.isWritable()) {
            ctx.channel().config().setAutoRead(false);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (peerChannel.isActive()) {
            peerChannel.config().setAutoRead(ctx.channel().isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeOnFlush(peerChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Tunnel relay closed due to error: {}", cause.getMessage());
        closeOnFlush(ctx.channel());
        closeOnFlush(peerChannel);
    }

    static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
