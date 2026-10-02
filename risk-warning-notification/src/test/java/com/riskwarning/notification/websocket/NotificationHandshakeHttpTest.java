package com.riskwarning.notification.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实随机端口运行生产 Netty 管道，通道管理器为替身。 */
class NotificationHandshakeHttpTest {
    private EventLoopGroup boss, worker, clients;
    private Channel server;
    private int port;
    private WebSocketChannelManager manager;

    @BeforeEach void start() throws Exception {
        manager = mock(WebSocketChannelManager.class);
        NotificationWebSocketHandler handler = new NotificationWebSocketHandler();
        ReflectionTestUtils.setField(handler, "channelManager", manager);
        NotificationServer production = new NotificationServer();
        ReflectionTestUtils.setField(production, "webSocketHandler", handler);
        boss = new NioEventLoopGroup(1); worker = new NioEventLoopGroup(1); clients = new NioEventLoopGroup(1);
        server = new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        production.configurePipeline(channel.pipeline());
                    }
                }).bind("127.0.0.1", 0).sync().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();
    }
    @AfterEach void stop() {
        if (server != null) server.close().syncUninterruptibly();
        for (EventLoopGroup group : new EventLoopGroup[]{clients, worker, boss}) {
            if (group != null) group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
    @Test void unknownRouteAndPlainHttpAreExplicitErrors() throws Exception {
        error(request("GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n"), 404);
        error(request("GET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n"), 400);
        Reply method = request("POST /ws HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
        error(method, 405); assertEquals("GET", method.headers.get("allow"));
        verify(manager, never()).bindUser(anyLong(), any());
    }
    @Test void handshakeFailuresAndOversizedPayloadKeepHeadersAndJson() throws Exception {
        Reply version = request(handshake("99", true));
        error(version, 426); assertEquals("13", version.headers.get("sec-websocket-version"));
        error(request(handshake("13", false)), 400);
        String large = handshake("13", true).replace("\r\n\r\n", "\r\nContent-Length: 65537\r\n\r\n");
        error(request(large), 413);
    }
    @Test void upgradeThenBindAndHeartbeatKeepExistingFrameProtocol() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CompletableFuture<Void> upgraded = new CompletableFuture<>();
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                URI.create("ws://127.0.0.1:" + port + "/ws"), WebSocketVersion.V13, null, false, new DefaultHttpHeaders());
        Channel client = new Bootstrap().group(clients).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536),
                                new SimpleChannelInboundHandler<Object>() {
                                    @Override protected void channelRead0(ChannelHandlerContext ctx, Object message) {
                                        if (!handshaker.isHandshakeComplete()) {
                                            handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) message);
                                            upgraded.complete(null);
                                        } else if (message instanceof TextWebSocketFrame) {
                                            frames.add(((TextWebSocketFrame) message).text());
                                        }
                                    }
                                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
                                        upgraded.completeExceptionally(failure); ctx.close();
                                    }
                                });
                    }
                }).connect("127.0.0.1", port).sync().channel();
        try {
            handshaker.handshake(client).sync(); upgraded.get(5, TimeUnit.SECONDS);
            client.writeAndFlush(new TextWebSocketFrame("{\"type\":\"bind\",\"userId\":123}")).sync();
            JSONObject bound = JSON.parseObject(frames.poll(5, TimeUnit.SECONDS));
            assertNotNull(bound); assertEquals("bind_success", bound.getString("type"));
            assertEquals(123, bound.getLongValue("userId")); assertFalse(bound.containsKey("code"));
            verify(manager, timeout(1000)).bindUser(eq(123L), any(Channel.class));
            client.writeAndFlush(new TextWebSocketFrame("{\"type\":\"ping\"}")).sync();
            assertEquals("{\"type\":\"pong\"}", frames.poll(5, TimeUnit.SECONDS));
        } finally { client.close().syncUninterruptibly(); }
    }
    private String handshake(String version, boolean key) {
        return "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: " + version + "\r\n"
                + (key ? "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" : "") + "\r\n";
    }
    private Reply request(String raw) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000); socket.setSoTimeout(3000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            byte[] ending = new byte[]{13, 10, 13, 10};
            while (matched < ending.length) {
                int next = input.read(); if (next < 0) throw new EOFException("Missing HTTP headers");
                header.write(next); matched = next == ending[matched] ? matched + 1 : next == 13 ? 1 : 0;
            }
            String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
            Reply result = new Reply(); result.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) result.headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            int length = Integer.parseInt(result.headers.getOrDefault("content-length", "0"));
            byte[] bytes = new byte[length]; int offset = 0;
            while (offset < length) {
                int count = input.read(bytes, offset, length - offset);
                if (count < 0) throw new EOFException("Missing HTTP body"); offset += count;
            }
            result.body = new String(bytes, StandardCharsets.UTF_8); return result;
        }
    }
    private void error(Reply reply, int expected) {
        assertEquals(expected, reply.status, reply.body);
        assertTrue(reply.headers.get("content-type").startsWith("application/json"));
        JSONObject body = JSON.parseObject(reply.body);
        assertEquals(expected, body.getIntValue("code")); assertTrue(body.containsKey("message"));
        assertTrue(body.containsKey("data")); assertNull(body.get("data"));
        assertFalse(reply.body.contains("private-"));
    }
    private static class Reply {
        int status; String body; Map<String, String> headers = new HashMap<>();
    }
}
