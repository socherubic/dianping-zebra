package com.dianping.zebra.monitor.spring;

import com.dianping.cat.Cat;
import com.dianping.zebra.group.Constants;
import com.dianping.zebra.group.config.DataSourceConfigManager;
import com.dianping.zebra.group.config.DataSourceConfigManagerFactory;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;
import com.dianping.zebra.group.exception.DalException;
import com.dianping.zebra.group.exception.IllegalConfigException;
import com.dianping.zebra.group.jdbc.GroupDataSource;
import com.dianping.zebra.group.util.StringUtils;
import com.dianping.zebra.monitor.model.DataSourceInfo;
import com.dianping.zebra.monitor.util.LionUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.unidal.helper.Files;
import org.unidal.helper.Urls;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Dozer on 8/13/14.
 */
public class DataSourceAutoReplacer implements BeanFactoryPostProcessor, PriorityOrdered {
	private static final String C3P0_CLASS_NAME = "com.mchange.v2.c3p0.ComboPooledDataSource";

	private static final String DPDL_CLASS_NAME = "com.dianping.dpdl.sql.DPDataSource";

	private static final String GROUP_CLASS_NAME = "com.dianping.zebra.group.jdbc.GroupDataSource";

	private static final Log logger = LogFactory.getLog(DataSourceAutoReplacer.class);

	private static boolean hasProcessd;

	private Set<String> c3p0Ds = new HashSet<String>();

	private Map<String, String> canReplacedDatabase = new HashMap<String, String>();

	private Set<String> dpdlDs = new HashSet<String>();

	private Set<String> groupds = new HashSet<String>();

	private DefaultListableBeanFactory listableBeanFactory = null;

	private Set<String> otherDs = new HashSet<String>();

	private Pattern pattern = Pattern.compile("^jdbc:([a-zA-Z0-9]+)://([^/]+)/([^?]+).*$");

	private void buildDataSourceInfo(DataSourceInfo info, BeanDefinition bean) {
		getJdbcUrlInfo(info, LionUtil.getLionValueFromBean(bean, "jdbcUrl"));
		info.setUsername(LionUtil.getLionValueFromBean(bean, "user"));
		info.setInitPoolSize(LionUtil.getLionValueFromBean(bean, "initialPoolSize"));
		info.setMaxPoolSize(LionUtil.getLionValueFromBean(bean, "maxPoolSize"));
		info.setMinPoolSize(LionUtil.getLionValueFromBean(bean, "minPoolSize"));
	}

	private String callHttp(String url) throws IOException {
		InputStream inputStream = Urls.forIO().connectTimeout(1000).readTimeout(5000).openStream(url);
		return Files.forIO().readFrom(inputStream, "utf-8");
	}

	private boolean canReplace(DataSourceInfo info) {
		String configString = LionUtil.getLionConfig("groupds.autoreplace.database");

		if (StringUtils.isBlank(info.getDatabase()) || StringUtils.isBlank(configString)) {
			return false;
		}

		List<String> dataBases = Arrays.asList(configString.split(","));

		for (String key : dataBases) {
			canReplacedDatabase.put(key.toLowerCase(), key);
		}

		return canReplacedDatabase.containsKey(info.getDatabase());
	}

	private Set<PropertyValue> getC3P0PropertyValues(BeanDefinition writeDsBean, DataSourceInfo info,
			boolean isFromDpdl) {
		Set<PropertyValue> properties = new HashSet<PropertyValue>();

		if (!writeDsBean.getBeanClassName().equals(C3P0_CLASS_NAME)) {
			return properties;
		}

		buildDataSourceInfo(info, writeDsBean);

		if ("mysql".equals(info.getType()) && canReplace(info)) {
			String jdbcRef = isFromDpdl ? canReplacedDatabase.get(info.getDatabase()) : info.getDatabase() + ".single";
			String groupConfig = LionUtil.getLionConfig(String.format("groupds.%s.mapping", jdbcRef));
			if (!StringUtils.isBlank(groupConfig)) {
				properties.add(new PropertyValue("jdbcRef", jdbcRef));
				properties.add(new PropertyValue("jdbcUrlExtra", parseUrlExtra(info.getUrl())));

				Set<String> ignoreList = getGroupDataSourceIgnoreProperties();
				for (PropertyValue property : writeDsBean.getPropertyValues().getPropertyValues()) {
					if (ignoreList.contains(property.getName())) {
						continue;
					}
					properties.add(property);
				}
			}
		}

		return properties;
	}

	private BeanDefinition getDpdlWriteDsBean(BeanDefinition dataSourceDefinition) {
		String writeDsBeanName = ((TypedStringValue) dataSourceDefinition.getPropertyValues().getPropertyValue("writeDS")
				.getValue()).getValue();

		c3p0Ds.remove(writeDsBeanName);
		BeanDefinition writeDsBean = listableBeanFactory.getBeanDefinition(writeDsBeanName);

		PropertyValue pv = dataSourceDefinition.getPropertyValues().getPropertyValue("readDS");
		if (pv != null && pv.getValue() != null) {
			ManagedMap map = (ManagedMap) pv.getValue();
			for (Object item : map.keySet()) {
				c3p0Ds.remove(((TypedStringValue) item).getValue());
			}
		}

		return writeDsBean;
	}

	private Set<String> getGroupDataSourceIgnoreProperties() {
		Set<String> result = new HashSet<String>();
		result.add("jdbcUrl");
		result.add("password");
		result.add("user");
		result.add("driverClass");
		return result;
	}

	private void getJdbcUrlInfo(DataSourceInfo info, String url) {
		if (url != null) {
			info.setUrl(url);

			Matcher m = pattern.matcher(url);
			if (m.find()) {
				info.setType(m.group(1).toLowerCase());
				info.setDatabase(m.group(3).toLowerCase());
			}
		}
	}

	@Override
	public int getOrder() {
		// 必须在DataSourceAutoMonitor之前执行
		return Ordered.LOWEST_PRECEDENCE - 2;
	}

	private String parseUrlExtra(String url) {
		int index = url.lastIndexOf("?");
		if (index >= 0 && index < url.length() - 2) {
			return url.substring(index + 1);
		}
		return null;
	}

	@Override
	public synchronized void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		if (hasProcessd) {
			return;
		}
		hasProcessd = true;

		listableBeanFactory = (DefaultListableBeanFactory) beanFactory;
		String[] beanDefinitionNames = listableBeanFactory.getBeanDefinitionNames();

		for (String beanDefinitionName : beanDefinitionNames) {
			try {
				AbstractBeanDefinition beanDefinition = (AbstractBeanDefinition) listableBeanFactory
						.getBeanDefinition(beanDefinitionName);
				Class<?> beanClazz = beanDefinition.resolveBeanClass(ClassUtils.getDefaultClassLoader());
				if (beanClazz != null && DataSource.class.isAssignableFrom(beanClazz)) {
					if (beanClazz.getName().equals(C3P0_CLASS_NAME)) {
						c3p0Ds.add(beanDefinitionName);
					} else if (beanClazz.getName().equals(DPDL_CLASS_NAME)) {
						dpdlDs.add(beanDefinitionName);
					} else if (beanClazz.getName().equals(GROUP_CLASS_NAME)) {
						groupds.add(beanDefinitionName);
					} else {
						otherDs.add(beanDefinitionName);
					}
				}
			} catch (ClassNotFoundException e) {
			}
		}
		replace();
	}

	private void processC3P0() {
		new DataSourceProcesser().process(c3p0Ds, new DataSourceProcesserTemplate() {
			@Override
			public void process(BeanDefinition dataSourceDefinition, String beanName, DataSourceInfo info) {
				Set<PropertyValue> properties = getC3P0PropertyValues(dataSourceDefinition, info, false);

				if (properties.size() > 0) {
					setGroupDataSourceProperties(dataSourceDefinition, info, properties);

					Cat.logEvent("DAL.BeanFactory", String.format("Replace-%s", beanName));
				} else {
					Cat.logEvent("DAL.BeanFactory", String.format("IgnoreC3P0-%s", beanName));
				}
			}
		});
	}

	private void processDpdl() {
		new DataSourceProcesser().process(dpdlDs, new DataSourceProcesserTemplate() {
			@Override
			public void process(BeanDefinition dataSourceDefinition, String beanName, DataSourceInfo info) {
				BeanDefinition dpdlWriteDsBean = getDpdlWriteDsBean(dataSourceDefinition);
				Set<PropertyValue> properties = getC3P0PropertyValues(dpdlWriteDsBean, info, true);

				if (properties.size() > 0) {
					setGroupDataSourceProperties(dataSourceDefinition, info, properties);

					Cat.logEvent("DAL.BeanFactory", String.format("Replace-%s", beanName));
				} else {
					Cat.logEvent("DAL.BeanFactory", String.format("IgnoreDpdl-%s", beanName));
				}
			}
		});
	}

	private void processGroupDs() {
		new DataSourceProcesser().process(groupds, new DataSourceProcesserTemplate() {
			@Override
			public void process(BeanDefinition dataSourceDefinition, String beanName, DataSourceInfo info) {
				String jdbcRef;
				PropertyValue propertyValue = dataSourceDefinition.getPropertyValues().getPropertyValue("jdbcRef");

				if (propertyValue == null) {
					ConstructorArgumentValues.ValueHolder valueHolder = (ValueHolder) dataSourceDefinition
							.getConstructorArgumentValues().getGenericArgumentValues().get(0);
					jdbcRef = ((TypedStringValue) valueHolder.getValue()).getValue();
				} else {
					jdbcRef = ((TypedStringValue) propertyValue.getValue()).getValue();
				}

				if (!StringUtils.isBlank(jdbcRef)) {
					DataSourceConfigManager manager = DataSourceConfigManagerFactory.getConfigManager(
							Constants.CONFIG_MANAGER_TYPE_REMOTE, jdbcRef, false);
					GroupDataSourceConfig config = manager.getGroupDataSourceConfig();
					if (config.getDataSourceConfigs().size() > 0) {
						DataSourceConfig dsConfig = config.getDataSourceConfigs().values().iterator().next();
						getJdbcUrlInfo(info, dsConfig.getJdbcUrl());
						info.setUsername(dsConfig.getUsername());
					}
				}
			}
		});
	}

	private void processOther() {
		new DataSourceProcesser().process(otherDs, new DataSourceProcesserTemplate() {
			@Override
			public void process(BeanDefinition dataSourceDefinition, String beanName, DataSourceInfo info) {
				Cat.logEvent("DAL.BeanFactory", String.format("IgnoreOther-%s", beanName));
			}
		});
	}

	private void replace() {
		processDpdl();
		processC3P0();
		processGroupDs();
		processOther();
	}

	private void setGroupDataSourceProperties(BeanDefinition dataSourceDefinition, DataSourceInfo info,
			Set<PropertyValue> properties) {
		dataSourceDefinition.setBeanClassName(GroupDataSource.class.getName());
		for (PropertyValue p : dataSourceDefinition.getPropertyValues().getPropertyValues()) {
			dataSourceDefinition.getPropertyValues().removePropertyValue(p);
		}
		for (PropertyValue entity : properties) {
			dataSourceDefinition.getPropertyValues().addPropertyValue(entity.getName(), entity.getValue());
		}
		if (dataSourceDefinition instanceof AbstractBeanDefinition) {
			((AbstractBeanDefinition) dataSourceDefinition).setInitMethodName("init");
			((AbstractBeanDefinition) dataSourceDefinition).setDestroyMethodName("close");
		}
		info.setReplaced(true);
	}

	private void uploadDataSourceInfo(DataSourceInfo info) {
		String url = LionUtil.getLionConfig("zebra.server.heartbeat.url");
		if (StringUtils.isBlank(url)) {
			logger.error("zebra.v2.datasource.upload.url not exists!");
			Cat.logError(new IllegalConfigException("zebra.v2.datasource.upload.url not exists!"));
		} else {
			try {
				callHttp(String.format("%s?%s", url, info.toString()));
			} catch (IOException e) {
				String msg = String.format("Call Zebra-Web failed! [%s]", url);
				logger.error(msg, e);
				Cat.logError("msg", e);
			}
		}
	}

	interface DataSourceProcesserTemplate {
		void process(BeanDefinition bean, String beanName, DataSourceInfo info);
	}

	class DataSourceProcesser {
		public void process(Set<String> ds, DataSourceProcesserTemplate template) {
			for (String beanName : ds) {
				BeanDefinition dataSourceDefinition = listableBeanFactory.getBeanDefinition(beanName);
				DataSourceInfo info = new DataSourceInfo(beanName);
				info.setDataSourceBeanClass(dataSourceDefinition.getBeanClassName());
				if (dataSourceDefinition.getBeanClassName().equals(C3P0_CLASS_NAME)) {
					buildDataSourceInfo(info, dataSourceDefinition);
				}

				try {
					template.process(dataSourceDefinition, beanName, info);
				} catch (Exception e) {
					String msg = String.format("DataSourceProcesser Error! bean:%s  class:%s", info.getDataSourceBeanName(),
							info.getDataSourceBeanClass());
					Exception exp = new DalException(e);
					logger.error(msg, exp);
					Cat.logError(msg, exp);
				}

				uploadDataSourceInfo(info);
			}
		}
	}
}