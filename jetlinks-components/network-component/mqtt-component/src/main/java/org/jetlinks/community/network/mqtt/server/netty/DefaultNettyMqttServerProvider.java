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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.community.network.*;
import org.jetlinks.community.network.security.CertificateManager;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.core.metadata.types.BooleanType;
import org.jetlinks.core.metadata.types.IntType;
import org.jetlinks.core.metadata.types.StringType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PreDestroy;

@Slf4j
@Component
@ConfigurationProperties(prefix = "jetlinks.network.mqtt-server")
public class DefaultNettyMqttServerProvider implements NetworkProvider<NettyMqttServerProperties> {

    private final CertificateManager certificateManager;

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    public DefaultNettyMqttServerProvider(CertificateManager certificateManager) {
        this.certificateManager = certificateManager;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }

    @PreDestroy
    public void shutdown() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @Nonnull
    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_SERVER;
    }

    @Nonnull
    @Override
    public Mono<Network> createNetwork(@Nonnull NettyMqttServerProperties properties) {
        NettyMqttServer server = new NettyMqttServer(properties.getId());
        return initServer(server, properties);
    }

    private Mono<Network> initServer(NettyMqttServer server, NettyMqttServerProperties properties) {
        return createSslContext(properties)
            .flatMap(sslContext -> server.start(properties, bossGroup, workerGroup, sslContext))
            .switchIfEmpty(Mono.defer(() -> server.start(properties, bossGroup, workerGroup, null)))
            .cast(Network.class)
            .onErrorResume(e -> {
                server.setLastError(e.getMessage());
                return Mono.error(e);
            });
    }

    private Mono<SslContext> createSslContext(NettyMqttServerProperties properties) {
        if (!properties.isSecure()) {
            return Mono.empty();
        }
        return certificateManager
            .getCertificate(properties.getCertId())
            .map(cert -> {
                try {
                    return SslContextBuilder
                        .forServer(cert.getKeyManagerFactory())
                        .trustManager(cert.getTrustManagerFactory())
                        .build();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create SSL context", e);
                }
            });
    }

    @Override
    public Mono<Network> reload(@Nonnull Network network, @Nonnull NettyMqttServerProperties properties) {
        log.debug("reload mqtt server[{}]", properties.getId());
        return initServer((NettyMqttServer) network, properties);
    }

    @Nullable
    @Override
    public ConfigMetadata getConfigMetadata() {
        return new DefaultConfigMetadata()
            .add("id", "id", "", new StringType())
            .add("host", "本地地址", "", new StringType())
            .add("port", "本地端口", "", new IntType())
            .add("publicHost", "公网地址", "", new StringType())
            .add("publicPort", "公网端口", "", new IntType())
            .add("certId", "证书id", "", new StringType())
            .add("secure", "开启TLS", "", new BooleanType())
            .add("maxMessageSize", "最大消息长度", "", new StringType());
    }

    @Nonnull
    @Override
    public Mono<NettyMqttServerProperties> createConfig(@Nonnull NetworkProperties properties) {
        return Mono.defer(() -> {
                NettyMqttServerProperties config = FastBeanCopier.copy(properties.getConfigurations(), new NettyMqttServerProperties());
                config.setId(properties.getId());
                config.validate();
                return Mono.just(config);
            })
            .as(LocaleUtils::transform);
    }

    @Override
    public boolean isReusable() {
        return true;
    }
}
