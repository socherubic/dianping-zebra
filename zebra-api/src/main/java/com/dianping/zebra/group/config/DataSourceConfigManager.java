package com.dianping.zebra.group.config;

import java.beans.PropertyChangeListener;

import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;

public interface DataSourceConfigManager {
	
	public void addListerner(PropertyChangeListener listener);
	
	public GroupDataSourceConfig getGroupDataSourceConfig();
	
	public String getRouterStrategy();
	
	public void init();
	
	public boolean isTransactionForceWrite();
	
	public boolean isWriteFirst();

	public void setVerbose(boolean verbose);
}
