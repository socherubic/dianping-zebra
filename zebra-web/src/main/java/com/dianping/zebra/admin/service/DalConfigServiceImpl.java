package com.dianping.zebra.admin.service;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.zebra.group.config.DefaultDataSourceConfigManager;
import com.dianping.zebra.util.StringUtils;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DalConfigServiceImpl implements DalConfigService {

    @Autowired
    private LionHttpService m_lionHttpService;

    private String project = "ds";

    @Override
    public boolean generateConfig(String name) {
        String url = String.format("%s.%s.jdbc.url", project, name);
        String user = String.format("%s.%s.jdbc.username", project, name);
        String password = String.format("%s.%s.jdbc.password", project, name);
        String driverClass = String.format("%s.%s.jdbc.driverClass", project, name);
        String properties = String.format("%s.%s.jdbc.properties", project, name);
        String active = String.format("%s.%s.jdbc.active", project, name);

        try {
            m_lionHttpService.createKey(project, url);
            m_lionHttpService.createKey(project, user);
            m_lionHttpService.createKey(project, password);
            m_lionHttpService.createKey(project, driverClass);
            m_lionHttpService.createKey(project, properties);
            m_lionHttpService.createKey(project, active);
        } catch (Exception ignore) {
        }

        try {
            for (String env : m_lionHttpService.getAllEnv()) {
                String originUrl = m_lionHttpService.getConfigByHttp(env, url);
                if (originUrl == null || originUrl.length() == 0) {
                    m_lionHttpService.setConfig(env, url, "jdbc:mysql://{ip}:{port}/{database}?characterEncoding=UTF8");
                } else {
                    m_lionHttpService.setConfig(env, url, originUrl);
                }

                String originUser = m_lionHttpService.getConfigByHttp(env, user);
                if (originUser == null || originUser.length() == 0) {
                    m_lionHttpService.setConfig(env, user, "");
                } else {
                    m_lionHttpService.setConfig(env, user, originUser);
                }

                String originPassword = m_lionHttpService.getConfigByHttp(env, password);
                if (originPassword == null || originPassword.length() == 0) {
                    m_lionHttpService.setConfig(env, password, "");
                } else {
                    m_lionHttpService.setConfig(env, password, originPassword);
                }

                String originDriverClass = m_lionHttpService.getConfigByHttp(env, driverClass);
                if (originDriverClass == null || originDriverClass.length() == 0) {
                    m_lionHttpService.setConfig(env, driverClass, "com.mysql.jdbc.Driver");
                }

                String originProperties = m_lionHttpService.getConfigByHttp(env, properties);
                if (originProperties == null || originProperties.length() == 0) {
                    m_lionHttpService.setConfig(env, properties, "${ds.datasource.properties}");
                }

                String originActive = m_lionHttpService.getConfigByHttp(env, active);
                if (originActive == null || originActive.length() == 0) {
                    m_lionHttpService.setConfig(env, active, "true");
                } else {
                    m_lionHttpService.setConfig(env, active, originActive);
                }
            }
        } catch (Exception t) {
            Cat.logError(t);
            return false;
        }

        return true;
    }

    public GroupConfigModel getDsConfig(String env, String groupId, final String otherkey) {
        try {
            GroupConfigModel result = new GroupConfigModel();
            result.setEnv(env);
            result.setId(groupId);
            result.setConfig(m_lionHttpService.getConfigByHttp(env, getGroupDataSourceKeyById(groupId)));
            final Map<String, DefaultDataSourceConfigManager.ReadOrWriteRole> groupConfig = DefaultDataSourceConfigManager.ReadOrWriteRole
                    .parseConfig(result.getConfig());

            final String originGroupId = groupId.split("\\.")[0];

            HashMap<String, String> configs = m_lionHttpService.getConfigByProject(env, "ds");
            List<String> keys = Lists.newArrayList(Iterables.filter(configs.keySet(), new Predicate<String>() {
                @Override
                public boolean apply(String input) {
                    if (input.matches("^ds\\." + originGroupId + "\\-[a-zA-Z0-9\\-]+\\.jdbc\\.[a-zA-Z0-9]+$")) {
                        return true;
                    }
                    if (StringUtils.isNotBlank(otherkey) && input.matches("^ds\\." + otherkey + "[a-zA-Z0-9\\-]*\\.jdbc\\.[a-zA-Z0-9]+$")) {
                        return true;
                    }
                    for (String key : groupConfig.keySet()) {
                        if (input.matches("^ds\\." + key + "\\.jdbc\\.[a-zA-Z0-9]+$")) {
                            return true;
                        }
                    }
                    return false;
                }
            }));
            Map<String, DsConfigModel> dsConfigMap = new HashMap<String, DsConfigModel>();

            for (String key : keys) {
                String dsKey = getDsIdFromKey(key);
                if (!dsConfigMap.containsKey(dsKey)) {
                    DsConfigModel ds = new DsConfigModel();
                    ds.setId(dsKey);
                    dsConfigMap.put(dsKey, ds);
                }

                DsConfigModel ds = dsConfigMap.get(dsKey);
                ConfigProperty config = new ConfigProperty();
                config.setKey(key);
                config.setValue(configs.get(key));
                config.setNewValue(configs.get(key));
                ds.getProperties().add(config);

                ds.setRole(groupConfig.get(dsKey));
            }

            result.setConfigs(Lists.newArrayList(dsConfigMap.values()));
            return result;
        } catch (IOException e) {
            Cat.logError(e);
            return null;
        }
    }

    private String getDsIdFromKey(String key) {
        String result = key.substring(key.indexOf(".") + 1);
        return result.substring(0, result.indexOf("."));
    }

    private String getGroupDataSourceKeyById(String groupId) {
        return String.format("groupds.%s.mapping", groupId);
    }

    @Override
    public void setProject(String project) {
        this.project = project;
    }

    public void updateDsConfig(GroupConfigModel modal, boolean isForce) {
        Transaction tran = Cat.newTransaction("DsConfigUpdate", modal.getId());
        try {
            String groupKey = getGroupDataSourceKeyById(modal.getId());

            String oldConfig = m_lionHttpService.getConfigByHttp(modal.getEnv(), groupKey);

            m_lionHttpService.setConfig(modal.getEnv(), groupKey, modal.getConfig());
            Cat.logEvent("GroupDsConfigUpdate", String.format("env:%s key:%s from:%s to:%s",
                    modal.getEnv(), groupKey, oldConfig, modal.getConfig()));

            for (DsConfigModel ds : modal.getConfigs()) {
                for (ConfigProperty prop : ds.getProperties()) {
                    if (prop.isDelete()) {
                        m_lionHttpService.setConfig(modal.getEnv(), prop.getKey(), "");
                        Cat.logEvent("DsConfigRemove", String.format("env:%s key:%s ",
                                modal.getEnv(), prop.getKey()));
                        continue;
                    }
                    if (prop.isCreate()) {
                        m_lionHttpService.createKey("ds", prop.getKey());
                    }
                    if (prop.isCreate() || (prop.getNewValue() != null && !prop.getNewValue().equals(prop.getValue()))
                            || isForce) {
                        m_lionHttpService.setConfig(modal.getEnv(), prop.getKey(), prop.getNewValue());
                    }

                    Cat.logEvent("DsConfigUpdate", String.format("env:%s key:%s from:%s to:%s",
                            modal.getEnv(), prop.getKey(), prop.getValue(), prop.getNewValue()));
                }
            }

            tran.setStatus(Message.SUCCESS);
        } catch (IOException e) {
            Cat.logError(e);
            tran.setStatus(e);
        } finally {
            tran.complete();
        }
    }
}