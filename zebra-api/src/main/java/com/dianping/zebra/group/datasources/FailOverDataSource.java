package com.dianping.zebra.group.datasources;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dianping.cat.Cat;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.exception.WriteDataSourceNotFoundException;
import com.dianping.zebra.group.exception.ZebraRuntimeException;
import com.dianping.zebra.group.jdbc.AbstractDataSource;
import com.dianping.zebra.group.monitor.SingleDataSourceMBean;

/**
 * features: 1. auto-detect write database by select @@read_only</br> 2. if ping connection was killed by MySQL server, it can
 * reconnect.</br> 3. if cannot find any write database in the initial phase, fail fast.</br>
 * 
 * @author damonzhu
 * 
 */
public class FailOverDataSource extends AbstractDataSource {

	private static final Logger logger = LoggerFactory.getLogger(FailOverDataSource.class);

	private static final String ERROR_MESSAGE = "[DAL]Cannot find any write dataSource.";

	private volatile SingleDataSource writeDs;

	private Map<String, DataSourceConfig> configs;

	private Map<String, Connection> connections = new ConcurrentHashMap<String, Connection>(); // cache ping connections

	private Thread writeDataSourceMonitorThread;

	public FailOverDataSource(Map<String, DataSourceConfig> configs) {
		this.configs = configs;
	}

	@Override
	public void close() throws SQLException {
		if (this.writeDataSourceMonitorThread != null) {
			writeDataSourceMonitorThread.interrupt();
		}

		for (Connection conn : connections.values()) {
			try {
				conn.close();
			} catch (SQLException ignore) {
			}
		}

		if (writeDs != null) {
			SingleDataSourceManagerFactory.getDataSourceManager().destoryDataSource(writeDs.getId(), this);
		}
	}

	private boolean findWriteDataSource() {
		boolean isWriteDBExist = false;

		for (DataSourceConfig config : configs.values()) {
			Connection conn = null;
			Statement stmt = null;
			ResultSet rs = null;
			try {
				conn = getConnection(config);
				stmt = conn.createStatement();
				rs = stmt.executeQuery(config.getTestReadOnlySql());

				if (rs.next()) {
					// switch database, 0 for write dataSource, 1 for read dataSource.
					if (rs.getInt(1) == 0) {
						if (writeDs == null || !writeDs.getId().equals(config.getId())) {
							writeDs = getDataSource(config);
						}

						isWriteDBExist = true;
						break;
					}
				}
			} catch (SQLException e) {
				// communication link fail,then print the error message to cat
				if (writeDs != null && writeDs.getId().equals(config.getId())) {
					Cat.logError(String.format("the ping connection for %s was killed by mysql server", writeDs.getId()), e);
				}

				try {
					connections.remove(config.getId());
					if (conn != null) {
						conn.close();
					}
				} catch (SQLException ignore) {
				}
			} finally {
				if (rs != null) {
					try {
						rs.close();
					} catch (SQLException ignore) {
					}
				}
				if (stmt != null) {
					try {
						stmt.close();
					} catch (SQLException ignore) {
					}
				}
			}
		}

		return isWriteDBExist;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return getConnection(null, null);
	}

	public Connection getConnection(DataSourceConfig config) throws SQLException {
		Connection conn = connections.get(config.getId());

		if (conn == null) {
			conn = DriverManager.getConnection(config.getJdbcUrl(), config.getUser(), config.getPassword());
			connections.put(config.getId(), conn);
		}

		return conn;
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		if (writeDs != null && writeDs.isAvailable()) {
			return writeDs.getConnection();
		} else {
			throw new SQLException("[DAL]Write dataSource is currently in the maintaining stage.");
		}
	}

	public SingleDataSourceMBean getCurrentDataSourceMBean() {
		return writeDs;
	}

	private SingleDataSource getDataSource(DataSourceConfig config) {
		if (writeDs != null) {
			SingleDataSourceManagerFactory.getDataSourceManager().destoryDataSource(writeDs.getId(), this);
		}

		return SingleDataSourceManagerFactory.getDataSourceManager().createDataSource(this, config);
	}

	@Override
	public void init() {
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch (ClassNotFoundException ex) {
			String errorMsg = "Cannot find mysql driver class[com.mysql.jdbc.Driver]";
			logger.error(errorMsg);
			throw new ZebraRuntimeException(errorMsg, ex);
		}

		boolean isFind = false;

		try {
			isFind = findWriteDataSource();
		} catch (RuntimeException e) {
			throw new ZebraRuntimeException(e);
		}

		if (!isFind) {
			logger.error(ERROR_MESSAGE);
			throw new WriteDataSourceNotFoundException(ERROR_MESSAGE);
		}

		writeDataSourceMonitorThread = new Thread(new WriterDataSourceMonitor());
		writeDataSourceMonitorThread.setDaemon(true);
		writeDataSourceMonitorThread.setName("FailOverDataSource");

		writeDataSourceMonitorThread.start();
	}

	class WriterDataSourceMonitor implements Runnable {
		private long counter = 0L;

		private boolean perMinite() {
			return (counter++ % 60) == 0;
		}

		@Override
		public void run() {
			while (!Thread.interrupted()) {
				try {
					if (!findWriteDataSource()) {
						if (perMinite()) {
							Cat.logError(new WriteDataSourceNotFoundException(ERROR_MESSAGE));
						}
					}
				} catch (Throwable e) {
					Cat.logError(e);
				}

				try {
					TimeUnit.SECONDS.sleep(1); // TODO: temperary
				} catch (InterruptedException e) {
					break;
				}
			}
		}
	}
}