package org.jetlinks.community.protocol;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.jetlinks.core.ProtocolSupport;
import org.jetlinks.core.message.codec.Transport;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@Generated
@NoArgsConstructor
public class ProtocolDetail {
    @Schema(description = "协议ID")
    private String id;

    @Schema(description = "协议名称")
    private String name;

    @Schema(description = "协议说明")
    private String description;

    private List<TransportDetail> transports;

    public static Mono<ProtocolDetail> of(ProtocolSupport support, String transport) {
        if (!StringUtils.hasText(transport)){
            return of(support);
        }
        return getTransDetail(support, Transport.of(transport))
            .map(detail -> new ProtocolDetail(support.getId(), support.getName(), support.getDescription(), Collections.singletonList(detail)));
    }

    public static Mono<ProtocolDetail> of(ProtocolSupport support) {
        return support
            .getSupportedTransport()
            .flatMap(trans -> getTransDetail(support, trans))
            .collectList()
            .map(details -> new ProtocolDetail(support.getId(), support.getName(), support.getDescription(), details));
    }

    private static Mono<TransportDetail> getTransDetail(ProtocolSupport support, Transport transport) {
        return TransportDetail.of(support, transport);
    }
}




