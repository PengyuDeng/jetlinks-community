package org.jetlinks.community.dashboard.measurements.sys;

import java.io.Serializable;

public interface MonitorInfo<T extends MonitorInfo<T>> extends Serializable {

    /**
     * 监控监管标识符
     * @return 标识id
     */
    String getId();

    /**
     *
     * @param info 累加值
     * @return 累加后值
     */
    T add(T info);

    /**
     *
     * @param num 集群个数
     * @return 平均值
     */
    T division(int num);

}
