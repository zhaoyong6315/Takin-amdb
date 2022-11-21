package io.shulie.amdb.run;

import com.alibaba.fastjson.JSON;
import io.shulie.amdb.adaptors.connector.DataContext;
import io.shulie.amdb.adaptors.instance.model.InstanceStatusModel;
import io.shulie.amdb.entity.TAmdbAppInstanceStatusDO;
import io.shulie.amdb.request.query.AppInstanceStatusQueryRequest;
import io.shulie.amdb.service.AppInstanceStatusService;
import io.shulie.amdb.utils.StringUtil;
import io.shulie.surge.data.deploy.pradar.parser.utils.Md5Utils;
import io.shulie.takin.sdk.kafka.MessageReceiveCallBack;
import io.shulie.takin.sdk.kafka.MessageReceiveService;
import io.shulie.takin.sdk.kafka.entity.MessageEntity;
import io.shulie.takin.sdk.kafka.impl.KafkaSendServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.util.Lists;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class InstanceStatusKafkaMonitor implements ApplicationListener<ApplicationStartedEvent> {

    Map<String, String> instancePathMap = new HashMap<>(100);

    private static final String INSTANCE_STATUS_PATH = "/config/log/pradar/status/";

    /**
     * INSTANCE_STATUS_PATH + "/" + appName + "/" + agentId + "/" + tenantAppKey + "/" + envCode
     */
    private static final Map<String, String> INSTANCE_STATUS_CACHE = new HashMap<>();

    @Resource
    private AppInstanceStatusService appInstanceStatusService;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent applicationStartedEvent) {

        Executors.newCachedThreadPool().execute(() -> {
            MessageReceiveService messageReceiveService = new KafkaSendServiceFactory().getKafkaMessageReceiveInstance();
            messageReceiveService.receive(Lists.newArrayList("stress-test-config-log-pradar-status"), new MessageReceiveCallBack() {
                @Override
                public void success(MessageEntity messageEntity) {
                    Map entityBody = messageEntity.getBody();
                    log.info("接收到stress-test-config-log-pradar-status消息:{}", JSON.toJSONString(entityBody));
                    Object appName = entityBody.get("appName");
                    Object agentId = entityBody.get("agentId");
                    Object tenantAppKey = entityBody.get("tenantAppKey");
                    Object envCode = entityBody.get("envCode");
                    if (appName == null) {
                        log.warn("接收到的节点信息应用名为空，数据出现问题，接收到的数据为数据为:{}", JSON.toJSONString(entityBody));
                        return;
                    }
                    if (agentId == null) {
                        log.warn("接收到的节点信息agentId为空，数据出现问题，接收到的数据为数据为:{}", JSON.toJSONString(entityBody));
                        return;
                    }

                    String instancePath = INSTANCE_STATUS_PATH + appName + "/" + agentId + "/" + tenantAppKey + "/" + envCode;

                    String body = JSON.toJSONString(entityBody);
                    DataContext<InstanceStatusModel> dataContext = new DataContext<>();
                    dataContext.setPath(instancePath);
                    InstanceStatusModel instanceStatusModel = JSON.parseObject(body, InstanceStatusModel.class);
                    dataContext.setModel(instanceStatusModel);
                    if (instancePathMap.containsKey(instancePath)) {
                        //如果应用节点信息没有任何更新，不重复处理本条数据
                        String md5 = Md5Utils.md5(body);
                        if (md5.equals(instancePathMap.get(instancePath))) {
                            updateTime(dataContext);
                            return;
                        }
                    }
                    process(dataContext);
                }

                @Override
                public void fail(String errorMessage) {
                    log.error("节点信息接收kafka消息出现异常，errorMessage:{}", errorMessage);
                }
            });
        });
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                batchOffline();
            }
        },20 ,60 ,TimeUnit.SECONDS);

    }

    private void updateTime(DataContext<InstanceStatusModel> dataContext) {
        InstanceStatusModel model = dataContext.getModel();
        TAmdbAppInstanceStatusDO selectParam = new TAmdbAppInstanceStatusDO();
        // 如果AgentId被修改，则用原先的ID来更新
        selectParam.setAppName(model.getAppName());
        selectParam.setIp(model.getAddress());
        selectParam.setPid(model.getPid());
        selectParam.setUserAppKey(model.getTenantAppKey());
        selectParam.setEnvCode(model.getEnvCode());

        TAmdbAppInstanceStatusDO amdbAppInstanceDO = appInstanceStatusService.selectOneByParam(selectParam);
        if (amdbAppInstanceDO != null) {
            amdbAppInstanceDO.setGmtModify(new Date());
            appInstanceStatusService.update(amdbAppInstanceDO);
        }
    }


    public Object process(DataContext<InstanceStatusModel> dataContext) {
        String path[] = dataContext.getPath().replaceAll(INSTANCE_STATUS_PATH, "").split("/");
        String appName = path[0];
        InstanceStatusModel instanceStatusModel = dataContext.getModel();
        String oldInstanceKey = INSTANCE_STATUS_CACHE.get(dataContext.getPath());

        //如果从zk中获取到的数据模型不为空,则进入更新或者插入逻辑
        if (instanceStatusModel != null) {
            instanceStatusModel.buildDefaultValue(appName);
            //实例DO更新
            updateOrInsertInstanceStatus(appName, instanceStatusModel, oldInstanceKey);
            INSTANCE_STATUS_CACHE.put(dataContext.getPath(), instanceStatusModel.cacheKey());
        } else {
            // 说明节点被删除，执行实例下线
            if (oldInstanceKey != null) {
                log.info("节点:{}已删除", oldInstanceKey);
                instanceOffline(oldInstanceKey);
                //清除缓存
                INSTANCE_STATUS_CACHE.remove(oldInstanceKey);

            }
        }
        return null;
    }

    private void updateOrInsertInstanceStatus(String appName, InstanceStatusModel newInstanceStatusModel, String oldInstanceKey) {

        TAmdbAppInstanceStatusDO selectParam = new TAmdbAppInstanceStatusDO();
        // 如果有更新则需要拿到原来的唯一值检索
        if (StringUtils.isBlank(oldInstanceKey)) {
            selectParam.setAppName(appName);
            selectParam.setIp(newInstanceStatusModel.getAddress());
            // 老的没有，说明服务重启缓存重置，这种情况下只能根据AgentID来更新
            selectParam.setAgentId(newInstanceStatusModel.getAgentId());
        } else {
            String instanceInfo[] = oldInstanceKey.split("#");
            selectParam.setAppName(instanceInfo[0]);
            selectParam.setIp(instanceInfo[1]);
            selectParam.setPid(instanceInfo[2]);
        }

        Date curr = new Date();
        TAmdbAppInstanceStatusDO oldInstanceStatusDO = appInstanceStatusService.selectOneByParam(selectParam);
        if (oldInstanceStatusDO == null) {
            TAmdbAppInstanceStatusDO instanceStatus = getInsertInstanceStatusModel(newInstanceStatusModel, appName, curr);
            appInstanceStatusService.insertOrUpdate(instanceStatus);
        } else {
            TAmdbAppInstanceStatusDO instanceStatus = getUpdateInstanceStatusModel(oldInstanceStatusDO, newInstanceStatusModel, curr);
            appInstanceStatusService.update(instanceStatus);
        }
    }

    /**
     * 创建实例状态对象
     *
     * @param instanceStatusModel
     * @param curr
     * @return
     */
    private TAmdbAppInstanceStatusDO getInsertInstanceStatusModel(InstanceStatusModel instanceStatusModel, String appName, Date curr) {
        TAmdbAppInstanceStatusDO appInstanceStatus = new TAmdbAppInstanceStatusDO();
        appInstanceStatus.setAppName(appName);
        appInstanceStatus.setAgentId(instanceStatusModel.getAgentId());
        appInstanceStatus.setIp(instanceStatusModel.getAddress());
        appInstanceStatus.setPid(instanceStatusModel.getPid());
        appInstanceStatus.setHostname(instanceStatusModel.getHost());
        appInstanceStatus.setAgentLanguage(instanceStatusModel.getAgentLanguage());
        appInstanceStatus.setAgentVersion(instanceStatusModel.getAgentVersion());

        dealWithInstanceStatusModel(instanceStatusModel);

        appInstanceStatus.setProbeVersion(instanceStatusModel.getSimulatorVersion());
        appInstanceStatus.setErrorCode(instanceStatusModel.getErrorCode());
        appInstanceStatus.setErrorMsg(instanceStatusModel.getErrorMsg());
        appInstanceStatus.setProbeStatus(instanceStatusModel.getAgentStatus());

        //租户相关
        appInstanceStatus.setUserId(instanceStatusModel.getUserId());
        appInstanceStatus.setUserAppKey(instanceStatusModel.getTenantAppKey());
        appInstanceStatus.setEnvCode(instanceStatusModel.getEnvCode());

        appInstanceStatus.setJvmArgs(instanceStatusModel.getJvmArgs());
        appInstanceStatus.setJdk(instanceStatusModel.getJdk());
        appInstanceStatus.setGmtCreate(curr);
        appInstanceStatus.setGmtModify(curr);
        return appInstanceStatus;
    }

    private void dealWithInstanceStatusModel(InstanceStatusModel instanceStatusModel) {
        //探针状态转换
        if (instanceStatusModel != null) {
            if (instanceStatusModel.getSimulatorVersion() == null) {
                log.info("探针版本为null");
                instanceStatusModel.setSimulatorVersion("未知版本");
            }
            switch (StringUtil.parseStr(instanceStatusModel.getAgentStatus())) {
                case "INSTALLED":
                    instanceStatusModel.setAgentStatus("0");
                    break;
                case "UNINSTALL":
                    instanceStatusModel.setAgentStatus("1");
                    break;
                case "INSTALLING":
                    instanceStatusModel.setAgentStatus("2");
                    break;
                case "UNINSTALLING":
                    instanceStatusModel.setAgentStatus("3");
                    break;
                case "INSTALL_FAILED":
                    instanceStatusModel.setAgentStatus("4");
                    break;
                case "UNINSTALL_FAILED":
                    instanceStatusModel.setAgentStatus("5");
                    break;
                default:
                    //do nothing
                    log.info("未知状态:{}", StringUtil.parseStr(instanceStatusModel.getAgentStatus()));
                    instanceStatusModel.setAgentStatus("99");
            }
        }
    }


    /**
     * 更新实例状态对象
     *
     * @param oldInstanceStatusDO
     * @param newInstanceStatusModel
     * @param curr
     * @return
     */
    private TAmdbAppInstanceStatusDO getUpdateInstanceStatusModel(TAmdbAppInstanceStatusDO oldInstanceStatusDO, InstanceStatusModel newInstanceStatusModel, Date curr) {
        oldInstanceStatusDO.setAgentId(newInstanceStatusModel.getAgentId());
        oldInstanceStatusDO.setIp(newInstanceStatusModel.getAddress());
        oldInstanceStatusDO.setPid(newInstanceStatusModel.getPid());
        oldInstanceStatusDO.setHostname(newInstanceStatusModel.getHost());
        oldInstanceStatusDO.setAgentLanguage(newInstanceStatusModel.getAgentLanguage());
        oldInstanceStatusDO.setAgentVersion(newInstanceStatusModel.getAgentVersion());

        dealWithInstanceStatusModel(newInstanceStatusModel);

        //租户相关
        oldInstanceStatusDO.setUserId(newInstanceStatusModel.getUserId());
        oldInstanceStatusDO.setUserAppKey(newInstanceStatusModel.getTenantAppKey());
        oldInstanceStatusDO.setEnvCode(newInstanceStatusModel.getEnvCode());

        oldInstanceStatusDO.setProbeVersion(newInstanceStatusModel.getSimulatorVersion());
        oldInstanceStatusDO.setErrorCode(newInstanceStatusModel.getErrorCode());
        oldInstanceStatusDO.setErrorMsg(newInstanceStatusModel.getErrorMsg());
        oldInstanceStatusDO.setProbeStatus(newInstanceStatusModel.getAgentStatus());
        oldInstanceStatusDO.setGmtModify(curr);
        return oldInstanceStatusDO;
    }

    /**
     * 执行实例下线
     *
     * @param oldInstanceKey
     */
    private void instanceOffline(String oldInstanceKey) {
        TAmdbAppInstanceStatusDO selectParam = new TAmdbAppInstanceStatusDO();
        // 如果AgentId被修改，则用原先的ID来更新
        String instanceInfo[] = oldInstanceKey.split("#");
        selectParam.setAppName(instanceInfo[0]);
        selectParam.setIp(instanceInfo[1]);
        selectParam.setPid(instanceInfo[2]);
        selectParam.setUserAppKey(instanceInfo[3]);
        selectParam.setEnvCode(instanceInfo[4]);

        TAmdbAppInstanceStatusDO amdbAppInstanceDO = appInstanceStatusService.selectOneByParam(selectParam);
        if (amdbAppInstanceDO == null) {
            return;
        }

        AppInstanceStatusQueryRequest request = new AppInstanceStatusQueryRequest();
        request.setAppName(selectParam.getAppName());
        request.setIp(selectParam.getIp());
        request.setPid(selectParam.getPid());
        appInstanceStatusService.deleteByParams(request);
    }

    private void batchOffline(){
        Calendar instance = Calendar.getInstance();
        instance.add(Calendar.MINUTE, -3);
        appInstanceStatusService.batchOfflineByTime(instance.getTime());
    }

}
