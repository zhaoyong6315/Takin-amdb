package io.shulie.amdb.controller;

import io.shulie.amdb.common.Response;
import io.shulie.amdb.exception.AmdbExceptionEnums;
import io.shulie.amdb.request.query.AppBaseDataQuery;
import io.shulie.amdb.request.query.ClickhouseQueryRequest;
import io.shulie.amdb.service.ClickhouseQueryService;
import org.apache.commons.collections.CollectionUtils;
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
 * app_base_data_all 前端控制器
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@RestController
@RequestMapping("/amdb/db/api/appBaseData")
public class AppBaseDataAllController {

    @Resource
    private ClickhouseQueryService clickhouseQueryService;

    @RequestMapping(value = "/queryListMap", method = RequestMethod.POST)
    public Response queryListMap(@RequestBody AppBaseDataQuery request) {
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return Response.fail(AmdbExceptionEnums.COMMON_EMPTY_PARAM);
        }
        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("t_app_base_data_all");
        clickhouseQueryRequest.setStartTime(request.getStartTime());
        clickhouseQueryRequest.setStartTimeEqual(true);
        clickhouseQueryRequest.setEndTime(request.getEndTime());
        clickhouseQueryRequest.setEndTimeEqual(true);
        if (CollectionUtils.isNotEmpty(request.getGroupByFields())) {
            Map<String, String> fieldAndAlias = request.getFieldAndAlias();
            request.getGroupByFields().forEach(group -> {
                fieldAndAlias.put(group, null);
            });
            clickhouseQueryRequest.setAggregateStrategy(fieldAndAlias);
            clickhouseQueryRequest.setGroupByTags(request.getGroupByFields());
        } else {
            clickhouseQueryRequest.setFieldAndAlias(request.getFieldAndAlias());
        }
        Map<String, Object> whereFilter = new HashMap<>();
        if (StringUtils.isNotBlank(request.getAgentId())) {
            whereFilter.put("agent_id", request.getAgentId());
        }
        if (StringUtils.isNotBlank(request.getAppName())) {
            whereFilter.put("app_name", request.getAppName());
        }
        if (StringUtils.isNotBlank(request.getAppId())) {
            whereFilter.put("app_ip", request.getAppId());
        }
        if (StringUtils.isNotBlank(request.getTenantAppKey())) {
            whereFilter.put("tenant_app_key", request.getTenantAppKey());
        }
        if (StringUtils.isNotBlank(request.getEnvCode())) {
            whereFilter.put("env_code", request.getEnvCode());
        }
        clickhouseQueryRequest.setWhereFilter(whereFilter);

        return clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest);
    }
}
