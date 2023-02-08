package io.shulie.amdb.request.query;

import io.shulie.amdb.common.request.AbstractAmdbBaseRequest;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EnginePressureQuery extends AbstractAmdbBaseRequest {

    private Map<String, String> fieldAndAlias;

    private Long startTime;

    private Long endTime;

    private String transaction;

    private Long jobId;

    private Integer limit;

    @ApiModelProperty("排序策略,0:升序;1:降序")
    private Integer orderByStrategy;

    /**
     * groupBy字段
     */
    private List<String> groupByFields;
}
