package com.dianping.zebra.group.router;import java.beans.PropertyChangeEvent;import java.beans.PropertyChangeListener;import java.sql.SQLException;import java.util.ArrayList;import java.util.List;import java.util.Map;import java.util.Set;import com.dianping.zebra.group.SqlType;import com.dianping.zebra.group.config.DataSourceConfigManager;import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;import com.dianping.zebra.group.util.SqlUtils;/** * 读写分离的DataSource选择器 */public class GroupReadWriteDataSourceRouter implements GroupDataSourceRouter, PropertyChangeListener {	private GroupWeightDataSourceRouter readrouter;	private GroupWeightDataSourceRouter writerouter;	private DataSourceConfigManager configManager;	public GroupReadWriteDataSourceRouter(DataSourceConfigManager configManager) {		this.configManager = configManager;		Map<String, DataSourceConfig> dataSourceConfigs = configManager.getAvailableDataSources();		this.init(dataSourceConfigs);	}	private void init(Map<String, DataSourceConfig> dataSourceConfigs) {		List<DataSourceConfig> readDataSourceConfigs = new ArrayList<DataSourceConfig>();		List<DataSourceConfig> writeDataSourceConfigs = new ArrayList<DataSourceConfig>();		for (DataSourceConfig config : dataSourceConfigs.values()) {			boolean readonly = config.isReadonly();			if (readonly) {				readDataSourceConfigs.add(config);			} else {				writeDataSourceConfigs.add(config);			}		}		this.readrouter = new GroupWeightDataSourceRouter(readDataSourceConfigs, true);		this.writerouter = new GroupWeightDataSourceRouter(writeDataSourceConfigs, false);	}	@Override	public GroupDataSourceTarget select(GroupDataSourceRouterInfo routerInfo) {		return this.select(routerInfo, null);	}	@Override	public GroupDataSourceTarget select(GroupDataSourceRouterInfo routerInfo, Set<GroupDataSourceTarget> excludeTargets) {		GroupDataSourceContext dsContext = GroupDataSourceContext.get();		if (dsContext.getMasterFlag()) {			return writerouter.select(routerInfo, excludeTargets);		} else {			// 判断出sql的SqlType，算出它是read还是write			SqlType sqlType = getSqlType(routerInfo);			if (sqlType.isRead()) {				return readrouter.select(routerInfo, excludeTargets);			} else {				return writerouter.select(routerInfo, excludeTargets);			}		}	}	private SqlType getSqlType(GroupDataSourceRouterInfo routerInfo) {		String sql = routerInfo.getSql();		try {			return SqlUtils.getSqlType(sql);		} catch (SQLException e) {			throw new RuntimeException(e.getMessage(), e);		}	}	public String getName() {		return this.getName();	}//TODO	@Override	public String getRouterStrategy() {		return "roundrobin";	}	@Override	public void propertyChange(PropertyChangeEvent evt) {		this.init(configManager.getAvailableDataSources());	}}