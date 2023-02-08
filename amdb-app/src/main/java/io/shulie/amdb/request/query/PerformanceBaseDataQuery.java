package io.shulie.amdb.request.query;

import io.shulie.amdb.common.request.AbstractAmdbBaseRequest;
import lombok.Data;

@Data
public class PerformanceBaseDataQuery extends AbstractAmdbBaseRequest {

    private String agentId;

    private String appName;

    private String appIp;

    private Long startTime;

    private Long endTime;

    private Long tenantId;

    private Integer limit;
}
