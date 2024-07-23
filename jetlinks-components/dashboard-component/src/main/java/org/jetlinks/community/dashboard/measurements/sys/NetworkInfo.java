package org.jetlinks.community.dashboard.measurements.sys;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;


@Getter
@Setter
@AllArgsConstructor(staticName = "of")
public class NetworkInfo implements MonitorInfo<NetworkInfo> {

    private Set<Network> networks;

    @Override
    public String getId() {
        return "network";
    }

    @Override
    public NetworkInfo add(NetworkInfo info) {
        this.networks.addAll(info.getNetworks());
        return this;
    }

    @Override
    public NetworkInfo division(int num) {
        return null;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    static class Network {
        @Schema(description = "标识符")
        private String name;

        @Schema(description = "用户友好名")
        private String displayName;

        @Schema(description = "mac地址")
        private String mac;

        @Schema(description = "是否为虚拟网卡")
        private boolean isVirtual;

        @Schema(description = "网卡是否在运行")
        private boolean state;

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj);
        }
    }
}



