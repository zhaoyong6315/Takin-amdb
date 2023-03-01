package io.shulie.amdb.controller;


import io.shulie.amdb.common.Response;
import io.shulie.amdb.exception.AmdbExceptionEnums;
import io.shulie.amdb.request.query.ClickhouseQueryRequest;
import io.shulie.amdb.request.query.EnginePressureQuery;
import io.shulie.amdb.service.ClickhouseQueryService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * t_engine_pressure_all 前端控制器
 * </p>
 *
 * @author zhaoyong
 * @since 2023-01-11
 */
@RestController
@RequestMapping("amdb/db/api/enginePressure")
public class EnginePressureAllController {

    @Resource
    private ClickhouseQueryService clickhouseQueryService;

    @RequestMapping(value = "/queryListMap", method = RequestMethod.POST)
    public Response queryListMap(@RequestBody EnginePressureQuery request) {
        if (request.getJobId() == null) {
            return Response.fail(AmdbExceptionEnums.COMMON_EMPTY_PARAM) ;
        }
        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("t_engine_pressure_all");
        if (request.getStartTime() != null) {
            clickhouseQueryRequest.setStartTime(request.getStartTime());
            clickhouseQueryRequest.setStartTimeEqual(true);
        }
        if (request.getEndTime() != null) {
            clickhouseQueryRequest.setEndTime(request.getEndTime());
            clickhouseQueryRequest.setEndTimeEqual(true);
        }
        if (request.getLimit() != null) {
            clickhouseQueryRequest.setLimitRows(request.getLimit());
        }
        if (CollectionUtils.isNotEmpty(request.getGroupByFields())) {
            clickhouseQueryRequest.setAggregateStrategy(request.getFieldAndAlias());
            clickhouseQueryRequest.setGroupByTags(request.getGroupByFields());
            request.getGroupByFields().forEach(group -> {
                if (!clickhouseQueryRequest.getAggregateStrategy().containsKey(group)){
                    clickhouseQueryRequest.getAggregateStrategy().put(group, null);
                }
            });
        } else {
            clickhouseQueryRequest.setFieldAndAlias(request.getFieldAndAlias());
        }
        Map<String, Object> whereFilter = new HashMap<>();
        if (request.getWhereFilter() != null && !request.getWhereFilter().isEmpty()){
            whereFilter.putAll(request.getWhereFilter());
        }
        if (StringUtils.isNotBlank(request.getTransaction())) {
            whereFilter.put("transaction", request.getTransaction());
        }
        whereFilter.put("job_id", request.getJobId());
        clickhouseQueryRequest.setWhereFilter(whereFilter);
        if (request.getOrderByStrategy() != null) {
            clickhouseQueryRequest.setOrderByStrategy(request.getOrderByStrategy());
        }
        return clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest);
    }
}
