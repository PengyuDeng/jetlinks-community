/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.network.mqtt.server.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MQTT Server 流式构建器
 * <pre>{@code
 * DisposableMqttServer server = MqttServerBuilder
 *     .create()
 *     .host("0.0.0.0")
 *     .port(1883)
 *     .maxMessageSize(8096)
 *     .handle(connection -> {
 *         connection
 *             .handleMessage()
 *             .subscribe(msg -> System.out.println("Received: " + msg.getTopic()));
 *
 *         connection
 *             .handleSubscribe(true)
 *             .subscribe(sub -> System.out.println("Subscribe: " + sub.getMessage()));
 *
 *         return validate(connection).then(Mono.fromRunnable(() -> connection.accept()));
 *     })
 *     .bindNow();
 * }</pre>
 *
 * @author zhouhao
 */
@Slf4j
public class MqttServerBuilder {

    private String host = "0.0.0.0";
    private int port = 1883;
    private int maxMessageSize = 8096;
    private Duration keepAliveTimeout = Duration.ofSeconds(120);
    private SslContext sslContext;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = false;
    private Function<NettyMqttConnection, Mono<Void>> connectionHandler;

    private MqttServerBuilder() {
    }

    public static MqttServerBuilder create() {
        return new MqttServerBuilder();
    }

    public MqttServerBuilder host(String host) {
        this.host = host;
        return this;
    }

    public MqttServerBuilder port(int port) {
        this.port = port;
        return this;
    }

    public MqttServerBuilder maxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
        return this;
    }

    public MqttServerBuilder keepAliveTimeout(Duration keepAliveTimeout) {
        this.keepAliveTimeout = keepAliveTimeout;
        return this;
    }

    public MqttServerBuilder ssl(SslContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    public MqttServerBuilder eventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.ownEventLoopGroups = false;
        return this;
    }

    /**
     * 设置连接处理器
     *
     * @param handler 连接处理函数，返回 Mono<Void> 表示处理完成
     *                在处理器中调用 connection.accept() 接受连接
     *                或调用 connection.reject(code) 拒绝连接
     */
    public MqttServerBuilder handle(Function<NettyMqttConnection, Mono<Void>> handler) {
        this.connectionHandler = handler;
        return this;
    }

    /**
     * 异步绑定服务器
     */
    public Mono<DisposableMqttServer> bind() {
        return Mono.create(sink -> {
            try {
                DisposableMqttServer server = doBind();
                sink.success(server);
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    /**
     * 同步绑定服务器
     */
    public DisposableMqttServer bindNow() {
        return doBind();
    }

    /**
     * 同步绑定服务器，带超时
     */
    public DisposableMqttServer bindNow(Duration timeout) {
        return bind().block(timeout);
    }

    private DisposableMqttServer doBind() {
        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            ownEventLoopGroups = true;
        }

        MqttConnectionHandler handler = new MqttConnectionHandler(connectionHandler);
        long keepAliveSeconds = keepAliveTimeout.toSeconds();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .option(ChannelOption.SO_REUSEADDR, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();

                    if (sslContext != null) {
                        pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                    }

                    pipeline.addLast("idleStateHandler",
                        new IdleStateHandler(0, 0, keepAliveSeconds, TimeUnit.SECONDS));
                    pipeline.addLast("mqttDecoder", new MqttDecoder(maxMessageSize));
                    pipeline.addLast("mqttEncoder", MqttEncoder.INSTANCE);
                    pipeline.addLast("mqttHandler", handler);
                }
            });

        InetSocketAddress bindAddress = new InetSocketAddress(host, port);

        try {
            ChannelFuture future = bootstrap.bind(bindAddress).sync();
            if (!future.isSuccess()) {
                throw new RuntimeException("Failed to bind MQTT server on " + bindAddress, future.cause());
            }

            Channel serverChannel = future.channel();
            log.info("MQTT server started on {}:{}", host, port);

            return new DisposableMqttServer() {
                @Override
                public InetSocketAddress address() {
                    return bindAddress;
                }

                @Override
                public void dispose() {
                    serverChannel.close().syncUninterruptibly();
                    if (ownEventLoopGroups) {
                        bossGroup.shutdownGracefully();
                        workerGroup.shutdownGracefully();
                    }
                    log.info("MQTT server stopped");
                }

                @Override
                public boolean isDisposed() {
                    return !serverChannel.isActive();
                }

                @Override
                public Channel channel() {
                    return serverChannel;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while binding MQTT server", e);
        }
    }

    /**
     * 可释放的 MQTT 服务器
     */
    public interface DisposableMqttServer extends Disposable {
        /**
         * 获取绑定地址
         */
        InetSocketAddress address();

        /**
         * 获取服务器 Channel
         */
        Channel channel();
    }
}
