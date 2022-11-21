package io.shulie.amdb.run;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import io.shulie.amdb.adaptors.connector.DataContext;
import io.shulie.amdb.adaptors.instance.model.InstanceModel;
import io.shulie.amdb.adaptors.utils.FlagUtil;
import io.shulie.amdb.common.Response;
import io.shulie.amdb.common.dto.instance.AppInstanceExtDTO;
import io.shulie.amdb.common.dto.instance.ModuleLoadDetailDTO;
import io.shulie.amdb.entity.AppDO;
import io.shulie.amdb.entity.TAmdbAgentConfigDO;
import io.shulie.amdb.entity.TAmdbAppInstanceDO;
import io.shulie.amdb.entity.TAmdbAppInstanceStatusDO;
import io.shulie.amdb.mapper.TAmdbAgentConfigDOMapper;
import io.shulie.amdb.request.query.AppInstanceStatusQueryRequest;
import io.shulie.amdb.service.AppInstanceService;
import io.shulie.amdb.service.AppInstanceStatusService;
import io.shulie.amdb.service.AppService;
import io.shulie.amdb.utils.StringUtil;
import io.shulie.surge.data.deploy.pradar.parser.utils.Md5Utils;
import io.shulie.takin.sdk.kafka.MessageReceiveCallBack;
import io.shulie.takin.sdk.kafka.MessageReceiveService;
import io.shulie.takin.sdk.kafka.entity.MessageEntity;
import io.shulie.takin.sdk.kafka.impl.KafkaSendServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.assertj.core.util.Lists;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import tk.mybatis.mapper.entity.Example;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class InstanceKafkaMonitor implements ApplicationListener<ApplicationStartedEvent> {

    Map<String, String> instancePathMap = new HashMap<>(100);

    private static final String INSTANCE_PATH = "/config/log/pradar/client/";

    /**
     * path-> appName+"#"+ip+"#"+pid
     */
    private static final Map<String, String> INSTANCEID_CACHE = new HashMap<>();

    @Resource
    private AppService appService;
    @Resource
    private AppInstanceService appInstanceService;
    @Resource
    private AppInstanceStatusService appInstanceStatusService;
    @Resource
    private TAmdbAgentConfigDOMapper agentConfigDOMapper;


    @Override
    public void onApplicationEvent(ApplicationStartedEvent applicationStartedEvent) {
        log.info("开始准备监听config-log-pradar-client的消息");
        Executors.newCachedThreadPool().execute(() -> {
            MessageReceiveService messageReceiveService = new KafkaSendServiceFactory().getKafkaMessageReceiveInstance();
            log.info("开始准备监听config-log-pradar-client的消息，开始设置topic");
            messageReceiveService.receive(Lists.newArrayList("stress-test-config-log-pradar-client"), new MessageReceiveCallBack() {
                @Override
                public void success(MessageEntity messageEntity) {
                    log.info("收到config-log-pradar-client的消息，" + messageEntity);
                    Map entityBody = messageEntity.getBody();
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

                    String instancePath = INSTANCE_PATH + appName + "/" + agentId + "/" + tenantAppKey + "/" + envCode;

                    String body = JSON.toJSONString(entityBody);

                    DataContext<InstanceModel> dataContext = new DataContext<>();
                    dataContext.setPath(instancePath);
                    InstanceModel instanceModel = JSON.parseObject(body, InstanceModel.class);
                    instanceModel.setAppName(appName.toString());
                    dataContext.setModel(instanceModel);

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

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate((new Runnable() {
            @Override
            public void run() {
//                long currentTimeMillis = System.currentTimeMillis();
//                instancePathMap.forEach((instancePath, md5) -> {
//                    //连续两分钟没有接收到该节点的信息，认为当前节点已下线
//                    DataContext<InstanceModel> dataContext = new DataContext<>();
//                    dataContext.setPath(instancePath);
//                    process(dataContext);
//                    instancePathMap.remove(instancePath);
//                });
                batchOffline();
            }
        }),20,60, TimeUnit.SECONDS);
    }


    public Object process(DataContext<InstanceModel> dataContext) {
        String path[] = dataContext.getPath().replaceAll(INSTANCE_PATH, "").split("/");
        String appName = path[0];
        InstanceModel instanceModel = dataContext.getModel();
        String oldInstanceKey = INSTANCEID_CACHE.get(dataContext.getPath());
        if (instanceModel != null) {
            instanceModel.buildDefaultValue(appName);

            updateAppAndInstance(appName, instanceModel, oldInstanceKey);
            //配置DO更新
            updateAgentConfig(appName, instanceModel);

            INSTANCEID_CACHE.put(dataContext.getPath(), instanceModel.cacheKey());
        } else {
            // 说明节点被删除，执行实例下线
            if (oldInstanceKey != null) {
                instanceOffline(oldInstanceKey);

                String agentId = path[1];
                removeConfig(appName, agentId);
            }
        }
        return null;
    }

    private void updateAppAndInstance(String appName, InstanceModel instanceModel, String oldInstanceKey) {
        Date curr = new Date();
        // 判断APP记录是否存在
        AppDO params = new AppDO();
        params.setAppName(appName);
        params.setUserAppKey(instanceModel.getTenantAppKey());
        params.setEnvCode(instanceModel.getEnvCode());
        AppDO inDataBaseAppDo = appService.selectOneByParam(params);
        if (inDataBaseAppDo == null) {
            inDataBaseAppDo = getTamdAppCreateModelByInstanceModel(appName, instanceModel, curr);
            // insert,拿到返回ID
            Response insertResponse = appService.insert(inDataBaseAppDo);
            inDataBaseAppDo.setId(NumberUtils.toLong(insertResponse.getData() + ""));
        } else {
            inDataBaseAppDo = getTamdAppUpdateModelByInstanceModel(instanceModel, inDataBaseAppDo, curr);
            // update
            appService.update(inDataBaseAppDo);
        }
        //更新实例信息
        TAmdbAppInstanceDO appInstanceDO = queryOldInstance(appName, instanceModel, oldInstanceKey);
        if (appInstanceDO == null) {
            TAmdbAppInstanceDO amdbAppInstance = getTamdAppInstanceCreateModelByInstanceModel(inDataBaseAppDo, instanceModel, curr);
            appInstanceService.insert(amdbAppInstance);
        } else {
            TAmdbAppInstanceDO amdbAppInstance = getTamdAppInstanceUpdateModelByInstanceModel(inDataBaseAppDo, instanceModel, appInstanceDO, curr);
            appInstanceService.update(amdbAppInstance);
        }

        // 处理agent状态映射关系，与 探针 status 处理一致
        dealWithProbeStatusModel(instanceModel);
        TAmdbAppInstanceStatusDO instanceStatus = createInstanceStatus(appName, instanceModel);
        appInstanceStatusService.insertOrUpdate(instanceStatus);
    }

    private TAmdbAppInstanceDO queryOldInstance(String appName, InstanceModel instanceModel, String oldInstanceKey) {
        // 判断instance是否存在
        TAmdbAppInstanceDO selectParam = new TAmdbAppInstanceDO();
        // 如果有更新则需要拿到原来的唯一值检索
        if (StringUtils.isBlank(oldInstanceKey)) {
            selectParam.setAppName(appName);
            selectParam.setIp(instanceModel.getAddress());
            // 老的没有，说明服务重启缓存重置，这种情况下只能根据AgentID来更新
            selectParam.setAgentId(instanceModel.getAgentId());
            selectParam.setUserAppKey(instanceModel.getTenantAppKey());
            selectParam.setEnvCode(instanceModel.getEnvCode());
        } else {
            String instanceInfo[] = oldInstanceKey.split("#");
            selectParam.setAppName(instanceInfo[0]);
            selectParam.setIp(instanceInfo[1]);
            selectParam.setPid(instanceInfo[2]);
            selectParam.setUserAppKey(instanceInfo[3]);
            selectParam.setEnvCode(instanceInfo[4]);
        }
        return appInstanceService.selectOneByParam(selectParam);
    }

    /**
     * 创建APP对象
     *
     * @param appName
     * @param instanceModel
     * @param curr
     * @return
     */
    private AppDO getTamdAppCreateModelByInstanceModel(String appName, InstanceModel instanceModel, Date curr) {
        AppDO tAmdbApp = new AppDO();
        tAmdbApp.setAppName(appName);
        if (instanceModel.getExt() == null || instanceModel.getExt().length() == 0) {
            instanceModel.setExt("{}");
        }
        Map<String, Object> ext = JSON.parseObject(instanceModel.getExt());
        ext.put("jars", instanceModel.getJars());
        tAmdbApp.setExt(JSON.toJSONString(ext));
        tAmdbApp.setCreator("");
        tAmdbApp.setCreatorName("");
        tAmdbApp.setModifier("");
        tAmdbApp.setModifierName("");
        tAmdbApp.setGmtCreate(curr);
        tAmdbApp.setGmtModify(curr);
        tAmdbApp.setUserAppKey(instanceModel.getTenantAppKey());
        tAmdbApp.setEnvCode(instanceModel.getEnvCode());
        tAmdbApp.setUserId(instanceModel.getUserId());
        return tAmdbApp;
    }

    /**
     * APP更新对象
     *
     * @param instanceModel
     * @param inDataBaseAppDo
     * @param curr
     * @return
     */
    private AppDO getTamdAppUpdateModelByInstanceModel(InstanceModel instanceModel, AppDO inDataBaseAppDo, Date curr) {
        Map<String, Object> ext = JSON.parseObject(inDataBaseAppDo.getExt() == null ? "{}" : inDataBaseAppDo.getExt());
        if (ext == null) {
            ext = new HashMap<>();
        }
        ext.put("jars", instanceModel.getJars());
        inDataBaseAppDo.setExt(JSON.toJSONString(ext));
        inDataBaseAppDo.setGmtModify(curr);
        inDataBaseAppDo.setUserAppKey(instanceModel.getTenantAppKey());
        inDataBaseAppDo.setEnvCode(instanceModel.getEnvCode());
        inDataBaseAppDo.setUserId(instanceModel.getUserId());
        return inDataBaseAppDo;
    }

    /**
     * 实例创建对象
     *
     * @param amdbApp
     * @param instanceModel
     * @param curr
     * @return
     */
    private TAmdbAppInstanceDO getTamdAppInstanceCreateModelByInstanceModel(AppDO amdbApp, InstanceModel instanceModel, Date curr) {
        TAmdbAppInstanceDO amdbAppInstance = new TAmdbAppInstanceDO();
        amdbAppInstance.setAppName(amdbApp.getAppName());
        amdbAppInstance.setAppId(amdbApp.getId());
        amdbAppInstance.setAgentId(instanceModel.getAgentId());
        amdbAppInstance.setIp(instanceModel.getAddress());
        amdbAppInstance.setPid(instanceModel.getPid());
        amdbAppInstance.setAgentVersion(instanceModel.getAgentVersion());
        amdbAppInstance.setMd5(instanceModel.getMd5());
        amdbAppInstance.setAgentLanguage(instanceModel.getAgentLanguage());

        //租户相关
        amdbAppInstance.setUserId(instanceModel.getUserId());
        amdbAppInstance.setUserAppKey(instanceModel.getTenantAppKey());
        amdbAppInstance.setEnvCode(instanceModel.getEnvCode());


        AppInstanceExtDTO ext = new AppInstanceExtDTO();
        Map<String, String> simulatorConfig = JSON.parseObject(instanceModel.getSimulatorFileConfigs(), new TypeReference<Map<String, String>>() {
        });
        ext.setSimulatorConfigs(simulatorConfig);
        ext.setModuleLoadResult(instanceModel.getModuleLoadResult());
        List<ModuleLoadDetailDTO> moduleLoadDetailDTOS = JSON.parseArray(instanceModel.getModuleLoadDetail(), ModuleLoadDetailDTO.class);
        ext.setModuleLoadDetail(moduleLoadDetailDTOS);
        ext.setErrorMsgInfos("{}");
        ext.setGcType(instanceModel.getGcType());
        ext.setHost(instanceModel.getHost());
        ext.setStartTime(instanceModel.getStartTime());
        ext.setJdkVersion(instanceModel.getJdkVersion());
        amdbAppInstance.setHostname(instanceModel.getHost());
        amdbAppInstance.setExt(JSON.toJSONString(ext));
        amdbAppInstance.setFlag(0);
        amdbAppInstance.setFlag(FlagUtil.setFlag(amdbAppInstance.getFlag(), 1, true));
        if (instanceModel.isStatus()) {
            amdbAppInstance.setFlag(FlagUtil.setFlag(amdbAppInstance.getFlag(), 2, true));
        } else {
            amdbAppInstance.setFlag(FlagUtil.setFlag(amdbAppInstance.getFlag(), 2, false));
        }
        amdbAppInstance.setCreator("");
        amdbAppInstance.setCreatorName("");
        amdbAppInstance.setModifier("");
        amdbAppInstance.setModifierName("");
        amdbAppInstance.setGmtCreate(curr);
        amdbAppInstance.setGmtModify(curr);
        return amdbAppInstance;
    }

    /**
     * 实例更新对象
     *
     * @param amdbApp
     * @param instanceModel
     * @param oldAmdbAppInstance
     * @param curr
     * @return
     */
    private TAmdbAppInstanceDO getTamdAppInstanceUpdateModelByInstanceModel(AppDO amdbApp, InstanceModel instanceModel, TAmdbAppInstanceDO oldAmdbAppInstance, Date curr) {
        oldAmdbAppInstance.setAppName(amdbApp.getAppName());
        oldAmdbAppInstance.setAppId(amdbApp.getId());
        oldAmdbAppInstance.setAgentId(instanceModel.getAgentId());
        oldAmdbAppInstance.setIp(instanceModel.getAddress());
        oldAmdbAppInstance.setPid(instanceModel.getPid());
        oldAmdbAppInstance.setAgentVersion(instanceModel.getAgentVersion());
        oldAmdbAppInstance.setMd5(instanceModel.getMd5());
        oldAmdbAppInstance.setAgentLanguage(instanceModel.getAgentLanguage());
        oldAmdbAppInstance.setHostname(instanceModel.getHost());
        AppInstanceExtDTO ext = new AppInstanceExtDTO();
        Map<String, String> simulatorConfig = JSON.parseObject(instanceModel.getSimulatorFileConfigs(), new TypeReference<Map<String, String>>() {
        });
        ext.setSimulatorConfigs(simulatorConfig);
        ext.setModuleLoadResult(instanceModel.getModuleLoadResult());
        List<ModuleLoadDetailDTO> moduleLoadDetailDTOS = JSON.parseArray(instanceModel.getModuleLoadDetail(), ModuleLoadDetailDTO.class);
        ext.setModuleLoadDetail(moduleLoadDetailDTOS);
        ext.setErrorMsgInfos("{}");
        ext.setGcType(instanceModel.getGcType());
        ext.setHost(instanceModel.getHost());
        ext.setStartTime(instanceModel.getStartTime());
        ext.setJdkVersion(instanceModel.getJdkVersion());
        oldAmdbAppInstance.setExt(JSON.toJSONString(ext));
        // 改为在线状态
        oldAmdbAppInstance.setFlag(FlagUtil.setFlag(oldAmdbAppInstance.getFlag(), 1, true));
        // 设置Agent状态
        if (instanceModel.isStatus()) {
            // 设为正常状态
            oldAmdbAppInstance.setFlag(FlagUtil.setFlag(oldAmdbAppInstance.getFlag(), 2, true));
        } else {
            // 设置为异常状态
            oldAmdbAppInstance.setFlag(FlagUtil.setFlag(oldAmdbAppInstance.getFlag(), 2, false));
        }
        oldAmdbAppInstance.setGmtModify(curr);
        return oldAmdbAppInstance;
    }

    private void updateTime(DataContext<InstanceModel> dataContext) {
        InstanceModel instanceModel = dataContext.getModel();
        String agentId = instanceModel.getAgentId();
        String envCode = instanceModel.getEnvCode();
        String appName = instanceModel.getAppName();
        String address = instanceModel.getAddress();
        String pid = instanceModel.getPid();
        String tenantAppKey = instanceModel.getTenantAppKey();

        TAmdbAppInstanceDO selectParam = new TAmdbAppInstanceDO();
        // 如果AgentId被修改，则用原先的ID来更新
        selectParam.setAppName(appName);
        selectParam.setIp(address);
        selectParam.setPid(pid);
        TAmdbAppInstanceDO amdbAppInstanceDO = appInstanceService.selectOneByParam(selectParam);
        if (amdbAppInstanceDO != null) {
            amdbAppInstanceDO.setGmtModify(new Date());
            appInstanceService.update(amdbAppInstanceDO);
        }

        TAmdbAppInstanceStatusDO tAmdbAppInstanceStatusDO = new TAmdbAppInstanceStatusDO();
        tAmdbAppInstanceStatusDO.setAppName(appName);
        tAmdbAppInstanceStatusDO.setAgentId(agentId);
        tAmdbAppInstanceStatusDO.setEnvCode(envCode);
        tAmdbAppInstanceStatusDO.setUserAppKey(tenantAppKey);
        TAmdbAppInstanceStatusDO exist = appInstanceStatusService.selectOneByParam(tAmdbAppInstanceStatusDO);
        if (exist != null){
            exist.setGmtModify(new Date());
            appInstanceStatusService.update(exist);
        }

        Example example = new Example(TAmdbAppInstanceStatusDO.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("appName", appName);
        criteria.andEqualTo("agentId", agentId);
        List<TAmdbAgentConfigDO> tAmdbAgentConfigDOS = agentConfigDOMapper.selectByExample(example);
        if (!CollectionUtils.isEmpty(tAmdbAgentConfigDOS)){
            tAmdbAgentConfigDOS.forEach(config -> {
                config.setGmtCreate(new Date());
                agentConfigDOMapper.updateByPrimaryKey(config);
            });
        }
    }

    private void batchOffline(){
        Calendar instance = Calendar.getInstance();
        instance.add(Calendar.MINUTE, -3);
        appInstanceService.batchOfflineByTime(instance.getTime());
        appInstanceStatusService.batchOfflineByTime(instance.getTime());
        agentConfigDOMapper.batchOfflineByTime(instance.getTime());
    }

    /**
     * 执行实例下线
     *
     * @param oldInstanceKey
     */
    private void instanceOffline(String oldInstanceKey) {
        TAmdbAppInstanceDO selectParam = new TAmdbAppInstanceDO();
        // 如果AgentId被修改，则用原先的ID来更新
        String instanceInfo[] = oldInstanceKey.split("#");
        selectParam.setAppName(instanceInfo[0]);
        selectParam.setIp(instanceInfo[1]);
        selectParam.setPid(instanceInfo[2]);
        TAmdbAppInstanceDO amdbAppInstanceDO = appInstanceService.selectOneByParam(selectParam);
        if (amdbAppInstanceDO == null) {
            return;
        }
        amdbAppInstanceDO.setFlag(FlagUtil.setFlag(amdbAppInstanceDO.getFlag(), 1, false));
        appInstanceService.update(amdbAppInstanceDO);

        //如果探针版本是1.0的老版本,进行删除,新版本探针通过监听status节点的变化来做状态同步即可,否则在IP和PID重启后不变的情况下,会出现状态同步异常 by 人寿测试环境
        //对于老版本,其probe_status字段始终为空,可用此条件判断是否老探针创建的数据
        AppInstanceStatusQueryRequest request = new AppInstanceStatusQueryRequest();
        request.setAppName(selectParam.getAppName());
        request.setIp(selectParam.getIp());
        request.setPid(selectParam.getPid());
        request.setProbeStatus("");
        appInstanceStatusService.deleteByParams(request);
    }


    private void dealWithProbeStatusModel(InstanceModel instanceModel) {
        //探针状态转换
        if (instanceModel != null) {
            if (instanceModel.getSimulatorVersion() == null) {
                log.info("探针版本为null");
                instanceModel.setSimulatorVersion("未知版本");
            }
            switch (StringUtil.parseStr(instanceModel.getAgentStatus())) {
                case "INSTALLED":
                    instanceModel.setAgentStatus("0");
                    break;
                case "UNINSTALL":
                    instanceModel.setAgentStatus("1");
                    break;
                case "INSTALLING":
                    instanceModel.setAgentStatus("2");
                    break;
                case "UNINSTALLING":
                    instanceModel.setAgentStatus("3");
                    break;
                case "INSTALL_FAILED":
                    instanceModel.setAgentStatus("4");
                    break;
                case "UNINSTALL_FAILED":
                    instanceModel.setAgentStatus("5");
                    break;
                default:
                    log.info("agent未知状态:{}", StringUtil.parseStr(instanceModel.getAgentStatus()));
                    instanceModel.setAgentStatus("99");
            }
        }
    }

    // 这里应该设置 探针 的状态、错误码、错误信息
    private TAmdbAppInstanceStatusDO createInstanceStatus(String appName, InstanceModel instanceModel) {
        TAmdbAppInstanceStatusDO instanceStatus = new TAmdbAppInstanceStatusDO();
        instanceStatus.setAppName(appName);
        instanceStatus.setIp(instanceModel.getAddress());
        instanceStatus.setAgentId(instanceModel.getAgentId());
        instanceStatus.setPid(instanceModel.getPid());
        instanceStatus.setAgentErrorCode(instanceModel.getErrorCode());
        instanceStatus.setAgentErrorMsg(instanceModel.getErrorMsg());
        instanceStatus.setAgentStatus(instanceModel.getAgentStatus());
        instanceStatus.setEnvCode(instanceModel.getEnvCode());
        instanceStatus.setUserAppKey(instanceModel.getTenantAppKey());
        instanceStatus.setUserId(instanceModel.getUserId());
        return instanceStatus;
    }

    private void updateAgentConfig(String appName, InstanceModel instanceModel) {
        removeConfig(appName, instanceModel.getAgentId());

        List<TAmdbAgentConfigDO> agentConfigs = buildAgentConfig(appName, instanceModel);

        if (agentConfigs != null && !agentConfigs.isEmpty()) {
            saveConfig(agentConfigs);
        }
    }

    private void saveConfig(List<TAmdbAgentConfigDO> agentConfigs) {
        agentConfigDOMapper.batchInsert(agentConfigs);
    }

    private void removeConfig(String appName, String agentId) {
        Objects.requireNonNull(appName);
        Objects.requireNonNull(agentId);
        Example example = new Example(TAmdbAppInstanceStatusDO.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("appName", appName);
        criteria.andEqualTo("agentId", agentId);
        agentConfigDOMapper.deleteByExample(example);
    }

    private List<TAmdbAgentConfigDO> buildAgentConfig(String appName, InstanceModel instanceModel) {
        String agentAndSimulatorFileConfigsCheck = instanceModel.getSimulatorFileConfigsCheck();
        if (StringUtils.isBlank(agentAndSimulatorFileConfigsCheck)) {
            return null;
        }
        // 配置生效状态
        Map<String, String> configCheck = JSON.parseObject(agentAndSimulatorFileConfigsCheck, new TypeReference<Map<String, String>>() {
        });
        List<TAmdbAgentConfigDO> ret = new ArrayList<>();
        String agentId = instanceModel.getAgentId();

        // agent配置项
        String agentConfig = instanceModel.getAgentFileConfigs();
        if (StringUtils.isNotBlank(agentConfig)) {
            Map<String, String> configKeyValues = JSON.parseObject(agentConfig, new TypeReference<Map<String, String>>() {
            });
            configKeyValues.forEach((configKey, configValue) -> {
                TAmdbAgentConfigDO configDO = new TAmdbAgentConfigDO();
                configDO.setAgentId(agentId);
                configDO.setAppName(appName);
                configDO.setConfigKey(configKey);
                configDO.setConfigValue(configValue);
                String status = configCheck.get(configKey);
                if (status == null) {
                    status = configCheck.get("status");
                }
                configDO.setStatus(Boolean.parseBoolean(status));
                configDO.setUserAppKey(instanceModel.getTenantAppKey());
                configDO.setEnvCode(instanceModel.getEnvCode());
                configDO.setUserId(instanceModel.getUserId());
                ret.add(configDO);
            });
        }
        // 探针配置项
        String simulatorConfigs = instanceModel.getSimulatorFileConfigs();
        if (StringUtils.isNotBlank(simulatorConfigs)) {
            Map<String, String> simulatorConfigsKeyValues = JSON.parseObject(simulatorConfigs, new TypeReference<Map<String, String>>() {
            });
            simulatorConfigsKeyValues.forEach((configKey, configValue) -> {
                TAmdbAgentConfigDO configDO = new TAmdbAgentConfigDO();
                configDO.setAgentId(agentId);
                configDO.setAppName(appName);
                configDO.setConfigKey(configKey);
                configDO.setConfigValue(configValue);
                String status = configCheck.get(configKey);
                if (status == null) {
                    status = configCheck.get("status");
                }
                configDO.setStatus(Boolean.parseBoolean(status));
                configDO.setUserAppKey(instanceModel.getTenantAppKey());
                configDO.setEnvCode(instanceModel.getEnvCode());
                configDO.setUserId(instanceModel.getUserId());
                ret.add(configDO);
            });
        }
        return ret;
    }

}
