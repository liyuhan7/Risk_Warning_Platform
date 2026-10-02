package com.riskwarning.notification.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.sctp.nio.NioSctpServerChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationServer {

    @Value("${netty.websocket.port}")
    private int port;

    @Autowired
    private NotificationWebSocketHandler webSocketHandler;

    private EventLoopGroup boss;

    private EventLoopGroup worker;


    public void start() {
        // 启动Netty服务器的逻辑
        this.boss = new NioEventLoopGroup();
        this.worker = new NioEventLoopGroup();

        try {

            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            configurePipeline(ch.pipeline());
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            log.info("WebSocket Server started on port {}", port);
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            log.error("WebSocket服务器启动失败: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    /** 握手错误适配位于编解码器之后，覆盖聚合器与 WebSocket 协议的错误响应。 */
    void configurePipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec())
                .addLast(new NotificationHttpHandler())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(new ChunkedWriteHandler())
                .addLast(new WebSocketServerProtocolHandler("/ws"))
                .addLast(webSocketHandler);
    }


}
