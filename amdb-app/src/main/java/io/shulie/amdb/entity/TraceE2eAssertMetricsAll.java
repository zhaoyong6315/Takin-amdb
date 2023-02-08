package io.shulie.amdb.entity;


import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * trace_e2e_assert_metrics_all
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "TraceE2eAssertMetricsAll对象", description = "trace_e2e_assert_metrics_all")
public class TraceE2eAssertMetricsAll implements Serializable {

    private Integer time;

    private String nodeId;

    private String exceptionType;

    private String traceId;

    private String parsedAppName;

    private String parsedServiceName;

    private String parsedMethod;

    private String rpcType;

    private Integer totalRt;

    private Integer totalCount;

    private Integer totalQps;

    private BigDecimal qps;

    private BigDecimal rt;

    private Integer successCount;

    private Integer errorCount;

    private String clusterTest;

    private String tenantAppKey;

    private String envCode;

    private LocalDateTime createDate;


}
