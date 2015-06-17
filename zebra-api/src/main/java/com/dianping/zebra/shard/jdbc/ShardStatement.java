/**
 * Project: ${zebra-client.aid}
 * 
 * File Created at 2011-6-10 $Id$
 * 
 * Copyright 2010 dianping.com. All rights reserved.
 * 
 * This software is the confidential and proprietary information of Dianping
 * Company. ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dianping.zebra.shard.jdbc.data.DataPool;
import com.dianping.zebra.shard.router.DataSourceRepository;
import com.dianping.zebra.shard.router.RouterResult;
import com.dianping.zebra.shard.router.RouterTarget;
import com.dianping.zebra.shard.router.ShardRouter;
import com.dianping.zebra.shard.router.ShardRouterException;
import com.dianping.zebra.util.JDBCUtils;
import com.dianping.zebra.util.SqlType;
import com.dianping.zebra.util.SqlUtils;

/**
 * @author Leo Liang
 * 
 */
public class ShardStatement implements Statement {

	private ShardRouter router;

	protected ShardConnection connection;

	private boolean closed;

	private boolean readOnly;

	protected boolean autoCommit = true;

	private int resultSetType = -1;

	private int resultSetConcurrency = -1;

	private int resultSetHoldability = -1;

	private static final String SELECT_GENERATEDKEY_SQL_PATTERN = "@@identity";

	private static final String SELECT_LAST_INSERT_ID = "last_insert_id()";

	protected Set<ResultSet> attachedResultSets = new HashSet<ResultSet>();

	protected List<String> batchedArgs;

	protected List<Statement> actualStatements = new ArrayList<Statement>();

	protected ResultSet results;

	protected int updateCount;

	protected ResultSet generatedKey;

	private boolean _execute(String sql, int autoGeneratedKeys, int[] columnIndexes, String[] columnNames)
	      throws SQLException {
		SqlType sqlType = judgeSqlType(sql);
		if (sqlType == SqlType.SELECT) {
			executeQuery(sql);
			return true;
		} else if (sqlType == SqlType.INSERT || sqlType == SqlType.UPDATE || sqlType == SqlType.DELETE) {
			if (autoGeneratedKeys == -1 && columnIndexes == null && columnNames == null) {
				executeUpdate(sql);
			} else if (autoGeneratedKeys != -1) {
				executeUpdate(sql, autoGeneratedKeys);
			} else if (columnIndexes != null) {
				executeUpdate(sql, columnIndexes);
			} else if (columnNames != null) {
				executeUpdate(sql, columnNames);
			} else {
				executeUpdate(sql);
			}

			return false;
		} else {
			throw new SQLException("only select, insert, update, delete sql is supported");
		}
	}

	private int _executeUpdate(String sql, int autoGeneratedKeys, int[] columnIndexes, String[] columnNames)
	      throws SQLException {
		checkClosed();

		RouterResult routerTarget = routingAndCheck(sql, null);

		int affectedRows = 0;
		List<SQLException> exceptions = new ArrayList<SQLException>();

		for (RouterTarget targetedSql : routerTarget.getTargetedSqls()) {
			for (String executableSql : targetedSql.getSqls()) {
				try {
					Connection conn = connection.getRealConnection(targetedSql.getDataSourceName());
					if (conn == null) {
						String dbIndex = targetedSql.getDataSourceName();
						conn = DataSourceRepository.getDataSource(dbIndex).getConnection();
						conn.setAutoCommit(autoCommit);

						connection.setRealConnection(targetedSql.getDataSourceName(), conn);
					}
					Statement stmt = createStatementInternal(conn);
					actualStatements.add(stmt);

					if (autoGeneratedKeys == -1 && columnIndexes == null && columnNames == null) {
						affectedRows += stmt.executeUpdate(executableSql, Statement.RETURN_GENERATED_KEYS);
					} else if (autoGeneratedKeys != -1) {
						affectedRows += stmt.executeUpdate(executableSql, autoGeneratedKeys);
					} else if (columnIndexes != null) {
						affectedRows += stmt.executeUpdate(executableSql, columnIndexes);
					} else if (columnNames != null) {
						affectedRows += stmt.executeUpdate(executableSql, columnNames);
					} else {
						affectedRows += stmt.executeUpdate(executableSql, Statement.RETURN_GENERATED_KEYS);
					}

					// 把insert语句返回的生成key保存在当前会话中
					if (executableSql.trim().charAt(0) == 'i' || executableSql.trim().charAt(0) == 'I') {
						this.generatedKey = stmt.getGeneratedKeys();
					}
				} catch (SQLException e) {
					exceptions.add(e);
				}
			}
		}

		this.results = null;
		this.updateCount = affectedRows;

		JDBCUtils.throwSQLExceptionIfNeeded(exceptions);

		return affectedRows;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#addBatch(java.lang.String)
	 */
	@Override
	public void addBatch(String sql) throws SQLException {
		checkClosed();

		if (batchedArgs == null) {
			batchedArgs = new ArrayList<String>();
		}
		if (sql != null) {
			batchedArgs.add(sql);
		}
	}

	protected ResultSet beforeQuery(String sql) throws SQLException {
		// 特殊处理 SELECT @@IDENTITY AS A
		// 这种SQL，因为这种SQL需要从同一个DPConnection会话中获得上次Insert语句的返回值
		ResultSet generatedKey = this.generatedKey;
		sql = sql.toLowerCase();
		if (generatedKey != null && sql != null
		      && (sql.indexOf(SELECT_GENERATEDKEY_SQL_PATTERN) >= 0 || sql.indexOf(SELECT_LAST_INSERT_ID) >= 0)) {
			List<ResultSet> rsList = new ArrayList<ResultSet>();
			generatedKey.beforeFirst();
			rsList.add(generatedKey);

			DataPool dataPool = new DataPool();
			dataPool.setResultSets(rsList);
			dataPool.setInMemory(false);

			ShardResultSet rs = new ShardResultSet();
			rs.setStatement(this);
			rs.setDataPool(dataPool);

			attachedResultSets.add(rs);

			this.results = rs;
			this.updateCount = -1;

			return this.results;
		}

		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#cancel()
	 */
	@Override
	public void cancel() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport cancel");
	}

	protected void checkClosed() throws SQLException {
		if (closed) {
			throw new SQLException("No operations allowed after statement closed.");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#clearBatch()
	 */
	@Override
	public void clearBatch() throws SQLException {
		checkClosed();

		if (batchedArgs != null) {
			batchedArgs.clear();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#clearWarnings()
	 */
	@Override
	public void clearWarnings() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport clearWarnings");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#close()
	 */
	@Override
	public void close() throws SQLException {

		if (closed) {
			return;
		}

		List<SQLException> exceptions = new ArrayList<SQLException>();

		try {
			for (ResultSet resultSet : attachedResultSets) {
				try {
					resultSet.close();
				} catch (SQLException e) {
					exceptions.add(e);
				}
			}

			for (Statement stmt : actualStatements) {
				try {
					stmt.close();
				} catch (SQLException e) {
					exceptions.add(e);
				}
			}
		} finally {
			closed = true;
			attachedResultSets.clear();
			actualStatements.clear();
			results = null;
		}

		JDBCUtils.throwSQLExceptionIfNeeded(exceptions);
	}

	public void closeOnCompletion() throws SQLException {
		throw new UnsupportedOperationException("closeOnCompletion");
	}

	private Statement createStatementInternal(Connection connection) throws SQLException {
		Statement stmt;
		if (this.resultSetType != -1 && this.resultSetConcurrency != -1 && this.resultSetHoldability != -1) {
			stmt = connection.createStatement(this.resultSetType, this.resultSetConcurrency, this.resultSetHoldability);
		} else if (this.resultSetType != -1 && this.resultSetConcurrency != -1) {
			stmt = connection.createStatement(this.resultSetType, this.resultSetConcurrency);
		} else {
			stmt = connection.createStatement();
		}

		return stmt;
	}

	protected void executableCheck(RouterResult routerTarget) throws SQLException {
		if (routerTarget == null) {
			throw new SQLException("No router return value.");
		}
		// TODO 可以增加更多限制
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#execute(java.lang.String)
	 */
	@Override
	public boolean execute(String sql) throws SQLException {
		return _execute(sql, -1, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#execute(java.lang.String, int)
	 */
	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return _execute(sql, autoGeneratedKeys, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#execute(java.lang.String, int[])
	 */
	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return _execute(sql, -1, columnIndexes, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#execute(java.lang.String, java.lang.String[])
	 */
	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		return _execute(sql, -1, null, columnNames);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeBatch()
	 */
	@Override
	public int[] executeBatch() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport executeBatch");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeQuery(java.lang.String)
	 */
	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		checkClosed();

		ResultSet specRS = beforeQuery(sql);
		if (specRS != null) {
			this.results = specRS;
			this.updateCount = -1;
			attachedResultSets.add(specRS);
			return this.results;
		}

		RouterResult routerTarget = routingAndCheck(sql, null);

		ShardResultSet rs = new ShardResultSet();
		rs.setStatement(this);
		rs.setRouterTarget(routerTarget);

		attachedResultSets.add(rs);

		List<SQLException> exceptions = new ArrayList<SQLException>();

		for (RouterTarget targetedSql : routerTarget.getTargetedSqls()) {
			for (String executableSql : targetedSql.getSqls()) {
				try {
					Connection conn = connection.getRealConnection(targetedSql.getDataSourceName());
					if (conn == null) {
						String dbIndex = targetedSql.getDataSourceName();
						conn = DataSourceRepository.getDataSource(dbIndex).getConnection();
						conn.setAutoCommit(autoCommit);

						connection.setRealConnection(targetedSql.getDataSourceName(), conn);
					}
					Statement stmt = createStatementInternal(conn);
					actualStatements.add(stmt);
					rs.getActualResultSets().add(stmt.executeQuery(executableSql));
				} catch (SQLException e) {
					exceptions.add(e);
				}
			}

		}

		this.results = rs;
		this.updateCount = -1;

		try {
			rs.init();
		} catch (SQLException e) {
			exceptions.add(e);
		}

		JDBCUtils.throwSQLExceptionIfNeeded(exceptions);

		return this.results;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeUpdate(java.lang.String)
	 */
	@Override
	public int executeUpdate(String sql) throws SQLException {
		return _executeUpdate(sql, -1, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeUpdate(java.lang.String, int)
	 */
	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		return _executeUpdate(sql, autoGeneratedKeys, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeUpdate(java.lang.String, int[])
	 */
	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		return _executeUpdate(sql, -1, columnIndexes, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#executeUpdate(java.lang.String, java.lang.String[])
	 */
	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return _executeUpdate(sql, -1, null, columnNames);
	}

	/**
	 * @return the attachedResultSets
	 */
	public Set<ResultSet> getAttachedResultSets() {
		return attachedResultSets;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getConnection()
	 */
	@Override
	public Connection getConnection() throws SQLException {
		return connection;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getFetchDirection()
	 */
	@Override
	public int getFetchDirection() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getFetchDirection");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getFetchSize()
	 */
	@Override
	public int getFetchSize() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getFetchSize");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getGeneratedKeys()
	 */
	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		return this.generatedKey;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getMaxFieldSize()
	 */
	@Override
	public int getMaxFieldSize() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getMaxFieldSize");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getMaxRows()
	 */
	@Override
	public int getMaxRows() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getMaxRows");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getMoreResults()
	 */
	@Override
	public boolean getMoreResults() throws SQLException {
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getMoreResults(int)
	 */
	@Override
	public boolean getMoreResults(int current) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getMoreResults");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getQueryTimeout()
	 */
	@Override
	public int getQueryTimeout() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getQueryTimeout");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getResultSet()
	 */
	@Override
	public ResultSet getResultSet() throws SQLException {
		return results;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getResultSetConcurrency()
	 */
	@Override
	public int getResultSetConcurrency() throws SQLException {
		return resultSetConcurrency;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getResultSetHoldability()
	 */
	@Override
	public int getResultSetHoldability() throws SQLException {
		return resultSetHoldability;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getResultSetType()
	 */
	@Override
	public int getResultSetType() throws SQLException {
		return resultSetType;
	}

	public ShardRouter getRouter() {
		return router;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getUpdateCount()
	 */
	@Override
	public int getUpdateCount() throws SQLException {
		return updateCount;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#getWarnings()
	 */
	@Override
	public SQLWarning getWarnings() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport getWarnings");
	}

	public boolean isAutoCommit() {
		return autoCommit;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#isClosed()
	 */
	@Override
	public boolean isClosed() throws SQLException {
		return closed;
	}

	public boolean isCloseOnCompletion() throws SQLException {
		throw new UnsupportedOperationException("isCloseOnCompletion");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#isPoolable()
	 */
	@Override
	public boolean isPoolable() throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport isPoolable");
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Wrapper#isWrapperFor(java.lang.Class)
	 */
	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport isWrapperFor");
	}

	protected SqlType judgeSqlType(String sql) throws SQLException {
		SqlType sqlType = SqlUtils.getSqlType(sql);

		if (sqlType != SqlType.SELECT && sqlType != SqlType.INSERT && sqlType != SqlType.UPDATE
		      && sqlType != SqlType.DELETE) {
			throw new SQLException("Only select, insert, update, delete sql is supported.");
		}

		return sqlType;
	}

	/**
	 * @param sql
	 * @param params
	 * @return
	 * @throws java.sql.SQLException
	 */
	protected RouterResult routingAndCheck(String sql, List<Object> params) throws SQLException {
		RouterResult routerTarget = null;
		
		try {
			routerTarget = router.router(sql, params);
			executableCheck(routerTarget);
		} catch (ShardRouterException e) {
			throw new SQLException(e);
		}
		
		return routerTarget;
	}

	/**
	 * @param attachedResultSets
	 *           the attachedResultSets to set
	 */
	public void setAttachedResultSets(Set<ResultSet> attachedResultSets) {
		this.attachedResultSets = attachedResultSets;
	}

	public void setAutoCommit(boolean autoCommit) {
		this.autoCommit = autoCommit;
	}

	public void setConnection(ShardConnection dpConnection) {
		this.connection = dpConnection;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setCursorName(java.lang.String)
	 */
	@Override
	public void setCursorName(String name) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setCursorName");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setEscapeProcessing(boolean)
	 */
	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setEscapeProcessing");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setFetchDirection(int)
	 */
	@Override
	public void setFetchDirection(int direction) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setFetchDirection");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setFetchSize(int)
	 */
	@Override
	public void setFetchSize(int rows) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setFetchSize");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setMaxFieldSize(int)
	 */
	@Override
	public void setMaxFieldSize(int max) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setMaxFieldSize");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setMaxRows(int)
	 */
	@Override
	public void setMaxRows(int max) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setMaxRows");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setPoolable(boolean)
	 */
	@Override
	public void setPoolable(boolean poolable) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setPoolable");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Statement#setQueryTimeout(int)
	 */
	@Override
	public void setQueryTimeout(int seconds) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport setQueryTimeout");
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}

	public void setResultSetConcurrency(int resultSetConcurrency) {
		this.resultSetConcurrency = resultSetConcurrency;
	}

	public void setResultSetHoldability(int resultSetHoldability) {
		this.resultSetHoldability = resultSetHoldability;
	}

	public void setResultSetType(int resultSetType) {
		this.resultSetType = resultSetType;
	}

	public void setRouter(ShardRouter router) {
		this.router = router;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.Wrapper#unwrap(java.lang.Class)
	 */
	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		throw new UnsupportedOperationException("Zebra unsupport unwrap");
	}
}
