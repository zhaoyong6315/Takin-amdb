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
 * app_base_data_all
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "AppBaseDataAll对象", description = "t_app_base_data_all")
public class AppBaseDataAll implements Serializable {


    private Integer time;

    @ApiModelProperty(value = "agent_id")
    private String agentId;

    @ApiModelProperty("app_ip")
    private String appIp;

    @ApiModelProperty(value = "app_name")
    private String appName;

    @ApiModelProperty("cpu_cores")
    private Integer cpuCores;

    @ApiModelProperty("cpu_load")
    private BigDecimal cpuLoad;

    @ApiModelProperty("cpu_rate")
    private BigDecimal cpuRate;

    private Integer disk;

    @ApiModelProperty("env_code")
    private String envCode;

    private BigDecimal iowait;

    @ApiModelProperty("is_container_flag")
    private Integer isContainerFlag;

    @ApiModelProperty("log_time")
    private Integer logTime;

    @ApiModelProperty("mem_rate")
    private BigDecimal memRate;

    private Integer memory;

    @ApiModelProperty("net_bandwidth")
    private BigDecimal netBandwidth;

    @ApiModelProperty("net_bandwidth_rate")
    private BigDecimal netBandwidthRate;

    @ApiModelProperty(value = "tenant_app_key")
    private String tenantAppKey;

    @ApiModelProperty(value = "user_id")
    private Integer userId;

    private LocalDateTime createDate;


}
