/**
 * Project: ${zebra-ds-monitor-client.aid}
 * 
 * File Created at 2011-11-9
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.zebra.monitor.spring;

import java.util.Map;
import java.util.Map.Entry;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;

import com.dianping.zebra.group.jdbc.SingleDataSource;
import com.dianping.zebra.monitor.sql.MonitorableDataSource;

/**
 * 自动为spring容器中的所有DataSource添加monitor功能，配置在spring配置文件中即可，如下 <bean class="com.dianping.zebra.monitor.spring.DataSourceAutoMonitor"/>
 * 
 * @author danson.liu
 *
 */
public class DataSourceAutoMonitor implements BeanFactoryPostProcessor, PriorityOrdered {

	private static final Log logger = LogFactory.getLog(DataSourceAutoMonitor.class);

	private static final String ZEBRA_DATA_SOURCE_NAME = "com.dianping.zebra.jdbc.DPDataSource";

	private static final String C3P0_CLASS_NAME = "com.mchange.v2.c3p0.ComboPooledDataSource";

	private int nameId = 0;

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		DefaultListableBeanFactory listableBeanFactory = (DefaultListableBeanFactory) beanFactory;
		String[] beanDefinitionNames = listableBeanFactory.getBeanDefinitionNames();
		for (String beanDefinitionName : beanDefinitionNames) {
			AbstractBeanDefinition beanDefinition = (AbstractBeanDefinition) listableBeanFactory
			      .getBeanDefinition(beanDefinitionName);
			try {
				Class<?> beanClazz = beanDefinition.resolveBeanClass(ClassUtils.getDefaultClassLoader());
				if (beanClazz != null && DataSource.class.isAssignableFrom(beanClazz)) {
					autoReplaceWithMonitorableDataSource(beanDefinitionName, beanDefinition, beanClazz, listableBeanFactory);
				}
			} catch (ClassNotFoundException e) {
			}
		}
		if (logger.isInfoEnabled()) {
			logger.info("All dataSource is auto monitored by zebra ds monitor.");
		}
	}

	private void autoReplaceWithMonitorableDataSource(String beanName, BeanDefinition dataSourceDefinition,
	      Class<?> dataSourceClazz, DefaultListableBeanFactory listableBeanFactory) {
		listableBeanFactory.registerBeanDefinition(beanName,
		      createMonitorableBeanDefinition(beanName, dataSourceDefinition, listableBeanFactory));
		// zebra需做特殊处理，一些inner datasource可能作为nested bean方式定义，也需要wrapper
		if (isZebraDataSource(dataSourceClazz)) {
			replaceInnerDataSourceInZebra(beanName, dataSourceDefinition, listableBeanFactory);
		}
	}

	@SuppressWarnings("unchecked")
	private void replaceInnerDataSourceInZebra(String beanName, BeanDefinition zebraDataSourceDefinition,
	      DefaultListableBeanFactory listableBeanFactory) {
		MutablePropertyValues propertyValues = zebraDataSourceDefinition.getPropertyValues();
		PropertyValue dataSourcePoolVal = propertyValues.getPropertyValue("dataSourcePool");
		if (dataSourcePoolVal == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("Zebra dataSource's dataSourcePool property not found, maybe its name is modified, "
				      + "change the automonitor's implementation, otherwise some inner dataSource cannot be monitored.");
			}
			return;
		}
		Map<TypedStringValue, Object> innerDSDefinitionMap = (Map<TypedStringValue, Object>) dataSourcePoolVal.getValue();
		for (Entry<TypedStringValue, Object> innerDSDefEntry : innerDSDefinitionMap.entrySet()) {
			Object innerDSDefVal = innerDSDefEntry.getValue();
			if (innerDSDefVal instanceof BeanDefinitionHolder) {
				BeanDefinitionHolder innerDSDefHolder = (BeanDefinitionHolder) innerDSDefVal;
				BeanDefinition innerDSDefinition = innerDSDefHolder.getBeanDefinition();
				innerDSDefEntry.setValue(new BeanDefinitionHolder(createMonitorableBeanDefinition(beanName,
				      innerDSDefinition, listableBeanFactory), innerDSDefHolder.getBeanName(), innerDSDefHolder
				      .getAliases()));
			}
		}
	}

	private GenericBeanDefinition createMonitorableBeanDefinition(String beanName, BeanDefinition dataSourceDefinition,
	      DefaultListableBeanFactory listableBeanFactory) {

		if (dataSourceDefinition.getBeanClassName().equals(C3P0_CLASS_NAME)) {
			dataSourceDefinition.setBeanClassName(SingleDataSource.class.getName());
			dataSourceDefinition.getConstructorArgumentValues().addGenericArgumentValue(beanName);
//			Cat.logEvent("DAL.BeanProcessor", "ReplaceC3P0-" + beanName);
//		} else {
//			Cat.logEvent("DAL.BeanProcessor", "NotC3P0-" + beanName + "-" + dataSourceDefinition.getBeanClassName());
		}

		String newBeanName = String.format("%s-%d", beanName, nameId++);
		listableBeanFactory.registerBeanDefinition(newBeanName, dataSourceDefinition);

		GenericBeanDefinition monitorableDataSourceDefinition = new GenericBeanDefinition();
		monitorableDataSourceDefinition.setBeanClass(MonitorableDataSource.class);

		monitorableDataSourceDefinition.getConstructorArgumentValues().addGenericArgumentValue(
		      new RuntimeBeanReference(newBeanName));

		return monitorableDataSourceDefinition;
	}

	private boolean isZebraDataSource(Class<?> dataSourceClazz) {
		return ZEBRA_DATA_SOURCE_NAME.equals(dataSourceClazz.getName());
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

}
