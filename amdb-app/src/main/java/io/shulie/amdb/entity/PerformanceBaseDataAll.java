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
 * t_performance_base_data_all
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "PerformanceBaseDataAll对象", description = "t_performance_base_data_all")
public class PerformanceBaseDataAll implements Serializable {

    private Long timestamp;

    @ApiModelProperty("total_memory")
    private Long totalMemory;

    @ApiModelProperty("perm_memory")
    private Long permMemory;

    @ApiModelProperty("young_memory")
    private Long youngMemory;

    @ApiModelProperty("old_memory")
    private Long oldMemory;

    @ApiModelProperty("young_gc_count")
    private Integer youngGcCount;

    @ApiModelProperty("full_gc_count")
    private Integer fullGcCount;

    @ApiModelProperty("young_gc_cost")
    private Long youngGcCost;

    @ApiModelProperty("full_gc_cost")
    private Long fullGcCost;

    @ApiModelProperty("cpu_use_rate")
    private BigDecimal cpuUseRate;

    @ApiModelProperty("total_buffer_pool_memory")
    private Long totalBufferPoolMemory;

    @ApiModelProperty("total_no_heap_memory")
    private Long totalNoHeapMemory;

    @ApiModelProperty("thread_count")
    private Integer threadCount;

    @ApiModelProperty("base_id")
    private Long baseId;

    @ApiModelProperty("agent_id")
    private String agentId;

    @ApiModelProperty(value = "app_name")
    private String appName;

    @ApiModelProperty("app_ip")
    private String appIp;

    @ApiModelProperty("process_id")
    private String processId;

    @ApiModelProperty("process_name")
    private String processName;

    @ApiModelProperty("env_code")
    private String envCode;

    @ApiModelProperty("tenant_app_key")
    private String tenantAppKey;

    @ApiModelProperty("tenant_id")
    private Long tenantId;

    private LocalDateTime createDate;

    private Long time;

}
