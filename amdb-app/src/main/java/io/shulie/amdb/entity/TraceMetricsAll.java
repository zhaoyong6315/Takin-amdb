package io.shulie.amdb.entity;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * trace_metrics_all
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "TraceMetricsAll对象", description = "trace_metrics_all")
public class TraceMetricsAll implements Serializable {

    private Long time;

    private String edgeId;

    private String clusterTest;

    private String service;

    private String method;

    private String appName;

    private String rpcType;

    private String middlewareName;

    private String tenantAppKey;

    private String envCode;

    private int totalCount;

    private int successCount;

    private int totalRt;

    private int errorCount;

    private int hitCount;

    private int totalTps;

    private int total;

    private int e2eSuccessCount;

    private int e2eErrorCount;

    private int maxRt;

    private BigDecimal avgRt;

    private BigDecimal avgTps;

    private String traceId;

    private String sqlStatement;

    @ApiModelProperty("log_time")
    private String logTime;

    private LocalDateTime createDate;


}
