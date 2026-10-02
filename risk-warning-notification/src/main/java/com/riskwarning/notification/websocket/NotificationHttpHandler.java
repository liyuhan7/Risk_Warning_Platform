package com.riskwarning.notification.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.riskwarning.common.result.Result;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

/** WebSocket 升级前使用 HTTP 错误 envelope，升级后的帧保持原协议。 */
@ChannelHandler.Sharable
final class NotificationHttpHandler extends ChannelDuplexHandler {
    private static final AttributeKey<Boolean> REJECTED = AttributeKey.valueOf("notification.http.rejected");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (Boolean.TRUE.equals(ctx.channel().attr(REJECTED).get())) {
            ReferenceCountUtil.release(message);
            return;
        }
        if (message instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) message;
            HttpResponseStatus status = null;
            if (!request.decoderResult().isSuccess()) {
                status = HttpResponseStatus.BAD_REQUEST;
            } else if (!"/ws".equals(request.uri())) {
                status = HttpResponseStatus.NOT_FOUND;
            } else if (!HttpMethod.GET.equals(request.method())) {
                status = HttpResponseStatus.METHOD_NOT_ALLOWED;
            } else if (!request.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)
                    || !request.headers().containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)) {
                status = HttpResponseStatus.BAD_REQUEST;
            }
            if (status != null) {
                ctx.channel().attr(REJECTED).set(true);
                ReferenceCountUtil.release(message);
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
                if (status.equals(HttpResponseStatus.METHOD_NOT_ALLOWED)) {
                    response.headers().set(HttpHeaderNames.ALLOW, "GET");
                }
                // 从 Channel 写出，保证本处理器也适配自身产生的错误响应。
                ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        ctx.fireChannelRead(message);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof FullHttpResponse && ((FullHttpResponse) message).status().code() >= 400) {
            FullHttpResponse original = (FullHttpResponse) message;
            int code = original.status().code();
            String text = code == 404 ? "请求的资源不存在"
                    : code == 405 ? "请求方法不支持"
                    : code == 426 ? "WebSocket 版本不支持"
                    : code == 413 ? "请求内容过大"
                    : code >= 500 ? "请求处理失败" : "WebSocket 握手请求无效";
            byte[] body = JSON.toJSONBytes(Result.fail(code, text), JSONWriter.Feature.WriteNulls);
            FullHttpResponse replacement = new DefaultFullHttpResponse(original.protocolVersion(), original.status(),
                    Unpooled.wrappedBuffer(body));
            replacement.headers().set(original.headers());
            replacement.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            replacement.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            replacement.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            ReferenceCountUtil.release(original);
            message = replacement;
        }
        ctx.write(message, promise);
    }
}
