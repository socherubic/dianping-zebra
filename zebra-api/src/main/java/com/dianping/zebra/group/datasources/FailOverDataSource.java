package com.dianping.zebra.group.datasources;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.exception.MasterDsNotFoundException;
import com.dianping.zebra.group.filter.FilterWrapper;
import com.dianping.zebra.group.filter.JdbcFilter;
import com.dianping.zebra.group.filter.JdbcMetaData;
import com.dianping.zebra.group.jdbc.AbstractDataSource;
import com.dianping.zebra.group.monitor.SingleDataSourceMBean;
import com.dianping.zebra.group.util.JdbcDriverClassHelper;
import com.dianping.zebra.group.util.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.lang.ref.WeakReference;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * features: 1. auto-detect master database by select @@read_only</br> 2. auto check the master database.</br> 3. if cannot find any
 * master database in the initial phase, fail fast.</br>
 */
public class FailOverDataSource extends AbstractDataSource {
	private final Logger logger = LogManager.getLogger(this.getClass());

	private Map<String, DataSourceConfig> configs;

	private JdbcFilter filter = new FilterWrapper();

	private volatile InnerSingleDataSource master;

	private Thread masterDataSourceMonitorThread;

	private JdbcMetaData metaData;

	public FailOverDataSource(Map<String, DataSourceConfig> configs) {
		this.configs = configs;
	}

	public FailOverDataSource(Map<String, DataSourceConfig> configs, JdbcMetaData metaData, JdbcFilter filter) {
		this(configs);
		this.filter = filter;
		this.metaData = metaData.clone();
		this.metaData.setDataSourceClass(this.getClass().getName());
	}

	@Override
	public void close() throws SQLException {
		if (this.masterDataSourceMonitorThread != null) {
			masterDataSourceMonitorThread.interrupt();
		}
		if (master != null) {
			SingleDataSourceManagerFactory.getDataSourceManager().destoryDataSource(master);
		}
		super.close();
	}

	private String getConfigSummary() {
		StringBuilder sb = new StringBuilder(100);

		for (Map.Entry<String, DataSourceConfig> config : configs.entrySet()) {
			sb.append(String.format("[datasource=%s,url=%s,username=%s,password=%s,driverClass=%s,properties=%s]", config
							.getValue().getId(), config.getValue().getJdbcUrl(), config.getValue().getUsername(), StringUtils
							.repeat("*",
									config.getValue().getPassword() == null ? 0 : config.getValue().getPassword().length()),
					config.getValue().getDriverClass(), config.getValue().getProperties()));
		}

		return sb.toString();
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		if (master != null && master.isAvailable()) {
			return master.getConnection();
		} else {
			throw new SQLException("Master database is in the maintaining.");
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		return getConnection(null, null);
	}

	public SingleDataSourceMBean getCurrentDataSourceMBean() {
		return master;
	}

	private InnerSingleDataSource getDataSource(DataSourceConfig config) {
		if (master != null) {
			SingleDataSourceManagerFactory.getDataSourceManager().destoryDataSource(master);
		}

		return SingleDataSourceManagerFactory.getDataSourceManager().createDataSource(config);
	}

	@Override
	public void init() {
		init(true);
	}

	public void init(boolean forceCheckMaster) {
		MasterDataSourceMonitor monitor = new MasterDataSourceMonitor(this);

		try {
			if (!monitor.findMasterDataSource().isMasterExist()) {
				String error_message = String.format("Cannot find any master dataSource. Configs=%s", getConfigSummary());

				if (forceCheckMaster) {
					MasterDsNotFoundException exp = new MasterDsNotFoundException(error_message);
					Cat.logError(exp);
					throw exp;
				} else {
					logger.warn(error_message);
				}
			} else {
				logger.info("FailOverDataSource find master success!");
			}
		} catch (FailOverDataSourceGCException e) {
			Cat.logError("FailOverDataSource should not be null!", e);
		}

		masterDataSourceMonitorThread = new Thread(monitor);
		masterDataSourceMonitorThread.setDaemon(true);
		masterDataSourceMonitorThread.setName("Dal-MasterDataSourceChecker");
		masterDataSourceMonitorThread.start();
	}

	private boolean setMasterDb(DataSourceConfig config) {
		if (master == null || !master.getId().equals(config.getId())) {
			master = getDataSource(config);
			return true;
		}
		return false;
	}

	static enum CheckMasterDataSourceResult {
		READ_WRITE(1), READ_ONLY(2), ERROR(3);

		private int value;

		private CheckMasterDataSourceResult(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
	}

	static class FailOverDataSourceGCException extends Exception {

		/**
		 *
		 */
		private static final long serialVersionUID = -8241429431083896757L;

	}

	static class FindMasterDataSourceResult {
		private boolean changedMaster;

		private boolean masterExist;

		public boolean isChangedMaster() {
			return changedMaster;
		}

		public void setChangedMaster(boolean changedMaster) {
			this.changedMaster = changedMaster;
		}

		public boolean isMasterExist() {
			return masterExist;
		}

		public void setMasterExist(boolean masterExist) {
			this.masterExist = masterExist;
		}
	}

	static class MasterDataSourceMonitor implements Runnable {
		private final Logger logger = LogManager.getLogger(this.getClass());

		private final int maxTransactionTryLimits = 60 * 10; // 10 minute

		private AtomicInteger atomicSleepTimes = new AtomicInteger(0);

		private Map<String, Connection> cachedConnection = new HashMap<String, Connection>();

		private WeakReference<FailOverDataSource> ref;

		private Transaction transaction = null;

		private int transactionTryLimits = 0;

		public MasterDataSourceMonitor(FailOverDataSource dsRef) {
			this.ref = new WeakReference<FailOverDataSource>(dsRef);
		}

		private void checkIfFailOverDataSourceHasGC() throws FailOverDataSourceGCException {
			getWeakFailOverDataSource();
		}

		protected void closeConnection(String id) {
			if (!cachedConnection.containsKey(id)) {
				return;
			}

			try {
				cachedConnection.get(id).close();
			} catch (SQLException ignore) {
			} finally {
				cachedConnection.remove(id);
			}
		}

		protected void closeConnections() {
			for (Map.Entry<String, Connection> entity : cachedConnection.entrySet()) {
				try {
					entity.getValue().close();
				} catch (SQLException ignore) {
				}
			}

			cachedConnection.clear();
		}

		private void completeSwitchTransaction() {
			if (transaction != null) {
				Cat.logEvent("DAL.FailOver", "Success");
				transaction.setStatus(Message.SUCCESS);
				transaction.complete();
				transaction = null;
			}
		}

		private void createSwitchTransaction() {
			transaction = Cat.newTransaction("DAL", "FailOver");
		}

		public FindMasterDataSourceResult findMasterDataSource() throws FailOverDataSourceGCException {
			FindMasterDataSourceResult result = new FindMasterDataSourceResult();

			if (getWeakFailOverDataSource().configs.values().size() == 0) {
				logger.warn("zero writer data source in config!");
			}

			for (DataSourceConfig config : getWeakFailOverDataSource().configs.values()) {
				CheckMasterDataSourceResult checkResult = isMasterDataSource(config);
				if (checkResult == CheckMasterDataSourceResult.READ_WRITE) {
					Cat.logEvent("DAL.Master", "Found-" + config.getId());

					result.setChangedMaster(getWeakFailOverDataSource().setMasterDb(config));
					result.setMasterExist(true);
					break;
				}
			}

			return result;
		}

		protected Connection getConnection(DataSourceConfig config) throws SQLException {
			if (!cachedConnection.containsKey(config.getId())) {
				JdbcDriverClassHelper.loadDriverClass(config.getDriverClass(), config.getJdbcUrl());
				cachedConnection.put(config.getId(),
						DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword()));
			}

			return cachedConnection.get(config.getId());
		}

		public int getSleepTimes() {
			return atomicSleepTimes.get();
		}

		private FailOverDataSource getWeakFailOverDataSource() throws FailOverDataSourceGCException {
			FailOverDataSource weak = ref.get();
			if (weak == null) {
				throw new FailOverDataSourceGCException();
			}
			return weak;
		}

		private void increaseTransactionTryTimes() {
			transactionTryLimits++;
			if (transactionTryLimits >= maxTransactionTryLimits) {
				transactionTryLimits = 0;

				if (transaction != null) {
					Cat.logEvent("DAL.FailOver", "Failed");
					transaction.setStatus("Fail to find any master database");
					transaction.complete();
					transaction = null;
				}
			}
		}

		private boolean isMaster(ResultSet rs) throws SQLException {
			if (rs.next()) {
				return rs.getInt(1) == 0;
			} else {
				return false;
			}
		}

		protected CheckMasterDataSourceResult isMasterDataSource(DataSourceConfig config) {
			Statement stmt = null;
			ResultSet rs = null;

			try {
				stmt = getConnection(config).createStatement();
				rs = stmt.executeQuery(config.getTestReadOnlySql());

				if (isMaster(rs)) {
					return CheckMasterDataSourceResult.READ_WRITE;
				} else {
					return CheckMasterDataSourceResult.READ_ONLY;
				}
			} catch (SQLException e) {
				Cat.logError(e);
				closeConnection(config.getId());

				return CheckMasterDataSourceResult.ERROR;
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

		@Override
		public void run() {
			while (!Thread.interrupted()) {
				try {
					sleepForSeconds(1);
					checkIfFailOverDataSourceHasGC();

					FindMasterDataSourceResult result = findMasterDataSource();
					if (!result.isMasterExist()) {
						increaseTransactionTryTimes();
					} else {
						completeSwitchTransaction();

						closeConnections();
						while (!Thread.interrupted()) {
							sleepForSeconds(5);

							if (isMasterDataSource(
									getWeakFailOverDataSource().configs.get(getWeakFailOverDataSource().master.getId()))
									!= CheckMasterDataSourceResult.READ_WRITE) {
								createSwitchTransaction();
								closeConnections();
								break;
							}
						}
					}
				} catch (FailOverDataSourceGCException e) {
					Cat.logError("FailOverDataSource has be GC", e);
					break;
				} catch (InterruptedException ignore) {
					break;
				} catch (Exception e) {
					Cat.logError(e);
				}
			}

			closeConnections();
		}

		private void sleepForSeconds(long seconds) throws InterruptedException {
			atomicSleepTimes.incrementAndGet();
			if (atomicSleepTimes.get() > 100) {
				atomicSleepTimes.set(0);
			}

			TimeUnit.SECONDS.sleep(seconds);
		}
	}
}