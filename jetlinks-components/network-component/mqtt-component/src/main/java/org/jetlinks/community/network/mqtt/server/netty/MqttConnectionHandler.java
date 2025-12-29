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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.mqtt.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * MQTT 连接处理器，支持函数式连接处理
 */
@Slf4j
@ChannelHandler.Sharable
public class MqttConnectionHandler extends SimpleChannelInboundHandler<io.netty.handler.codec.mqtt.MqttMessage> {

    private static final AttributeKey<NettyMqttConnection> CONNECTION_KEY = AttributeKey.valueOf("mqtt.connection");

    private final Function<NettyMqttConnection, Mono<Void>> connectionHandler;

    public MqttConnectionHandler(Function<NettyMqttConnection, Mono<Void>> connectionHandler) {
        this.connectionHandler = connectionHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, io.netty.handler.codec.mqtt.MqttMessage msg) throws Exception {
        MqttMessageType messageType = msg.fixedHeader().messageType();

        switch (messageType) {
            case CONNECT:
                handleConnect(ctx, (MqttConnectMessage) msg);
                break;
            case PUBLISH:
                handlePublish(ctx, (MqttPublishMessage) msg);
                break;
            case PUBACK:
                handlePubAck(ctx, msg);
                break;
            case PUBREC:
                handlePubRec(ctx, msg);
                break;
            case PUBREL:
                handlePubRel(ctx, msg);
                break;
            case PUBCOMP:
                handlePubComp(ctx, msg);
                break;
            case SUBSCRIBE:
                handleSubscribe(ctx, (MqttSubscribeMessage) msg);
                break;
            case UNSUBSCRIBE:
                handleUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
                break;
            case PINGREQ:
                handlePingReq(ctx);
                break;
            case DISCONNECT:
                handleDisconnect(ctx);
                break;
            default:
                log.warn("Unsupported MQTT message type: {}", messageType);
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        NettyMqttConnection connection = new NettyMqttConnection(ctx.channel(), msg);
        ctx.channel().attr(CONNECTION_KEY).set(connection);

        if (connectionHandler != null) {
            connectionHandler.apply(connection)
                .doOnError(err -> {
                    log.error("Error handling MQTT connection from {}: {}",
                        connection.getClientAddress(), err.getMessage(), err);
                    connection.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
                })
                .subscribe();
        } else {
            // 默认接受连接
            connection.accept();
        }
    }

    private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            msg.retain();
            connection.handlePublish(msg);
        }
    }

    private void handlePubAck(ChannelHandlerContext ctx, io.netty.handler.codec.mqtt.MqttMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            MqttMessageIdVariableHeader header = (MqttMessageIdVariableHeader) msg.variableHeader();
            connection.handlePubAck(header.messageId());
        }
    }

    private void handlePubRec(ChannelHandlerContext ctx, io.netty.handler.codec.mqtt.MqttMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            MqttMessageIdVariableHeader header = (MqttMessageIdVariableHeader) msg.variableHeader();
            connection.handlePubRec(header.messageId());
        }
    }

    private void handlePubRel(ChannelHandlerContext ctx, io.netty.handler.codec.mqtt.MqttMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            MqttMessageIdVariableHeader header = (MqttMessageIdVariableHeader) msg.variableHeader();
            connection.handlePubRel(header.messageId());
        }
    }

    private void handlePubComp(ChannelHandlerContext ctx, io.netty.handler.codec.mqtt.MqttMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            MqttMessageIdVariableHeader header = (MqttMessageIdVariableHeader) msg.variableHeader();
            connection.handlePubComp(header.messageId());
        }
    }

    private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            connection.handleSubscribe(msg);
        }
    }

    private void handleUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage msg) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            connection.handleUnsubscribe(msg);
        }
    }

    private void handlePingReq(ChannelHandlerContext ctx) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            connection.handlePingReq();
        }
    }

    private void handleDisconnect(ChannelHandlerContext ctx) {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            connection.handleDisconnect();
        }
        ctx.close();
    }

    private NettyMqttConnection getConnection(ChannelHandlerContext ctx) {
        return ctx.channel().attr(CONNECTION_KEY).get();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyMqttConnection connection = getConnection(ctx);
        if (connection != null) {
            connection.complete();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String message = cause.getMessage();
        if (message != null && message.contains("too large message")) {
            log.error("MQTT消息过大,请在网络组件中设置[最大消息长度].", cause);
        } else {
            log.error("MQTT server error: {}", cause.getMessage(), cause);
        }
        ctx.close();
    }
}
