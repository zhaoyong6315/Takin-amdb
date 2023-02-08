package io.shulie.amdb.controller;

import io.shulie.amdb.common.Response;
import io.shulie.amdb.exception.AmdbExceptionEnums;
import io.shulie.amdb.request.query.ClickhouseQueryRequest;
import io.shulie.amdb.request.query.PerformanceBaseDataQuery;
import io.shulie.amdb.service.ClickhouseQueryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * t_performance_base_data_all 前端控制器
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@RestController
@RequestMapping("amdb/db/api/performance")
public class PerformanceBaseDataAllController {

    @Resource
    private ClickhouseQueryService clickhouseQueryService;

    @RequestMapping(value = "/queryList", method = RequestMethod.POST)
    public Response queryList(@RequestBody PerformanceBaseDataQuery request) {
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return Response.fail(AmdbExceptionEnums.COMMON_EMPTY_PARAM);
        }
        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("t_performance_base_data_all");
        clickhouseQueryRequest.setStartTime(request.getStartTime());
        clickhouseQueryRequest.setStartTimeEqual(true);
        clickhouseQueryRequest.setEndTime(request.getEndTime());
        clickhouseQueryRequest.setEndTimeEqual(true);
        clickhouseQueryRequest.setFieldAndAlias(null);
        Map<String, Object> whereFilter = new HashMap<>();
        if (StringUtils.isNotBlank(request.getAppIp())){
            whereFilter.put("app_ip", request.getAppIp());
        }
        if (StringUtils.isNotBlank(request.getAgentId())){
            whereFilter.put("agent_id", request.getAgentId());
        }
        if (StringUtils.isNotBlank(request.getAppName())){
            whereFilter.put("app_name", request.getAppName());
        }
        if (StringUtils.isNotBlank(request.getEnvCode())){
            whereFilter.put("env_code", request.getEnvCode());
        }
        if (request.getTenantId() != null){
            whereFilter.put("tenant_id", request.getTenantId());
        }
        clickhouseQueryRequest.setWhereFilter(whereFilter);
        clickhouseQueryRequest.setLimitRows(request.getLimit());
        clickhouseQueryRequest.setOrderByStrategy(0);
        return clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest);
    }

}
