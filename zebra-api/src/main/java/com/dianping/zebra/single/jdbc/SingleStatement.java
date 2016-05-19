/**
 * Project: zebra-sql-monitor-client
 *
 * File Created at 2011-10-28
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
package com.dianping.zebra.single.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dianping.zebra.filter.DefaultJdbcFilterChain;
import com.dianping.zebra.filter.JdbcFilter;
import com.dianping.zebra.filter.JdbcOperationCallback;
import com.dianping.zebra.util.SqlType;
import com.dianping.zebra.util.SqlUtils;

/**
 * @author hao.zhu
 */
public class SingleStatement implements Statement {

	private String dsId;

	private List<String> batchedSqls;

	protected final Connection innerConnection;

	protected final List<JdbcFilter> filters;

	protected final Statement openedStatement;

	protected boolean closed;

	public SingleStatement(String dsId, Connection innerConnection, List<JdbcFilter> filters, Statement stmt)
			throws SQLException {
		this.dsId = dsId;
		this.innerConnection = innerConnection;
		this.filters = filters;
		this.openedStatement = stmt;
	}

	@Override
	public ResultSet executeQuery(final String sql) throws SQLException {
		checkClosed();
		final String processedSql = processSQL(sql, false);
		return executeWithFilter(new JdbcOperationCallback<ResultSet>() {
			@Override
			public ResultSet doAction(Connection conn) throws SQLException {
				return openedStatement.executeQuery(processedSql);
			}
		}, sql, null, false);
	}

	@Override
	public int executeUpdate(final String sql) throws SQLException {
		checkClosed();
		final String processedSql = processSQL(sql, false);
		return executeWithFilter(new JdbcOperationCallback<Integer>() {
			@Override
			public Integer doAction(Connection conn) throws SQLException {
				return openedStatement.executeUpdate(processedSql);
			}
		}, sql, null, false);
	}

	@Override
	public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
		checkClosed();
		final String processedSql = processSQL(sql, false);
		return executeWithFilter(new JdbcOperationCallback<Integer>() {
			@Override
			public Integer doAction(Connection conn) throws SQLException {
				return openedStatement.executeUpdate(processedSql, autoGeneratedKeys);
			}
		}, sql, null, false);
	}

	@Override
	public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
		checkClosed();
		final String processedSql = processSQL(sql, false);
		return executeWithFilter(new JdbcOperationCallback<Integer>() {
			@Override
			public Integer doAction(Connection conn) throws SQLException {
				return openedStatement.executeUpdate(processedSql, columnIndexes);
			}
		}, sql, null, false);
	}

	@Override
	public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
		checkClosed();
		final String processedSql = processSQL(sql, false);
		return executeWithFilter(new JdbcOperationCallback<Integer>() {
			@Override
			public Integer doAction(Connection conn) throws SQLException {
				return openedStatement.executeUpdate(processedSql, columnNames);
			}
		}, sql, null, false);
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		checkClosed();
		return executeInternal(sql, -1, null, null);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		checkClosed();
		return executeInternal(sql, autoGeneratedKeys, null, null);
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		checkClosed();
		return executeInternal(sql, -1, columnIndexes, null);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		checkClosed();
		return executeInternal(sql, -1, null, columnNames);
	}

	@Override
	public int[] executeBatch() throws SQLException {
		checkClosed();
		try {
			return executeWithFilter(new JdbcOperationCallback<int[]>() {
				@Override
				public int[] doAction(Connection conn) throws SQLException {
					return openedStatement.executeBatch();
				}
			}, null, null, true);
		} finally {
			if (batchedSqls != null) {
				batchedSqls.clear();
			}
		}
	}

	private boolean executeInternal(String sql, int autoGeneratedKeys, int[] columnIndexes, String[] columnNames)
			throws SQLException {
		SqlType sqlType = SqlUtils.getSqlType(sql);
		if (sqlType.isQuery()) {
			executeQuery(sql);
			return true;
		} else {
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
		}
	}

	protected String processSQL(final String sql, boolean isPreparedStmt) throws SQLException {
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public String processSQL(String dsId, String sql, boolean isPreparedStmt, JdbcFilter chain)
						throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).processSQL(dsId, sql, isPreparedStmt, chain);
					} else {
						return sql;
					}
				}
			};

			return chain.processSQL(this.dsId, sql, isPreparedStmt, chain);
		}

		return sql;
	}

	@SuppressWarnings({ "unchecked", "hiding" })
	protected <T> T executeWithFilter(final JdbcOperationCallback<T> callback, final String sql, Object params,
			boolean isBatch) throws SQLException {
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public <T> T executeSingleStatement(SingleStatement source, Connection conn, String sql,
						List<String> batchedSql, boolean isBatched, boolean autoCommit, Object params, JdbcFilter chain)
								throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).executeSingleStatement(source, conn, sql, (List<String>) batchedSql,
								isBatched, autoCommit, params, chain);
					} else {
						return (T) executeWithFilterOrigin(callback, conn);
					}
				}
			};

			return chain.executeSingleStatement(this, this.innerConnection, sql, null, isBatch,
					this.innerConnection.getAutoCommit(), params, chain);
		} else {
			return executeWithFilterOrigin(callback, this.innerConnection);
		}
	}

	private <T> T executeWithFilterOrigin(JdbcOperationCallback<T> callback, Connection conn) throws SQLException {
		return callback.doAction(conn);
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		if (batchedSqls == null) {
			batchedSqls = new ArrayList<String>();
		}

		if (sql != null) {
			batchedSqls.add(sql);
		}

		this.openedStatement.addBatch(sql);
	}

	@Override
	public void clearBatch() throws SQLException {
		this.openedStatement.clearBatch();
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		return this.openedStatement.getGeneratedKeys();
	}

	@Override
	public Connection getConnection() throws SQLException {
		return innerConnection;
	}

	@Override
	public void cancel() throws SQLException {
		this.openedStatement.cancel();
	}

	@Override
	public void close() throws SQLException {
		if (closed) {
			return;
		}
		if (this.openedStatement != null) {
			this.openedStatement.close();
		}
		closed = true;
	}

	protected void checkClosed() throws SQLException {
		if (this.closed) {
			throw new SQLException("Operation not supported after statement closed.");
		}
	}

	@Override
	public int getMaxFieldSize() throws SQLException {
		return this.openedStatement.getMaxFieldSize();
	}

	@Override
	public void setMaxFieldSize(int max) throws SQLException {
		this.openedStatement.setMaxFieldSize(max);
	}

	@Override
	public int getMaxRows() throws SQLException {
		return this.openedStatement.getMaxRows();
	}

	@Override
	public void setMaxRows(int max) throws SQLException {
		this.openedStatement.setMaxRows(max);
	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		this.openedStatement.setEscapeProcessing(enable);
	}

	@Override
	public int getQueryTimeout() throws SQLException {
		return this.openedStatement.getQueryTimeout();
	}

	@Override
	public void setQueryTimeout(int seconds) throws SQLException {
		this.openedStatement.setQueryTimeout(seconds);
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		return this.openedStatement.getWarnings();
	}

	@Override
	public void clearWarnings() throws SQLException {
		this.openedStatement.clearWarnings();
	}

	@Override
	public void setCursorName(String name) throws SQLException {
		this.openedStatement.setCursorName(name);
	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		return this.openedStatement.getResultSet();
	}

	@Override
	public int getUpdateCount() throws SQLException {
		return this.openedStatement.getUpdateCount();
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		return this.openedStatement.getMoreResults(current);
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		return this.openedStatement.getMoreResults();
	}

	@Override
	public int getFetchDirection() throws SQLException {
		return this.openedStatement.getFetchDirection();
	}

	@Override
	public void setFetchDirection(int direction) throws SQLException {
		this.openedStatement.setFetchDirection(direction);
	}

	@Override
	public int getFetchSize() throws SQLException {
		return this.openedStatement.getFetchSize();
	}

	@Override
	public void setFetchSize(int rows) throws SQLException {
		this.openedStatement.setFetchSize(rows);
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		return this.openedStatement.getResultSetConcurrency();
	}

	@Override
	public int getResultSetType() throws SQLException {
		return this.openedStatement.getResultSetType();
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		return this.openedStatement.getResultSetHoldability();
	}

	@Override
	public boolean isClosed() throws SQLException {
		return this.openedStatement.isClosed();
	}

	@Override
	public boolean isPoolable() throws SQLException {
		return this.openedStatement.isPoolable();
	}

	@Override
	public void setPoolable(boolean poolable) throws SQLException {
		this.openedStatement.setPoolable(poolable);
	}

	public void closeOnCompletion() throws SQLException {
		this.openedStatement.closeOnCompletion();
	}

	public boolean isCloseOnCompletion() throws SQLException {
		return this.openedStatement.isCloseOnCompletion();
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		return this.openedStatement.unwrap(iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return this.openedStatement.isWrapperFor(iface);
	}
}
