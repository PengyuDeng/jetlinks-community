package org.jetlinks.community.dashboard.measurements.sys;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
@ToString
public class SystemInfo implements MonitorInfo<SystemInfo> {

    private CpuInfo cpu;
    private MemoryInfo memory;
    private DiskInfo disk;
    private NetworkInfo networkInfo;

    @Override
    public String getId() {
        return "all";
    }

    @Override
    public SystemInfo add(SystemInfo info) {
        return new SystemInfo(
            this.cpu.add(info.cpu),
            this.memory.add(info.memory),
            this.disk.add(info.disk),
            this.networkInfo.add(info.networkInfo)
        );
    }

    @Override
    public SystemInfo division(int num) {

        return new SystemInfo(
            this.cpu.division(num),
            this.memory.division(num),
            this.disk.division(num),
            this.networkInfo.division(num)
        );
    }
}
