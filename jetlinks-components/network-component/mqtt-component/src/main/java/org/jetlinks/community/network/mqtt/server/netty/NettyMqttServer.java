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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.ssl.SslContext;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jctools.maps.NonBlockingHashMap;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.community.network.mqtt.server.MqttConnection;
import org.jetlinks.community.network.mqtt.server.MqttServer;
import org.jetlinks.core.utils.Reactors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class NettyMqttServer implements MqttServer {

    private final Sinks.Many<MqttConnection> sink = Reactors.createMany(5 * 1024, false);

    private final Map<String, List<Sinks.Many<MqttConnection>>> sinks = new NonBlockingHashMap<>();

    private volatile MqttServerBuilder.DisposableMqttServer server;

    private final String id;

    @Getter
    @Setter
    private String lastError;

    @Setter(AccessLevel.PACKAGE)
    private InetSocketAddress bind;

    public NettyMqttServer(String id) {
        this.id = id;
    }

    /**
     * 使用流式 API 启动服务器
     */
    public Mono<NettyMqttServer> start(NettyMqttServerProperties properties,
                                        EventLoopGroup bossGroup,
                                        EventLoopGroup workerGroup,
                                        SslContext sslContext) {
        return Mono.fromCallable(() -> {
            if (this.server != null && !this.server.isDisposed()) {
                shutdown();
            }

            this.bind = new InetSocketAddress(properties.getHost(), properties.getPort());

            MqttServerBuilder.DisposableMqttServer disposableServer = MqttServerBuilder
                .create()
                .host(properties.getHost())
                .port(properties.getPort())
                .maxMessageSize(properties.getMaxMessageSize())
                .eventLoopGroups(bossGroup, workerGroup)
                .ssl(sslContext)
                .handle(connection -> {
                    // 分发连接到订阅者
                    handleConnection(connection);
                    // 返回空 Mono，连接处理由订阅者完成
                    return Mono.empty();
                })
                .bindNow();

            this.server = disposableServer;
            log.debug("startup mqtt server [{}] on port :{}", id, properties.getPort());

            return this;
        }).onErrorResume(e -> {
            this.lastError = e.getMessage();
            log.warn("startup mqtt server [{}] error", id, e);
            return Mono.error(e);
        });
    }

    /**
     * 使用旧的 Channel 方式设置服务器（向后兼容）
     */
    public void setServerChannel(Channel serverChannel) {
        if (this.server != null && !this.server.isDisposed()) {
            shutdown();
        }
        // 包装为 DisposableMqttServer
        this.server = new MqttServerBuilder.DisposableMqttServer() {
            @Override
            public InetSocketAddress address() {
                return bind;
            }

            @Override
            public void dispose() {
                serverChannel.close();
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
    }

    private boolean emitNext(Sinks.Many<MqttConnection> sink, NettyMqttConnection connection) {
        if (sink.currentSubscriberCount() <= 0) {
            return false;
        }
        try {
            return sink.tryEmitNext(connection).isSuccess();
        } catch (Throwable ignore) {
        }
        return false;
    }

    public void handleConnection(NettyMqttConnection connection) {
        boolean anyHandled = emitNext(sink, connection);

        for (List<Sinks.Many<MqttConnection>> value : sinks.values()) {
            if (value.isEmpty()) {
                continue;
            }
            Sinks.Many<MqttConnection> sink = value.get(ThreadLocalRandom.current().nextInt(value.size()));
            if (emitNext(sink, connection)) {
                anyHandled = true;
            }
        }
        if (!anyHandled) {
            connection.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
        }
    }

    @Override
    public Flux<MqttConnection> handleConnection() {
        return sink.asFlux();
    }

    @Override
    public Flux<MqttConnection> handleConnection(String holder) {
        List<Sinks.Many<MqttConnection>> sinks = this
            .sinks
            .computeIfAbsent(holder, ignore -> new CopyOnWriteArrayList<>());

        Sinks.Many<MqttConnection> sink =
            Sinks.unsafe()
                 .many()
                 .unicast()
                 .onBackpressureBuffer(Queues.<MqttConnection>unboundedMultiproducer().get());

        sinks.add(sink);

        return sink
            .asFlux()
            .doOnCancel(() -> sinks.remove(sink));
    }

    @Override
    public boolean isAlive() {
        return server != null && !server.isDisposed();
    }

    @Override
    public boolean isAutoReload() {
        return false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_SERVER;
    }

    @Override
    public void shutdown() {
        if (server != null) {
            try {
                server.dispose();
                log.debug("mqtt server [{}] closed", id);
            } catch (Throwable e) {
                log.error("shutdown mqtt server [{}] error", id, e);
            }
            server = null;
        }
    }

    @Override
    public InetSocketAddress getBindAddress() {
        return bind;
    }
}
