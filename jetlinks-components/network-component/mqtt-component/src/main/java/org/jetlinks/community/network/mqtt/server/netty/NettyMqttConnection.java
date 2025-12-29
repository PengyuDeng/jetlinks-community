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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.*;
import io.netty.util.ReferenceCountUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.network.mqtt.server.MqttConnection;
import org.jetlinks.community.network.mqtt.server.MqttPublishing;
import org.jetlinks.community.network.mqtt.server.MqttSubscription;
import org.jetlinks.community.network.mqtt.server.MqttUnSubscription;
import org.jetlinks.core.message.codec.EncodedMessage;
import org.jetlinks.core.message.codec.MqttMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.server.mqtt.MqttAuth;
import org.jetlinks.core.utils.Reactors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
public class NettyMqttConnection implements MqttConnection {

    private final Channel channel;
    private final MqttConnectMessage connectMessage;
    private long keepAliveTimeoutMs;
    @Getter
    private long lastPingTime = System.currentTimeMillis();
    private volatile boolean closed = false, accepted = false, autoAckSub = true, autoAckUnSub = true;
    private int messageIdCounter;

    private static final MqttAuth emptyAuth = new MqttAuth() {
        @Override
        public String getUsername() {
            return "";
        }

        @Override
        public String getPassword() {
            return "";
        }
    };

    private final Sinks.Many<MqttPublishing> messageProcessor = Reactors.createMany(Integer.MAX_VALUE, false);
    private final Sinks.Many<MqttSubscription> subscription = Reactors.createMany(Integer.MAX_VALUE, false);
    private final Sinks.Many<MqttUnSubscription> unsubscription = Reactors.createMany(Integer.MAX_VALUE, false);

    public NettyMqttConnection(Channel channel, MqttConnectMessage connectMessage) {
        this.channel = channel;
        this.connectMessage = connectMessage;
        int keepAliveSeconds = connectMessage.variableHeader().keepAliveTimeSeconds();
        this.keepAliveTimeoutMs = (keepAliveSeconds + 10) * 1000L;
    }

    private final Consumer<MqttConnection> defaultListener = mqttConnection -> {
        log.debug("mqtt client [{}] disconnected", getClientId());
        subscription.tryEmitComplete();
        unsubscription.tryEmitComplete();
        messageProcessor.tryEmitComplete();
    };

    private Consumer<MqttConnection> disconnectConsumer = defaultListener;

    @Override
    public Duration getKeepAliveTimeout() {
        return Duration.ofMillis(keepAliveTimeoutMs);
    }

    @Override
    public void onClose(Consumer<MqttConnection> listener) {
        disconnectConsumer = disconnectConsumer.andThen(listener);
    }

    @Override
    public String getClientId() {
        return connectMessage.payload().clientIdentifier();
    }

    @Override
    public Optional<MqttAuth> getAuth() {
        if (!connectMessage.variableHeader().hasUserName()) {
            return Optional.of(emptyAuth);
        }
        return Optional.of(new NettyMqttAuth());
    }

    @Override
    public void reject(MqttConnectReturnCode code) {
        if (closed) {
            return;
        }
        try {
            MqttConnAckMessage connAckMessage = MqttMessageBuilders.connAck()
                .returnCode(code)
                .sessionPresent(false)
                .build();
            channel.writeAndFlush(connAckMessage).addListener(future -> {
                channel.close();
            });
        } catch (Throwable ignore) {
        }
        complete();
    }

    @Override
    public Optional<MqttMessage> getWillMessage() {
        if (!connectMessage.variableHeader().isWillFlag()) {
            return Optional.empty();
        }

        byte[] willPayload = connectMessage.payload().willMessageInBytes();
        if (willPayload == null) {
            return Optional.empty();
        }

        return Optional.of(SimpleMqttMessage
            .builder()
            .will(true)
            .payload(Unpooled.wrappedBuffer(willPayload))
            .topic(connectMessage.payload().willTopic())
            .qosLevel(connectMessage.variableHeader().willQos())
            .build());
    }

    @Override
    public MqttConnection accept() {
        if (accepted) {
            return this;
        }
        log.debug("mqtt client [{}] connected", getClientId());
        accepted = true;
        try {
            MqttConnAckMessage connAckMessage = MqttMessageBuilders.connAck()
                .returnCode(MqttConnectReturnCode.CONNECTION_ACCEPTED)
                .sessionPresent(false)
                .build();
            channel.writeAndFlush(connAckMessage);
        } catch (Exception e) {
            close().subscribe();
            log.warn(e.getMessage(), e);
            return this;
        }
        return this;
    }

    @Override
    public void keepAlive() {
        ping();
    }

    void ping() {
        lastPingTime = System.currentTimeMillis();
    }

    public void handlePublish(MqttPublishMessage msg) {
        ping();
        NettyMqttPublishing publishing = new NettyMqttPublishing(msg, false);
        boolean hasDownstream = this.messageProcessor.currentSubscriberCount() > 0;
        if (hasDownstream) {
            this.messageProcessor.emitNext(publishing, Reactors.emitFailureHandler());
        }
    }

    public void handleSubscribe(io.netty.handler.codec.mqtt.MqttSubscribeMessage msg) {
        ping();
        NettyMqttSubscription sub = new NettyMqttSubscription(msg, false);
        boolean hasDownstream = this.subscription.currentSubscriberCount() > 0;
        if (autoAckSub || !hasDownstream) {
            sub.acknowledge();
        }
        if (hasDownstream) {
            this.subscription.emitNext(sub, Reactors.emitFailureHandler());
        }
    }

    public void handleUnsubscribe(io.netty.handler.codec.mqtt.MqttUnsubscribeMessage msg) {
        ping();
        NettyMqttUnSubscription unsub = new NettyMqttUnSubscription(msg, false);
        boolean hasDownstream = this.unsubscription.currentSubscriberCount() > 0;
        if (autoAckUnSub || !hasDownstream) {
            unsub.acknowledge();
        }
        if (hasDownstream) {
            this.unsubscription.emitNext(unsub, Reactors.emitFailureHandler());
        }
    }

    public void handlePubAck(int messageId) {
        ping();
        log.debug("PUBACK mqtt[{}] message[{}]", getClientId(), messageId);
    }

    public void handlePubRec(int messageId) {
        ping();
        log.debug("PUBREC mqtt[{}] message[{}]", getClientId(), messageId);
        // Send PUBREL
        io.netty.handler.codec.mqtt.MqttMessage pubRel = new io.netty.handler.codec.mqtt.MqttMessage(
            new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0),
            MqttMessageIdVariableHeader.from(messageId)
        );
        channel.writeAndFlush(pubRel);
    }

    public void handlePubRel(int messageId) {
        ping();
        log.debug("PUBREL mqtt[{}] message[{}]", getClientId(), messageId);
        // Send PUBCOMP
        io.netty.handler.codec.mqtt.MqttMessage pubComp = new io.netty.handler.codec.mqtt.MqttMessage(
            new MqttFixedHeader(MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0),
            MqttMessageIdVariableHeader.from(messageId)
        );
        channel.writeAndFlush(pubComp);
    }

    public void handlePubComp(int messageId) {
        ping();
        log.debug("PUBCOMP mqtt[{}] message[{}]", getClientId(), messageId);
    }

    public void handlePingReq() {
        ping();
        io.netty.handler.codec.mqtt.MqttMessage pingResp = new io.netty.handler.codec.mqtt.MqttMessage(
            new MqttFixedHeader(MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0)
        );
        channel.writeAndFlush(pingResp);
    }

    public void handleDisconnect() {
        complete();
    }

    @Override
    public void setKeepAliveTimeout(Duration duration) {
        keepAliveTimeoutMs = duration.toMillis();
    }

    private volatile InetSocketAddress clientAddress;

    @Override
    public InetSocketAddress getClientAddress() {
        try {
            if (clientAddress == null && channel != null) {
                clientAddress = (InetSocketAddress) channel.remoteAddress();
            }
        } catch (Throwable ignore) {
        }
        return clientAddress;
    }

    @Override
    public Flux<MqttPublishing> handleMessage() {
        return messageProcessor.asFlux();
    }

    @Override
    public Mono<Void> publish(MqttMessage message) {
        ping();
        int messageId = message.getMessageId() <= 0 ? nextMessageId() : message.getMessageId();
        return Mono.<Void>create(sink -> {
            ByteBuf buf = message.getPayload();
            MqttPublishMessage publishMessage = MqttMessageBuilders.publish()
                .topicName(message.getTopic())
                .payload(buf.retain())
                .qos(MqttQoS.valueOf(message.getQosLevel()))
                .messageId(messageId)
                .retained(message.isRetain())
                .build();

            channel.writeAndFlush(publishMessage).addListener(future -> {
                if (future.isSuccess()) {
                    sink.success();
                } else {
                    sink.error(future.cause());
                }
                ReferenceCountUtil.safeRelease(buf);
            });
        });
    }

    @Override
    public Flux<MqttSubscription> handleSubscribe(boolean autoAck) {
        autoAckSub = autoAck;
        return subscription.asFlux();
    }

    @Override
    public Flux<MqttUnSubscription> handleUnSubscribe(boolean autoAck) {
        autoAckUnSub = autoAck;
        return unsubscription.asFlux();
    }

    @Override
    public InetSocketAddress address() {
        return getClientAddress();
    }

    @Override
    public Mono<Void> sendMessage(EncodedMessage message) {
        if (message instanceof MqttMessage) {
            return publish((MqttMessage) message);
        }
        return Mono.empty();
    }

    @Override
    public Flux<EncodedMessage> receiveMessage() {
        return handleMessage().cast(EncodedMessage.class);
    }

    @Override
    public void disconnect() {
        close().subscribe();
    }

    @Override
    public boolean isAlive() {
        return channel.isActive() && (keepAliveTimeoutMs < 0 || ((System.currentTimeMillis() - lastPingTime) < keepAliveTimeoutMs));
    }

    @Override
    public Mono<Void> close() {
        if (closed) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            try {
                if (channel.isActive()) {
                    channel.close();
                } else {
                    complete();
                }
            } catch (Throwable ignore) {
            }
        });
    }

    public void complete() {
        if (closed) {
            return;
        }
        closed = true;
        disconnectConsumer.accept(this);
    }

    @AllArgsConstructor
    class NettyMqttPublishing implements MqttPublishing {

        private final MqttPublishMessage message;
        private volatile boolean acknowledged;

        @Nonnull
        @Override
        public String getTopic() {
            return message.variableHeader().topicName();
        }

        @Override
        public String getClientId() {
            return NettyMqttConnection.this.getClientId();
        }

        @Override
        public int getMessageId() {
            return message.variableHeader().packetId();
        }

        @Override
        public boolean isWill() {
            return false;
        }

        @Override
        public int getQosLevel() {
            return message.fixedHeader().qosLevel().value();
        }

        @Override
        public boolean isDup() {
            return message.fixedHeader().isDup();
        }

        @Override
        public boolean isRetain() {
            return message.fixedHeader().isRetain();
        }

        @Nonnull
        @Override
        public ByteBuf getPayload() {
            return message.payload();
        }

        @Override
        public String toString() {
            return print();
        }

        @Override
        public MqttProperties getProperties() {
            return message.variableHeader().properties();
        }

        @Override
        public MqttMessage getMessage() {
            return this;
        }

        @Override
        public void acknowledge() {
            if (acknowledged) {
                return;
            }
            acknowledged = true;
            MqttQoS qos = message.fixedHeader().qosLevel();
            if (qos == MqttQoS.AT_LEAST_ONCE) {
                log.debug("PUBACK QoS1 mqtt[{}] message[{}]", getClientId(), getMessageId());
                io.netty.handler.codec.mqtt.MqttMessage pubAck = MqttMessageBuilders.pubAck()
                    .packetId(getMessageId())
                    .build();
                channel.writeAndFlush(pubAck);
            } else if (qos == MqttQoS.EXACTLY_ONCE) {
                log.debug("PUBREC QoS2 mqtt[{}] message[{}]", getClientId(), getMessageId());
                io.netty.handler.codec.mqtt.MqttMessage pubRec = new io.netty.handler.codec.mqtt.MqttMessage(
                    new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0),
                    MqttMessageIdVariableHeader.from(getMessageId())
                );
                channel.writeAndFlush(pubRec);
            }
        }
    }

    @AllArgsConstructor
    class NettyMqttSubscription implements MqttSubscription {

        private final io.netty.handler.codec.mqtt.MqttSubscribeMessage message;
        private volatile boolean acknowledged;

        @Override
        public io.netty.handler.codec.mqtt.MqttSubscribeMessage getMessage() {
            return message;
        }

        @Override
        public synchronized void acknowledge() {
            if (acknowledged) {
                return;
            }
            acknowledged = true;
            MqttSubAckMessage subAck = MqttMessageBuilders.subAck()
                .packetId(message.variableHeader().messageId())
                .addGrantedQoses(message.payload().topicSubscriptions()
                    .stream()
                    .map(sub -> sub.qualityOfService())
                    .toArray(MqttQoS[]::new))
                .build();
            channel.writeAndFlush(subAck);
        }
    }

    @AllArgsConstructor
    class NettyMqttUnSubscription implements MqttUnSubscription {

        private final io.netty.handler.codec.mqtt.MqttUnsubscribeMessage message;
        private volatile boolean acknowledged;

        @Override
        public io.netty.handler.codec.mqtt.MqttUnsubscribeMessage getMessage() {
            return message;
        }

        @Override
        public synchronized void acknowledge() {
            if (acknowledged) {
                return;
            }
            log.info("acknowledge mqtt [{}] unsubscribe : {} ", getClientId(), message.payload().topics());
            acknowledged = true;
            MqttUnsubAckMessage unsubAck = MqttMessageBuilders.unsubAck()
                .packetId(message.variableHeader().messageId())
                .build();
            channel.writeAndFlush(unsubAck);
        }
    }

    class NettyMqttAuth implements MqttAuth {

        @Override
        public String getUsername() {
            return connectMessage.payload().userName();
        }

        @Override
        public String getPassword() {
            byte[] passwordBytes = connectMessage.payload().passwordInBytes();
            return passwordBytes != null ? new String(passwordBytes) : "";
        }
    }

    private int nextMessageId() {
        this.messageIdCounter = ((this.messageIdCounter % 65535) != 0) ? this.messageIdCounter + 1 : 1;
        return this.messageIdCounter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NettyMqttConnection that = (NettyMqttConnection) o;
        return Objects.equals(channel, that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel);
    }
}
