package com.riskwarning.notification.websocket;

import com.alibaba.fastjson2.JSON;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationHttpHandlerTest {
    @Test void errorsPreserveStatusAndProtocolHeadersAndReleaseOriginalBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new NotificationHttpHandler());
        try {
            for (HttpResponseStatus status : new HttpResponseStatus[]{HttpResponseStatus.UPGRADE_REQUIRED,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR}) {
                FullHttpResponse original = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
                original.content().writeCharSequence("private-internal-body", CharsetUtil.UTF_8);
                original.headers().set("Sec-WebSocket-Version", "13");
                original.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
                assertTrue(channel.writeOutbound(original));
                assertEquals(0, original.refCnt());
                FullHttpResponse response = channel.readOutbound();
                try {
                    assertEquals(status, response.status());
                    assertEquals("13", response.headers().get("Sec-WebSocket-Version"));
                    assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
                    assertEquals(response.content().readableBytes(), HttpUtil.getContentLength(response));
                    String body = response.content().toString(CharsetUtil.UTF_8);
                    assertEquals(status.code(), JSON.parseObject(body).getIntValue("code"));
                    assertTrue(JSON.parseObject(body).containsKey("data"));
                    assertFalse(body.contains("private-"));
                } finally { response.release(); }
            }
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void upgradeAndFramesPassThroughWithoutEnvelope() {
        EmbeddedChannel channel = new EmbeddedChannel(new NotificationHttpHandler());
        try {
            FullHttpResponse upgrade = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
            channel.writeOutbound(upgrade);
            assertSame(upgrade, channel.readOutbound());
            upgrade.release();
            TextWebSocketFrame frame = new TextWebSocketFrame("{\"type\":\"pong\"}");
            channel.writeOutbound(frame);
            assertSame(frame, channel.readOutbound());
            frame.release();
        } finally { channel.finishAndReleaseAll(); }
    }
}
