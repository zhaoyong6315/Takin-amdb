package io.shulie.amdb.request.query;

import io.shulie.amdb.common.request.AbstractAmdbBaseRequest;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AppBaseDataQuery extends AbstractAmdbBaseRequest {

    private Map<String, String> fieldAndAlias;

    private Long startTime;

    private Long endTime;

    private String appName;

    private String appId;

    private String agentId;

    /**
     * groupBy字段
     */
    private List<String> groupByFields;
}
