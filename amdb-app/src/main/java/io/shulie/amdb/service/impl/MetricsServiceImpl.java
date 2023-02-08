/*
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shulie.amdb.service.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.shulie.amdb.adaptors.common.Pair;
import io.shulie.amdb.common.Response;
import io.shulie.amdb.dao.ITraceDao;
import io.shulie.amdb.entity.TAMDBPradarLinkConfigDO;
import io.shulie.amdb.entity.TAmdbPradarLinkEdgeDO;
import io.shulie.amdb.entity.TraceMetricsAll;
import io.shulie.amdb.mapper.PradarLinkConfigMapper;
import io.shulie.amdb.mapper.PradarLinkEdgeMapper;
import io.shulie.amdb.request.query.*;
import io.shulie.amdb.response.metrics.MetricsDetailResponse;
import io.shulie.amdb.response.metrics.MetricsResponse;
import io.shulie.amdb.service.ClickhouseQueryService;
import io.shulie.amdb.service.MetricsService;
import io.shulie.surge.data.deploy.pradar.parser.PradarLogType;
import io.shulie.surge.data.sink.clickhouse.ClickHouseSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tk.mybatis.mapper.entity.Example;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MetricsServiceImpl implements MetricsService {

    @Resource
    PradarLinkEdgeMapper pradarLinkEdgeMapper;
    @Resource
    PradarLinkConfigMapper pradarLinkConfigMapper;
    @Resource
    private ClickHouseSupport clickHouseSupport;
    @Autowired
    @Qualifier("traceDaoImpl")
    ITraceDao traceDao;
    @Resource
    private ClickhouseQueryService clickhouseQueryService;

    @Override
    public Map<String, MetricsResponse> getMetrics(MetricsQueryRequest request) {
        Map<String, MetricsResponse> responseList = new HashMap<>();

        //遍历每一个tag进行查询
        request.getTagMapList().forEach(tagMap -> {
            List<Map<String, Object>> resultList = new ArrayList<>();
            String tableName = request.getMeasurementName().endsWith("_all") ? request.getMeasurementName() : request.getMeasurementName() + "_all";
            //构造聚合指标查询sql
            String aggrerateSql = "select " + parseAliasFields(request.getFieldMap()) +
                    " from  " + tableName + " where " + parseWhereFilter(tagMap) + " and time >= " +
                    request.getStartTime() + " and time < " + request.getEndTime() + " ";
            aggrerateSql += parseGroupBy(request.getGroups());

            log.info("聚合指标查询sql:{}", aggrerateSql);
            List<Map<String, Object>> aggrerateResultList = clickHouseSupport.queryForList(aggrerateSql);
            //构造非聚合指标查询sql
            List<Map<String, Object>> nonAggrerateResult = null;
            if (request.getNonAggrerateFieldMap() != null) {
                String nonAggrerateSql = "select " + parseAliasFields(request.getNonAggrerateFieldMap()) +
                        " from  " + tableName + " where " + parseWhereFilter(tagMap) + " and time >= " +
                        request.getStartTime() + " and time < " + request.getEndTime();
                log.info("非聚合指标查询sql:{}", nonAggrerateSql);
                nonAggrerateResult = clickHouseSupport.queryForList(nonAggrerateSql);
            }
            aggrerateResultList.forEach(aggrerateResult -> {
                List<Map<String, Object>> tmpResult = new ArrayList<>();
                MetricsResponse response = new MetricsResponse();
                LinkedHashMap<String, String> resultTagMap = new LinkedHashMap<>(tagMap);
                if (request.getGroups() != null) {
                    String[] groupFields = request.getGroups().split(",");
                    for (String groupField : groupFields) {
                        resultTagMap.put(groupField, aggrerateResult.get(groupField) == null ? null : aggrerateResult.get(groupField).toString());
                    }
                }
                response.setTags(resultTagMap);
                response.setTimes(request.getEndTime() - request.getStartTime());
                tmpResult.add(aggrerateResult);
                resultList.addAll(tmpResult);
                response.setValue(tmpResult);
                responseList.put(StringUtils.join(response.getTags().values(), "|"), response);
            });

            //如果要合并结果集,上面的聚合查询不能带group,可能会造成数据匹配错乱
            if (!resultList.isEmpty() && CollectionUtils.isNotEmpty(nonAggrerateResult) && request.getGroups() == null) {
                nonAggrerateResult.forEach(result -> {
                    Map<String, Object> aggrerateMap = resultList.get(0);
                    aggrerateMap.putAll(result);
                });
            }
        });
        log.info("指标查询合并结果:{}", responseList);
        return responseList;
    }

    @Override
    public Pair<List<MetricsDetailResponse>, Integer> metricsDetailes(MetricsDetailQueryRequest request) {
        List<MetricsDetailResponse> resultList2 = new ArrayList<>();
        if (this.cache1.size() == 0 || this.cache2.size() == 0) {
            refreshCache();
        }
        StringBuffer sb = new StringBuffer();
        String service_interface, method_interface;
        String service_active, method_active;
        String appName = request.getAppName();
        String startTime = request.getStartTime();
        String endTime = request.getEndTime();
        String realEndTime = "";
        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("trace_metrics_all");
        Map<String, String> fieldAndAlias = new HashMap<>();
        fieldAndAlias.put("appName", null);
        fieldAndAlias.put("service", null);
        fieldAndAlias.put("method", null);
        fieldAndAlias.put("middlewareName", null);
        fieldAndAlias.put("total", null);
        fieldAndAlias.put("rpcType", null);
        clickhouseQueryRequest.setFieldAndAlias(fieldAndAlias);
        Map<String, Object> whereFilter = new HashMap<>();
        //必填字段
        if (StringUtils.isNotBlank(startTime)){
            clickhouseQueryRequest.setStartTime(Long.parseLong(startTime));
        }
        if (StringUtils.isNotBlank(endTime)){
            clickhouseQueryRequest.setEndTime(Long.parseLong(endTime));
        }
        whereFilter.put("appName", appName);
        //流量类型
        if (request.getClusterTest() != -1) {
            whereFilter.put("clusterTest", 0 == request.getClusterTest() ? "false" : "true");
        }
        //租户，环境隔离
        if (StringUtils.isNotBlank(request.getTenantAppKey())) {
            whereFilter.put("tenantAppKey", request.getTenantAppKey());
        }
        if (StringUtils.isNotBlank(request.getEnvCode())) {
            whereFilter.put("envCode", request.getEnvCode());
        }
        //服务名称
        String serviceName = request.getServiceName();
        if (StringUtils.isNotBlank(serviceName)) {
            String[] info = serviceName.split("#");
            service_interface = info[0];
            whereFilter.put("service", service_interface);
            if (info.length == 2) {
                method_interface = info[1];
                whereFilter.put("method", method_interface);
            }
        }
        //活动名称
        if (StringUtils.isNotBlank(request.getActivityName())) {
            String[] info = request.getActivityName().split("#");
            service_active = info[0];
            method_active = info[1];
            whereFilter.put("service", service_active);
            whereFilter.put("method", method_active);
        }
        clickhouseQueryRequest.setWhereFilter(whereFilter);
        clickhouseQueryRequest.setOrderByStrategy(1);

        List<TraceMetricsAll> traceMetricsAlls = clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest, TraceMetricsAll.class);
        List<TraceMetrics> resultList = new ArrayList<>();
        traceMetricsAlls.forEach(traceMetricsAll -> {
            TraceMetrics traceMetrics = new TraceMetrics();
            traceMetrics.setTime(traceMetricsAll.getTime() + "");
            traceMetrics.setAppName(traceMetricsAll.getAppName());
            traceMetrics.setAvgRt(traceMetricsAll.getAvgRt() == null ? 0 : traceMetricsAll.getAvgRt().intValue());
            traceMetrics.setAvgTps(traceMetricsAll.getAvgTps() == null ? 0 : traceMetricsAll.getAvgTps().intValue());
            traceMetrics.setClusterTest("true".equals(traceMetricsAll.getClusterTest()));
            traceMetrics.setE2eErrorCount(traceMetricsAll.getE2eErrorCount());
            traceMetrics.setE2eSuccessCount(traceMetricsAll.getE2eSuccessCount());
            traceMetrics.setEdgeId(traceMetricsAll.getEdgeId());
            traceMetrics.setErrorCount(traceMetricsAll.getErrorCount());
            traceMetrics.setHitCount(traceMetricsAll.getHitCount());
            traceMetrics.setLog_time(traceMetricsAll.getLogTime());
            traceMetrics.setMaxRt(traceMetricsAll.getMaxRt());
            traceMetrics.setMethod(traceMetricsAll.getMethod());
            traceMetrics.setMiddlewareName(traceMetricsAll.getMiddlewareName());
            traceMetrics.setRpcType(StringUtils.isNotBlank(traceMetricsAll.getRpcType()) ? Integer.parseInt(traceMetricsAll.getRpcType()) : 0);
            traceMetrics.setService(traceMetricsAll.getService());
            traceMetrics.setSqlStatement(traceMetricsAll.getSqlStatement());
            traceMetrics.setSuccessCount(traceMetricsAll.getSuccessCount());
            traceMetrics.setTotal(traceMetricsAll.getTotal());
            traceMetrics.setTotalCount(traceMetricsAll.getTotalCount());
            traceMetrics.setTotalRt(traceMetricsAll.getTotalRt());
            traceMetrics.setTotalTps(traceMetricsAll.getTotalTps());
            traceMetrics.setTraceId(traceMetricsAll.getTraceId());
            resultList.add(traceMetrics);
        });
        // 提前分页总户数判断
        int size = resultList.size();
        Integer current = request.getCurrentPage();
        Integer pageSize = request.getPageSize();
        if (size <= current * pageSize) {
            return new Pair<>(new ArrayList<>(0), size);
        }

        //计算数据间隔描述，为计算tps准备
        int diffInMillis = 300;
        String maxTime = resultList.get(0).getTime();
        String minTime = resultList.get(size - 1).getTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date firstDate = sdf.parse(minTime.replace("T", " ").replace("+08:00", ""));
            Date secondDate = sdf.parse(maxTime.replace("T", " ").replace("+08:00", ""));
            realEndTime = sdf.format(secondDate);
            diffInMillis = ((int) (Math.abs(secondDate.getTime() - firstDate.getTime()) / 1000));
        } catch (ParseException e) {
            e.printStackTrace();
        }

        //去重(因为influxDB的group by支持不够),计算指标,计算业务活动入口
        String sql;
        Set<String> allActiveList = new HashSet<>();
        for (TraceMetrics temp : resultList) {
            MetricsDetailResponse response = new MetricsDetailResponse();
            response.setAppName(temp.getAppName());
            response.setMiddlewareName(temp.getMiddlewareName());
            response.setService(temp.getService());
            response.setMethod(temp.getMethod());
            response.setServiceAndMethod(temp.getService() + "#" + temp.getMethod());
            response.setRpcType(temp.getRpcType());
            String key;
            if (!resultList2.contains(response)) {
                key = request.getAppName() + "#" + response.getServiceAndMethod();
                List<String> value = this.cache2.getIfPresent(key);
                if (value != null) {
                    response.setActiveList(value);
                    allActiveList.addAll(value);
                }
                ClickhouseQueryRequest traceMetricsAllQuery = new ClickhouseQueryRequest();
                traceMetricsAllQuery.setMeasurement("trace_metrics_all");
                if (StringUtils.isNotBlank(startTime)){
                    traceMetricsAllQuery.setStartTime(Long.parseLong(startTime));
                }
                if (StringUtils.isNotBlank(endTime)){
                    traceMetricsAllQuery.setEndTime(Long.parseLong(endTime));
                }

                Map<String, Object> traceMetricsWhereFilter = new HashMap<>();
                traceMetricsAllQuery.setWhereFilter(traceMetricsWhereFilter);
                traceMetricsAllQuery.setOrderByStrategy(1);
                List<String> groupByTags = new ArrayList<>();
                traceMetricsAllQuery.setGroupByTags(groupByTags);
                Map<String, String> traceMetricsAggregateStrategy = new HashMap<>();
                traceMetricsAllQuery.setAggregateStrategy(traceMetricsAggregateStrategy);
                traceMetricsAggregateStrategy.put("sum(totalCount)", "requestCount");
                traceMetricsAggregateStrategy.put("sum(totalCount)/" + diffInMillis, "tps");
                traceMetricsAggregateStrategy.put("sum(successCount)/sum(totalCount)", "successRatio");
                traceMetricsAggregateStrategy.put("sum(totalRt)/sum(totalCount)", "responseConsuming");
                if (StringUtils.isNotBlank(startTime)){
                    traceMetricsAllQuery.setStartTime(Long.parseLong(startTime));
                }
                if (StringUtils.isNotBlank(endTime)){
                    traceMetricsAllQuery.setEndTime(Long.parseLong(endTime));
                }
                traceMetricsWhereFilter.put("appName", response.getAppName());
                traceMetricsWhereFilter.put("service", response.getService());
                traceMetricsWhereFilter.put("method", response.getMethod());

                groupByTags.add("appName");
                groupByTags.add("service");
                groupByTags.add("method");
                //计算指标
                if (request.getClusterTest() != -1) {
                    traceMetricsWhereFilter.put("clusterTest", 0 == request.getClusterTest() ? "false" : "true");
                }
                //拼接租户，环境隔离
                if (StringUtils.isNotBlank(request.getTenantAppKey())) {
                    traceMetricsWhereFilter.put("tenantAppKey", request.getTenantAppKey());
                }
                if (StringUtils.isNotBlank(request.getEnvCode())) {
                    traceMetricsWhereFilter.put("envCode", request.getEnvCode());
                }
                Response<List<Map<String, Object>>> listResponse = clickhouseQueryService.queryObjectByConditions(traceMetricsAllQuery);
                List<Map<String, Object>> mapList = listResponse.getData();

                float requestCount = 0f;
                float tps = 0f;
                float successRatio = 0f;
                float responseConsuming = 0f;
                if (CollectionUtils.isNotEmpty(mapList)) {
                    requestCount = Float.parseFloat(mapList.get(0).get("requestCount").toString());
                    tps = Float.parseFloat(mapList.get(0).get("tps").toString());
                    successRatio = Float.parseFloat(mapList.get(0).get("successRatio").toString());
                    responseConsuming = Float.parseFloat(mapList.get(0).get("responseConsuming").toString());
                    response.setRequestCount(requestCount);                 //总请求次数
                    response.setTps(tps);                                   //tps
                    response.setResponseConsuming(responseConsuming);       //耗时
                    response.setSuccessRatio(successRatio);                 //成功率
                    response.setStartTime(startTime);
                    response.setEndTime(realEndTime);
                    response.setTimeGap(diffInMillis);
                    resultList2.add(response);
                }
            }
        }
        int responseSize = resultList2.size();
        // 是否可以分页
        if (responseSize <= current * pageSize) {
            return new Pair<>(new ArrayList<>(0), responseSize);
        }
        List<MetricsDetailResponse> currentPageList = sortAndPaging(resultList2, request);
        currentPageList.get(0).setAllActiveList(allActiveList);
        return new Pair<>(currentPageList, responseSize);
    }

    //根据业务活动找服务列表,linkId    /   appname+server+method
    private Cache<String, List<String>> cache1 = CacheBuilder.newBuilder().maximumSize(90000).expireAfterWrite(1, TimeUnit.HOURS).build();
    //查询关联业务活动
    private Cache<String, List<String>> cache2 = CacheBuilder.newBuilder().maximumSize(90000).expireAfterWrite(1, TimeUnit.HOURS).build();

    @PostConstruct
    public void initCache() {
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> refreshCache(), 0, 5, TimeUnit.MINUTES);
    }

    private void refreshCache() {
        try {
            List<TAmdbPradarLinkEdgeDO> allList1 = this.getAllEdge1();
            for (TAmdbPradarLinkEdgeDO edge : allList1) {
                if (this.cache1.getIfPresent(edge.getLinkId()) == null) {
                    this.cache1.put(edge.getLinkId(), new ArrayList<>());
                }
                //可能存在重复的service
                this.cache1.getIfPresent(edge.getLinkId()).add(edge.getService());
            }
            List<TAmdbPradarLinkEdgeDO> allList2 = this.getAllEdge2(allList1);
            for (TAmdbPradarLinkEdgeDO edge : allList2) {
                if (this.cache2.getIfPresent(edge.getService()) == null) {
                    this.cache2.put(edge.getService(), new ArrayList<>());
                }

                if (!this.cache2.getIfPresent(edge.getService()).contains(edge.getExtend())) {
                    this.cache2.getIfPresent(edge.getService()).add(edge.getExtend());
                }
            }
        } catch (Throwable e) {
            log.error("缓存操作失败{}", ExceptionUtils.getStackTrace(e));
            e.printStackTrace();
        }
    }

    //@Select("select distinct CONCAT(edge.app_name,'#',edge.service,'#',edge.method) as service,\n" +
    //        "CONCAT(config.app_name,'#',config.service,'#',config.method) as extend\n" +
    //        "from (select * from t_amdb_pradar_link_edge  where app_name != 'UNKNOWN' and log_type != '2') edge,t_amdb_pradar_link_config config where config.link_id = edge.link_id ")
    public List<TAmdbPradarLinkEdgeDO> getAllEdge2(List<TAmdbPradarLinkEdgeDO> edges) {
        // 获取所有的边 edges 已经去重过了
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.EMPTY_LIST;
        }
        // 读取所有配置
        List<TAMDBPradarLinkConfigDO> linkList = pradarLinkConfigMapper.selectAll();
        // 按linkId分组
        Map<String, List<TAMDBPradarLinkConfigDO>> linkIdMap = linkList.stream().collect(Collectors.groupingBy(TAMDBPradarLinkConfigDO::getLinkId));
        for (int i = 0; i < edges.size(); i++) {
            TAmdbPradarLinkEdgeDO edgeDO = edges.get(i);
            String linkId = edgeDO.getLinkId();
            List<TAMDBPradarLinkConfigDO> configDOs = linkIdMap.get(linkId);
            if (CollectionUtils.isNotEmpty(configDOs)) {
                TAMDBPradarLinkConfigDO configDO = configDOs.get(0);
                String extend = configDO.getAppName() + "#" + configDO.getService() + "#" + configDO.getMethod();
                edgeDO.setExtend(extend);
            }
        }
        return edges;
    }

    // 循环去获取
    // @Select("select distinct CONCAT(app_name,'#',service,'#',method) as service,link_id as linkId from t_amdb_pradar_link_edge where app_name != 'UNKNOWN' and log_type != '2'")
    public List<TAmdbPradarLinkEdgeDO> getAllEdge1() {
        Map<String, String> duMap = Maps.newHashMap();
        List<TAmdbPradarLinkEdgeDO> linkEdgeList = Lists.newArrayList();
        //查询所有的链路配置,链路配置不多，可以全量查询
        List<String> configIds = pradarLinkConfigMapper.selectConfigId();
        if (CollectionUtils.isEmpty(configIds)) {
            return Collections.EMPTY_LIST;
        }
        List<List<String>> subList = Lists.partition(configIds, 10);
        for (int i = 0; i < subList.size(); i++) {
            Example example = new Example(TAmdbPradarLinkEdgeDO.class);
            Example.Criteria linkIdCriteria = example.createCriteria();
            linkIdCriteria.andIn("linkId", subList.get(i));
            linkIdCriteria.andIn("logType", getDefaultLogTypes());
            linkIdCriteria.andGreaterThan("gmtModify", LocalDateTime.now().minusDays(1));
            List<TAmdbPradarLinkEdgeDO> edges = pradarLinkEdgeMapper.selectByExample(example);
            if (CollectionUtils.isNotEmpty(edges)) {
                if (edges.size() > 10000) {
                    log.warn("当前链路边太多,请检查 linkIds {}", StringUtils.join(subList.get(i), ","));
                }
                for (int j = 0; j < edges.size(); j++) {
                    TAmdbPradarLinkEdgeDO edgeDO = edges.get(j);
                    String appName = edgeDO.getAppName();
                    if (StringUtils.isNotBlank(appName) && appName.equals("UNKNOWN")) {
                        continue;
                    }
                    String service = edgeDO.getAppName() + "#" + edgeDO.getService() + "#" + edgeDO.getMethod();
                    String linkId = edgeDO.getLinkId();
                    if (!duMap.containsKey(service)) {
                        duMap.put(service, "");

                        // 设置值
                        TAmdbPradarLinkEdgeDO tmp = new TAmdbPradarLinkEdgeDO();
                        tmp.setService(service);
                        tmp.setLinkId(linkId);
                        linkEdgeList.add(tmp);
                    }
                }
            }
        }
        return linkEdgeList;
    }

    private List<String> getDefaultLogTypes() {
        List<String> logTypes = new ArrayList<>();
        logTypes.add(String.valueOf(PradarLogType.LOG_TYPE_BIZ));
        logTypes.add(String.valueOf(PradarLogType.LOG_TYPE_TRACE));
        logTypes.add(String.valueOf(PradarLogType.LOG_TYPE_RPC_SERVER));
        logTypes.add(String.valueOf(PradarLogType.LOG_TYPE_RPC_LOG));
        logTypes.add(String.valueOf(PradarLogType.LOG_TYPE_FLOW_ENGINE));
        return logTypes;
    }

    /**
     * 处理查询列
     *
     * @param fieldsMap
     * @return
     */
    private String parseAliasFields(Map<String, String> fieldsMap) {
        List<String> aliasList = new ArrayList<>();
        fieldsMap.forEach((k, v) -> {
            aliasList.add(v + " as " + k);
        });
        return StringUtils.join(aliasList, ",");
    }

    /**
     * 处理查询条件
     *
     * @param tagMap
     * @return
     */
    private String parseWhereFilter(Map<String, String> tagMap) {
        List<String> inFilterList = new ArrayList<>();
        List<String> orFilterList = new ArrayList<>();
        tagMap.forEach((k, v) -> {
            if (("edgeId".equals(k) || "nodeId".equals(k)) && v.contains(",")) {
                //rpc服务的method含有形参,亦是用逗号分割,暂时过滤其他字段的or查询,只支持edgeId
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("(");
                for (String single : v.split(",")) {
                    stringBuilder.append(k + "='" + single + "'").append(" or ");
                }
                stringBuilder.delete(stringBuilder.lastIndexOf(" or "), stringBuilder.length());
                stringBuilder.append(")");
                orFilterList.add(stringBuilder.toString());
            } else {
                inFilterList.add(k + "='" + v + "'");
            }
        });
        if (orFilterList.isEmpty()) {
            return StringUtils.join(inFilterList, " and ");
        }
        return StringUtils.join(inFilterList, " and ") + " and " + StringUtils.join(orFilterList, " and ");
    }

    /**
     * 处理分组条件
     *
     * @param groupFields
     * @return
     */
    private String parseGroupBy(String groupFields) {
        if (StringUtils.isBlank(groupFields)) {
            return "";
        }
        return "group by " + groupFields;
    }

    /**
     * 时间格式化
     *
     * @param timestamp
     * @return
     */
    private long formatTimestamp(long timestamp) {
        String temp = timestamp + "000000";
        return Long.valueOf(temp);
    }

    /**
     * 浮点数据格式化
     *
     * @param data
     * @return
     */
    private BigDecimal formatDouble(Double data) {
        if (data == null) {
            return new BigDecimal("0");
        }
        BigDecimal b = BigDecimal.valueOf(data);
        return b.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /***整理列名、行数据***/
    private List<TraceMetrics> getQueryData(List<String> columns, List<List<Object>> values) {
        List<TraceMetrics> lists = new ArrayList<>();
        for (List<Object> list : values) {
            TraceMetrics info = new TraceMetrics();
            BeanWrapperImpl bean = new BeanWrapperImpl(info);
            for (int i = 0; i < list.size(); i++) {
                String propertyName = columns.get(i);//字段名
                Object value = list.get(i);//相应字段值
                bean.setPropertyValue(propertyName, value);
            }
            lists.add(info);
        }
        return lists;
    }

    // 按照控制台传递过来的关注列表(优先排序)、排序字段、以及分页参数进行排序和分页，
    private List<MetricsDetailResponse> sortAndPaging(List<MetricsDetailResponse> allResult, MetricsDetailQueryRequest request) {
        List<String> focusOn = request.getAttentionList();
        String[] orderBy = request.getOrderBy().split(" ");
        String orderName = orderBy[0];
        String orderType;
        if (orderBy.length < 2) {
            orderType = "desc";
        } else {
            orderType = orderBy[1];
        }
        boolean orderByAsc = "asc".equalsIgnoreCase(orderType);
        List<MetricsDetailResponse> responseList = allResult.stream().sorted((left, right) -> {
            boolean leftAttention = focusOn.contains(left.getServiceAndMethod());
            boolean rightAttention = focusOn.contains(right.getServiceAndMethod());
            if ((leftAttention && rightAttention) || (!leftAttention && !rightAttention)) {
                float result = 0f;
                switch (orderName) {
                    case "QPS":
                        result = left.getTps() - right.getTps();
                        break;
                    case "TPS":
                        result = left.getRequestCount() - right.getRequestCount();
                        break;
                    case "RT":
                        result = left.getResponseConsuming() - right.getResponseConsuming();
                        break;
                    case "SUCCESSRATE":
                        result = left.getSuccessRatio() - right.getSuccessRatio();
                        break;
                }
                if (result == 0) {
                    return 0;
                } else {
                    int diff = result > 0 ? 1 : -1;
                    return orderByAsc ? diff : -diff;
                }
            } else {
                return leftAttention ? -1 : 1;
            }
        }).collect(Collectors.toList());
        Integer current = request.getCurrentPage();
        Integer pageSize = request.getPageSize();
        int limit = Integer.min((current + 1) * pageSize, responseList.size());
        List<MetricsDetailResponse> responses = new ArrayList<>(pageSize);
        for (int i = current * pageSize; i < limit; i++) {
            responses.add(responseList.get(i));
        }
        return responses;
    }

    public String entranceFromChickHouse(MetricsFromInfluxdbQueryRequest request) {
        String startTime = request.getStartTime();
        String endTime = request.getEndTime();
        String in_appName = request.getInAppName();          //入口应用
        String in_service = request.getInService();          //入口接口
        String in_method = request.getInMethod();            //入口方法
        String sql1 = "select DISTINCT entranceId from t_trace_all " +
                "where startDate between '" + startTime + "' and  '" + endTime + "' " +
                "and parsedServiceName ='" + in_service + "' and parsedMethod = '" + in_method + "' " +
//                "and logType in ('1','2') "+
                "and (" +
                " (logType = '1' and rpcType in('0','1','3','7'))" +             //
                " or (logType = '2' and rpcType in('1','3','4','5','6','8'))" +  //
                " or (logType = '3' and rpcType in('0','1','3'))" +              //
                " )" +
                " and parsedAppName = '" + in_appName + "' ";
        if (StringUtils.isNotBlank(request.getTenantAppKey())) {
            sql1 += " and userAppKey='" + request.getTenantAppKey() + "' ";
        }
        if (StringUtils.isNotBlank(request.getEnvCode())) {
            sql1 += " and envCode='" + request.getEnvCode() + "' ";
        }
        List<Map<String, Object>> entranceIdList = traceDao.queryForList(sql1);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> temp : entranceIdList) {
            String tempEntranceId = temp.get("entranceId") != null && StringUtils.isNotBlank(temp.get("entranceId").toString()) ? temp.get("entranceId").toString() : "empty";
            if (sb.length() != 0) {
                sb.append(",");
            }
            sb.append("'" + tempEntranceId + "'");
        }
        if (StringUtils.isBlank(sb.toString())) {
            sb.append("'empty'");
        }
        return sb.toString();
    }

    public Map<String, Object> metricsFromChickHouse(MetricsFromInfluxdbQueryRequest request) {
        String startTime = request.getStartTime();
        String endTime = request.getEndTime();
        int timeGap = request.getTimeGap();
        String entranceStr = request.getEntranceStr();
        int clusterTest = request.getClusterTest();             //-1,混合  0,业务  1,压测
        String f_appName = request.getFromAppName();            //上游应用
        String middlewareName = request.getMiddlewareName();    //上游应用

        String t_appName = request.getAppName();                //应用
        String t_service = request.getService();                //接口
        String t_method = request.getMethod();                  //方法
        StringBuilder where1 = new StringBuilder();
        where1.append(" where startDate between '" + startTime + "' and  '" + endTime + "' ");
        where1.append(" and (" +
                " (logType = '1' and rpcType in('0','1','3','7'))" +             //
                " or (logType = '2' and rpcType in('1','3','4','5','6','8'))" +  //
                " or (logType = '3' and rpcType in('0','1','3'))" +              //
                " ) ");
        if (StringUtils.isNotBlank(f_appName) && !f_appName.endsWith("Virtual")) {
            where1.append(" and upAppName = '" + f_appName + "' ");
        }
        if (StringUtils.isNotBlank(middlewareName) && !"virtual".equals(middlewareName)) {
            where1.append(" and middlewareName = '" + middlewareName + "' ");
        } else {
            //where1.append(" and ((logType = '1' and rpcType in('0','1','3','7')) or middlewareName is null or middlewareName = '')");
        }
        where1.append(" and parsedServiceName ='" + t_service + "' and parsedMethod = '" + t_method + "' " +
                "and parsedAppName = '" + t_appName + "' ");
        //区分租户，环境隔离
        if (StringUtils.isNotBlank(request.getTenantAppKey())) {
            where1.append(" and userAppKey='" + request.getTenantAppKey() + "' ");
        }
        if (StringUtils.isNotBlank(request.getEnvCode())) {
            where1.append(" and envCode='" + request.getEnvCode() + "' ");
        }
        //if(entranceStr.contains("empty")){
        where1.append("and (entranceId in(" + entranceStr + ") or entranceId='' or entranceId is null )");
        //}else{
        //    where1.append("and entranceId in(" + entranceStr + ") ");
        //}

        if (clusterTest != -1) {
            where1.append(" and clusterTest = '" + (0 == clusterTest ? "0" : "1") + "'");
        }
        String selectsql1 = "select sum(toInt32(samplingInterval)) as allTotalCount,\n" +
                "MAX(cost) as allMaxRt,\n" +
                "sum(cost*toInt32(samplingInterval)) as allTotalRt,\n" +
                "(sum(toInt32(samplingInterval))/" + timeGap + ") as allTotalTps\n" +
                "from t_trace_all \n" + where1;
        Map<String, Object> modelList = traceDao.queryForMap(selectsql1);
        if (modelList.get("allTotalCount") == null) {
            modelList.put("allTotalCount", 0);
        }
        if (modelList.get("allMaxRt") == null) {
            modelList.put("allMaxRt", 0);
        }
        if (modelList.get("allTotalRt") == null) {
            modelList.put("allTotalRt", 0);
        }
        if (modelList.get("allTotalTps") == null) {
            modelList.put("allTotalTps", 0);
        }
        modelList.put("realSeconds", timeGap);
        String selectsql2 = "select sum(toInt32(samplingInterval)) as allSuccessCount\n" +
                "from t_trace_all \n" + where1 + " and resultCode in('00','200') ";
        Map<String, Object> successCount = traceDao.queryForMap(selectsql2);
        modelList.putAll(successCount);
        if (modelList.get("allSuccessCount") == null) {
            modelList.put("allSuccessCount", 0);
        }
        return modelList;
    }

    private long getTracePeriod(long startMilli, long endMilli, String eagleId) throws ParseException {
        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("trace_metrics_all");
        clickhouseQueryRequest.setStartTimeEqual(true);
        clickhouseQueryRequest.setStartTime(startMilli);
        clickhouseQueryRequest.setEndTime(endMilli);
        clickhouseQueryRequest.setEndTimeEqual(true);
        Map<String, Object> whereFilter = new HashMap<>();
        clickhouseQueryRequest.setWhereFilter(whereFilter);
        Map<String, String> fieldAndAlias = new HashMap<>();
        clickhouseQueryRequest.setFieldAndAlias(fieldAndAlias);
        fieldAndAlias.put("time", null);
        whereFilter.put("edgeId", eagleId);
        clickhouseQueryRequest.setLimitRows(1);
        clickhouseQueryRequest.setOrderByStrategy(1);
        List<TraceMetricsAll> lastMetricsAlls = clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest, TraceMetricsAll.class);

        clickhouseQueryRequest.setOrderByStrategy(0);
        List<TraceMetricsAll> firstMetricsAlls = clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest, TraceMetricsAll.class);
        if (CollectionUtils.isEmpty(lastMetricsAlls) || CollectionUtils.isEmpty(firstMetricsAlls)) {
            return 0;
        }
        return (lastMetricsAlls.get(0).getTime() - firstMetricsAlls.get(0).getTime()) / 1000;
    }

    public List<Map<String, Object>> metricFromInfluxdb(MetricsFromInfluxdbRequest request) {
        List result = new ArrayList<Map<String, Object>>();
        Set<String> edgeIdSetFromInfluxdb = new HashSet<String>();
        long startMilli = request.getStartMilli();
        long endMilli = request.getEndMilli();
        Boolean metricsType = request.getMetricsType();
        String eagleId = request.getEagleId();
        List<String> eagleIds = request.getEagleIds();

        ClickhouseQueryRequest clickhouseQueryRequest = new ClickhouseQueryRequest();
        clickhouseQueryRequest.setMeasurement("trace_metrics_all");
        clickhouseQueryRequest.setStartTime(startMilli);
        clickhouseQueryRequest.setStartTimeEqual(true);
        clickhouseQueryRequest.setEndTime(endMilli);
        clickhouseQueryRequest.setEndTimeEqual(true);
        Map<String, Object> whereFilter = new HashMap<>();
        clickhouseQueryRequest.setWhereFilter(whereFilter);
        List<String> groupByTags = new ArrayList<>();
        clickhouseQueryRequest.setGroupByTags(groupByTags);
        Map<String, String> aggregateStrategy = new HashMap<>();
        clickhouseQueryRequest.setAggregateStrategy(aggregateStrategy);

        aggregateStrategy.put("edgeId", null);
        aggregateStrategy.put("SUM(successCount)", "allSuccessCount");
        aggregateStrategy.put("SUM(totalCount)", "allTotalCount");
        aggregateStrategy.put("SUM(totalTps)", "allTotalTps");
        aggregateStrategy.put("MAX(maxRt)", "allMaxRt");
        aggregateStrategy.put("SUM(totalRt)", "allTotalRt");
        // 如果不是 混合流量 则需要增加条件
        if (null != metricsType) {
            whereFilter.put("clusterTest", metricsType);
        }
        if (CollectionUtils.isEmpty(eagleIds)) {
            whereFilter.put("edgeId", eagleId);
        } else {
            whereFilter.put("edgeId", eagleIds);
        }
        groupByTags.add("edgeId");
        try {
            Response<List<Map<String, Object>>> listResponse = clickhouseQueryService.queryObjectByConditions(clickhouseQueryRequest);
            List<Map<String, Object>> mapList = listResponse.getData();
            if (CollectionUtils.isNotEmpty(mapList)) {
                mapList.forEach(serie -> {
                    Map<String, Object> resultMap = new HashMap<>();
                    String edgeId = serie.get("edgeId").toString();
                    edgeIdSetFromInfluxdb.add(edgeId);
                    resultMap.put("edgeId", edgeId);
                    resultMap.put("allSuccessCount", Long.parseLong(serie.get("allSuccessCount").toString()));
                    resultMap.put("allTotalCount", Long.parseLong(serie.get("allTotalCount").toString()));
                    resultMap.put("allTotalTps", Long.parseLong(serie.get("allTotalTps").toString()));
                    resultMap.put("allMaxRt", Long.parseLong(serie.get("allMaxRt").toString()));
                    resultMap.put("allTotalRt", Long.parseLong(serie.get("allTotalRt").toString()));
                    long realSeconds = 0;
                    try {
                        realSeconds = getTracePeriod(startMilli, endMilli, edgeId);
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                    resultMap.put("realSeconds", realSeconds);
                    result.add(resultMap);
                });

                //数据补全
                eagleIds.forEach(tmpEagleId -> {
                    if (!edgeIdSetFromInfluxdb.contains(tmpEagleId)) {
                        Map<String, Object> resultMap = new HashMap<>();
                        resultMap.put("edgeId", tmpEagleId);
                        resultMap.put("allSuccessCount", 0L);
                        resultMap.put("allTotalCount", 0L);
                        resultMap.put("allTotalTps", 0L);
                        resultMap.put("allMaxRt", 0L);
                        resultMap.put("allTotalRt", 0L);
                        resultMap.put("realSeconds", 0L);
                        result.add(resultMap);
                    }
                });

            } else {
                throw new IllegalArgumentException("query influxdb result is empty.");
            }
        } catch (Exception e) {
            eagleIds.forEach(tmpEagleId -> {
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("edgeId", tmpEagleId);
                resultMap.put("allSuccessCount", 0L);
                resultMap.put("allTotalCount", 0L);
                resultMap.put("allTotalTps", 0L);
                resultMap.put("allMaxRt", 0L);
                resultMap.put("allTotalRt", 0L);
                resultMap.put("realSeconds", 0L);
                result.add(resultMap);
            });
        }
        return result;
    }
}
