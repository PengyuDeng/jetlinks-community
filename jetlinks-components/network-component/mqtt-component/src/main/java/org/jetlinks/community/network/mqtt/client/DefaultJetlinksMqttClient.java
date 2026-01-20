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
package org.jetlinks.community.network.mqtt.client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.core.message.codec.MqttMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.reactor.mqtt.client.ClientConnection;
import org.jetlinks.reactor.mqtt.client.ClientReceivedPublish;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 使用 reactor-mqtt 实现的 MQTT Client。
 *
 * @author PengyuDeng
 * @since 2.11
 */
@Slf4j
public class DefaultJetlinksMqttClient implements JetlinksMqttClient {

    private static final VarHandle CONNECTION;
    private static final VarHandle LOADING;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            CONNECTION = lookup.findVarHandle(DefaultJetlinksMqttClient.class, "connection", ClientConnection.class);
            LOADING = lookup.findVarHandle(DefaultJetlinksMqttClient.class, "loading", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ClientConnection connection;

    private final String id;

    private boolean loading;

    private final List<Runnable> loadSuccessListener = new CopyOnWriteArrayList<>();

    @Setter
    private String topicPrefix;

    public DefaultJetlinksMqttClient(String id) {
        this.id = id;
    }

    public ClientConnection getConnection() {
        return (ClientConnection) CONNECTION.getVolatile(this);
    }

    public void setConnection(ClientConnection connection) {
        ClientConnection oldConnection = (ClientConnection) CONNECTION.getAndSet(this, connection);
        if (oldConnection != null && oldConnection != connection) {
            try {
                oldConnection.disconnect().subscribe();
            } catch (Exception ignore) {
            }
        }

        if (isLoading()) {
            loadSuccessListener.add(this::onConnected);
        } else if (isAlive()) {
            onConnected();
        }
    }

    private void onConnected() {
        log.debug("mqtt client [{}] connected", id);
    }

    public boolean isLoading() {
        return (boolean) LOADING.getVolatile(this);
    }

    public void setLoading(boolean loading) {
        LOADING.setVolatile(this, loading);
        if (!loading) {
            loadSuccessListener.forEach(Runnable::run);
            loadSuccessListener.clear();
        }
    }

    protected String getCompleteTopic(String topic) {
        if (StringUtils.isEmpty(topicPrefix)) {
            return topic;
        }
        return topicPrefix.concat(topic);
    }

    @Override
    public Flux<MqttMessage> subscribe(List<String> topics, int qos) {
        return Flux.create(sink -> {
            ClientConnection conn = getConnection();
            if (conn == null || !conn.isAlive()) {
                sink.error(new IllegalStateException("MQTT client is not connected"));
                return;
            }

            List<String> completeTopics = topics.stream()
                                                .map(this::getCompleteTopic)
                                                .collect(Collectors.toList());

            Disposable disposable = conn.subscribe(
                completeTopics,
                MqttQoS.valueOf(qos),
                receivedPublish -> {
                    try {
                        MqttMessage mqttMessage = convertToMqttMessage(receivedPublish);
                        log.debug("handle mqtt message \n{}", mqttMessage);
                        sink.next(mqttMessage);
                    } catch (Exception e) {
                        log.error("handle mqtt message error", e);
                    }
                    return Mono.empty();
                }
            );

            sink.onDispose(disposable);
        });
    }

    private MqttMessage convertToMqttMessage(ClientReceivedPublish receivedPublish) {
        // retain payload，因为 reactor-mqtt 可能会在回调返回后释放原始 ByteBuf
        // 消费者处理完消息后需要调用 ReferenceCountUtil.release(payload) 释放
        ByteBuf payload = receivedPublish.getPayload();
        if (payload != null) {
            payload.retain();
        }
        return SimpleMqttMessage
            .builder()
            .messageId(receivedPublish.getMessageId())
            .topic(receivedPublish.getTopic())
            .payload(payload)
            .dup(receivedPublish.isDup())
            .retain(receivedPublish.isRetain())
            .qosLevel(receivedPublish.getQos().value())
            .properties(receivedPublish.getProperties())
            .build();
    }

    @Override
    public Mono<Void> publish(MqttMessage message) {
        if (isLoading()) {
            return Mono.create(sink -> {
                loadSuccessListener.add(() -> {
                    doPublish(message)
                        .doOnSuccess(v -> sink.success())
                        .doOnError(sink::error)
                        .subscribe();
                });
            });
        }
        return doPublish(message);
    }

    private Mono<Void> doPublish(MqttMessage message) {
        ClientConnection conn = getConnection();
        if (conn == null || !conn.isAlive()) {
            return Mono.error(new IllegalStateException("MQTT client is not connected"));
        }
        return conn
            .publish(
                message.getTopic(),
                message.getPayload(),
                MqttQoS.valueOf(message.getQosLevel()),
                message.isRetain()
            )
            .doOnSuccess(v -> log.info("publish mqtt [{}] message success: {}", id, message))
            .doOnError(e -> log.error("publish mqtt [{}] message error: {}", id, message, e));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_CLIENT;
    }

    @Override
    public void shutdown() {
        LOADING.setVolatile(this, false);
        ClientConnection conn = (ClientConnection) CONNECTION.getAndSet(this, null);
        if (conn != null && conn.isAlive()) {
            try {
                conn.disconnect().subscribe();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    public boolean isAlive() {
        ClientConnection conn = getConnection();
        return conn != null && conn.isAlive();
    }

    @Override
    public boolean isAutoReload() {
        return true;
    }
}
