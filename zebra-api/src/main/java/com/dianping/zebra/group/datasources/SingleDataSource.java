package com.dianping.zebra.group.datasources;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import com.dianping.zebra.Constants;
import com.dianping.zebra.group.config.datasource.entity.Any;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.exception.DalException;
import com.dianping.zebra.group.exception.IllegalConfigException;
import com.dianping.zebra.group.filter.DefaultJdbcFilterChain;
import com.dianping.zebra.group.filter.JdbcFilter;
import com.dianping.zebra.group.jdbc.AbstractDataSource;
import com.dianping.zebra.group.monitor.SingleDataSourceMBean;
import com.dianping.zebra.group.util.DataSourceState;
import com.dianping.zebra.util.JdbcDriverClassHelper;
import com.mchange.v2.c3p0.DataSources;
import com.mchange.v2.c3p0.PoolBackedDataSource;

public class SingleDataSource extends AbstractDataSource implements MarkableDataSource, SingleDataSourceMBean {

	private static final Logger logger = LogManager.getLogger(SingleDataSource.class);

	private String dsId;

	private DataSourceConfig config;

	private DataSource dataSource;

	private CountPunisher punisher;

	private volatile DataSourceState state = DataSourceState.INITIAL;

	public SingleDataSource(DataSourceConfig config, List<JdbcFilter> filters) {
		this.dsId = config.getId();
		this.config = config;
		this.punisher = new CountPunisher(this, config.getTimeWindow(), config.getPunishLimit());
		this.filters = filters;
		this.dataSource = initDataSource(config);
	}

	private void checkState() throws SQLException {
		if (state == DataSourceState.CLOSED || state == DataSourceState.DOWN) {
			throw new SQLException(String.format("dataSource is not avaiable, current state is [%s]", state));
		}
	}

	@Override
	public void close() throws SQLException {
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public void closeSingleDataSource(SingleDataSource source, JdbcFilter chain) throws SQLException {
					if (index < filters.size()) {
						filters.get(index++).closeSingleDataSource(source, chain);
					} else {
						source.closeOrigin();
					}
				}
			};
			chain.closeSingleDataSource(this, chain);
		} else {
			closeOrigin();
		}
	}

	public void closeOrigin() throws SQLException {
		if (dataSource != null) {
			if (dataSource instanceof PoolBackedDataSource) {
				PoolBackedDataSource poolBackedDataSource = (PoolBackedDataSource) dataSource;

				if (poolBackedDataSource.getNumBusyConnections() == 0) {
					logger.info("closing old datasource [" + dsId + "]");

					DataSources.destroy(poolBackedDataSource);

					logger.info("old datasource [" + dsId + "] closed");
					state = DataSourceState.CLOSED;
				} else {
					throw new DalException(String.format("Cannot close dataSource[%s] since there are busy connections.",
					      dsId));
				}
			} else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
				org.apache.tomcat.jdbc.pool.DataSource ds = (org.apache.tomcat.jdbc.pool.DataSource) dataSource;

				if (ds.getActive() == 0) {
					logger.info("closing old datasource [" + dsId + "]");

					ds.close();

					logger.info("old datasource [" + dsId + "] closed");
					state = DataSourceState.CLOSED;
				} else {
					throw new DalException(String.format("Cannot close dataSource[%s] since there are busy connections.",
					      dsId));
				}
			} else {
				Exception exp = new DalException(
				      "fail to close dataSource since dataSource is not an instance of C3P0 or Tomcat-Jdbc.");
				logger.warn(exp.getMessage(), exp);
			}
		} else {
			Exception exp = new DalException("fail to close dataSource since dataSource is null.");
			logger.warn(exp.getMessage(), exp);
		}
	}

	public synchronized DataSourceConfig getConfig() {
		return this.config;
	}

	@Override
	public Connection getConnection() throws SQLException {
		checkState();
		return getConnection(null, null);
	}

	@Override
	public Connection getConnection(final String username, final String password) throws SQLException {
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public SingleConnection getSingleConnection(SingleDataSource source, JdbcFilter chain) throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).getSingleConnection(source, chain);
					} else {
						return source.getConnectionOrigin(username, password);
					}
				}
			};
			return chain.getSingleConnection(this, chain);
		} else {
			return getConnectionOrigin(username, password);
		}
	}

	private SingleConnection getConnectionOrigin(String username, String password) throws SQLException {
		checkState();
		Connection conn;
		try {
			conn = dataSource.getConnection();
		} catch (SQLException e) {
			punisher.countAndPunish(e);
			throw e;
		}

		if (state == DataSourceState.INITIAL) {
			state = DataSourceState.UP;
		}

		return new SingleConnection(this, this.config, conn, this.filters);
	}

	@Override
	public String getCurrentState() {
		return state.toString();
	}

	public String getId() {
		return this.dsId;
	}

	public int getIntProperty(DataSourceConfig config, String name, int defaultValue) {
		for (Any any : config.getProperties()) {
			if (any.getName().equalsIgnoreCase(name)) {
				return Integer.parseInt(any.getValue());
			}
		}

		return defaultValue;
	}

	@Override
	public int getNumBusyConnection() {
		if (dataSource != null) {
			try {
				if (dataSource instanceof PoolBackedDataSource) {
					return ((PoolBackedDataSource) dataSource).getNumBusyConnections();
				} else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
					return ((org.apache.tomcat.jdbc.pool.DataSource) dataSource).getActive();
				}
			} catch (Exception e) {
			}
		}

		return 0;
	}

	@Override
	public int getNumConnections() {
		if (dataSource != null) {
			try {
				if (dataSource instanceof PoolBackedDataSource) {
					return ((PoolBackedDataSource) dataSource).getNumConnections();
				} else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
					return ((org.apache.tomcat.jdbc.pool.DataSource) dataSource).getSize();
				}
			} catch (Exception e) {
			}
		}

		return 0;
	}

	@Override
	public int getNumIdleConnection() {
		if (dataSource != null) {
			try {
				if (dataSource instanceof PoolBackedDataSource) {
					return ((PoolBackedDataSource) dataSource).getNumIdleConnections();
				} else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
					return ((org.apache.tomcat.jdbc.pool.DataSource) dataSource).getIdle();
				}
			} catch (Exception e) {
			}
		}

		return 0;
	}

	public CountPunisher getPunisher() {
		return this.punisher;
	}

	@Override
	public DataSourceState getState() {
		return this.state;
	}

	public String getStringProperty(DataSourceConfig config, String name, String defaultValue) {
		for (Any any : config.getProperties()) {
			if (any.getName().equalsIgnoreCase(name)) {
				return any.getValue();
			}
		}

		return defaultValue;
	}

	private DataSource initDataSource(final DataSourceConfig value) {
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public DataSource initSingleDataSource(SingleDataSource source, JdbcFilter chain) {
					if (index < filters.size()) {
						return filters.get(index++).initSingleDataSource(source, chain);
					} else {
						return source.initDataSourceOrigin(value);
					}
				}
			};
			return chain.initSingleDataSource(this, chain);
		} else {
			return initDataSourceOrigin(value);
		}
	}

	private DataSource initDataSourceOrigin(DataSourceConfig value) {
		try {
			JdbcDriverClassHelper.loadDriverClass(value.getDriverClass(), value.getJdbcUrl());
			if (value.getType().equalsIgnoreCase(Constants.CONNECTION_POOL_TYPE_C3P0)) {
				DataSource unPooledDataSource = DataSources.unpooledDataSource(value.getJdbcUrl(), value.getUsername(),
				      value.getPassword());

				Map<String, Object> props = new HashMap<String, Object>();

				props.put("driverClass", value.getDriverClass());

				for (Any any : value.getProperties()) {
					props.put(any.getName(), any.getValue());
				}

				PoolBackedDataSource pooledDataSource = (PoolBackedDataSource) DataSources.pooledDataSource(
				      unPooledDataSource, props);

				logger.info(String.format("New dataSource [%s] created.", value.getId()));

				return pooledDataSource;
			} else if (value.getType().equalsIgnoreCase(Constants.CONNECTION_POOL_TYPE_TOMCAT_JDBC)) {
				PoolProperties p = new PoolProperties();
				p.setUrl(value.getJdbcUrl());
				p.setDriverClassName(value.getDriverClass());
				p.setUsername(value.getUsername());
				p.setPassword(value.getPassword());

				p.setInitialSize(getIntProperty(value, "initialPoolSize", 5));
				p.setMaxActive(getIntProperty(value, "maxPoolSize", 20));
				p.setMinIdle(getIntProperty(value, "minPoolSize", 5));
				p.setMaxIdle(getIntProperty(value, "maxPoolSize", 20));
				p.setMaxWait(getIntProperty(value, "checkoutTimeout", 1000));

				p.setRemoveAbandoned(true);
				p.setRemoveAbandonedTimeout(60);
				p.setTestOnBorrow(true);
				p.setTestOnReturn(false);
				p.setTestWhileIdle(true);
				p.setValidationQuery(getStringProperty(value, "preferredTestQuery", "SELECT 1"));
				p.setValidationInterval(30000); // 5 min
				p.setTimeBetweenEvictionRunsMillis(300000); // 30 min
				p.setMinEvictableIdleTimeMillis(1800000);
				p.setJdbcInterceptors("org.apache.tomcat.jdbc.pool.interceptor.StatementCache");

				org.apache.tomcat.jdbc.pool.DataSource datasource = new org.apache.tomcat.jdbc.pool.DataSource();
				datasource.setPoolProperties(p);

				logger.info(String.format("New dataSource [%s] created.", value.getId()));

				return datasource;
			} else {
				throw new IllegalConfigException("illegal datasource pool type : " + value.getType());
			}
		} catch (IllegalConfigException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalConfigException(e);
		}
	}

	public boolean isAvailable() {
		return this.state == DataSourceState.INITIAL || this.state == DataSourceState.UP;
	}

	public boolean isClosed() {
		return this.state == DataSourceState.CLOSED;
	}

	public boolean isDown() {
		return this.state == DataSourceState.DOWN;
	}

	@Override
	public void markClosed() {
		this.state = DataSourceState.CLOSED;
	}

	@Override
	public void markDown() {
		this.state = DataSourceState.DOWN;
	}

	@Override
	public void markUp() {
		this.state = DataSourceState.INITIAL;
	}
}
