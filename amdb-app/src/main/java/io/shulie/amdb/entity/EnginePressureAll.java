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
 * t_engine_pressure_all
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@ApiModel(value = "EnginePressureAll对象", description = "t_engine_pressure_all")
public class EnginePressureAll implements Serializable {


    @ApiModelProperty(value = "time")
    private Integer time;

    @ApiModelProperty(value = "transaction")
    private String transaction;

    @ApiModelProperty("avg_rt")
    private BigDecimal avgRt;

    @ApiModelProperty("avg_tps")
    private BigDecimal avgTps;

    @ApiModelProperty(value = "test_name")
    private String testName;

    private Integer count;

    @ApiModelProperty("create_time")
    private Integer createTime;

    @ApiModelProperty("data_num")
    private Integer dataNum;

    @ApiModelProperty("data_rate")
    private Integer dataRate;

    @ApiModelProperty("fail_count")
    private Integer failCount;

    @ApiModelProperty("sent_bytes")
    private Integer sentBytes;

    @ApiModelProperty("received_bytes")
    private Integer receivedBytes;

    @ApiModelProperty("sum_rt")
    private BigDecimal sumRt;

    private Integer sa;

    @ApiModelProperty("sa_count")
    private Integer saCount;

    @ApiModelProperty("max_rt")
    private BigDecimal maxRt;

    @ApiModelProperty("min_rt")
    private BigDecimal minRt;

    @ApiModelProperty("active_threads")
    private Integer activeThreads;

    @ApiModelProperty("sa_percent")
    private String saPercent;

    private Integer status;

    @ApiModelProperty("success_rate")
    private BigDecimal successRate;

    @ApiModelProperty("job_id")
    private String jobId;

    private LocalDateTime createDate;


}
