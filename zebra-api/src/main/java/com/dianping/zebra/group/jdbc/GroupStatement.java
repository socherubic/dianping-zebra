/**
 * Project: zebra-client
 *
 * File Created at Feb 19, 2014
 *
 */
package com.dianping.zebra.group.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dianping.zebra.filter.DefaultJdbcFilterChain;
import com.dianping.zebra.filter.JdbcFilter;
import com.dianping.zebra.group.exception.DalNotSupportException;
import com.dianping.zebra.single.jdbc.SingleConnection;
import com.dianping.zebra.util.JDBCUtils;
import com.dianping.zebra.util.SqlType;
import com.dianping.zebra.util.SqlUtils;

/**
 * @author Leo Liang
 */
public class GroupStatement implements Statement {

	protected GroupConnection dpGroupConnection;

	protected List<JdbcFilter> filters;

	protected Statement openedStatement = null;

	protected GroupResultSet currentResultSet = null;

	protected List<String> batchedSqls;

	protected boolean closed = false;

	protected int fetchSize;

	protected int maxRows;

	protected boolean moreResults = false;

	protected int queryTimeout = 0;

	protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;

	protected int resultSetHoldability = -1;

	protected int resultSetType = ResultSet.TYPE_FORWARD_ONLY;

	protected int updateCount;

	public GroupStatement(GroupConnection connection, List<JdbcFilter> filters) {
		this.dpGroupConnection = connection;
		this.filters = filters;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		checkClosed();

		if (batchedSqls == null) {
			batchedSqls = new ArrayList<String>();
		}
		if (sql != null) {
			batchedSqls.add(sql);
		}
	}

	@Override
	public void cancel() throws SQLException {
		throw new UnsupportedOperationException("cancel");
	}

	protected void checkClosed() throws SQLException {
		if (closed) {
			throw new SQLException("No operations allowed after statement closed.");
		}
	}

	@Override
	public void clearBatch() throws SQLException {
		checkClosed();
		if (batchedSqls != null) {
			batchedSqls.clear();
		}
	}

	@Override
	public void clearWarnings() throws SQLException {
		checkClosed();
		if (openedStatement != null) {
			openedStatement.clearWarnings();
		}
	}

	@Override
	public void close() throws SQLException {
		if (closed) {
			return;
		}
		closed = true;

		try {
			if (currentResultSet != null) {
				currentResultSet.close();
			}
		} finally {
			currentResultSet = null;
		}

		try {
			if (this.openedStatement != null) {
				this.openedStatement.close();
			}
		} finally {
			this.openedStatement = null;
		}
	}

	protected void closeCurrentResultSet() throws SQLException {
		if (currentResultSet != null) {
			try {
				currentResultSet.close();
			} catch (SQLException e) {
				// ignore it
			} finally {
				currentResultSet = null;
			}
		}
	}

	public void closeOnCompletion() throws SQLException {
		throw new DalNotSupportException();
	}

	private Statement createStatementInternal(Connection conn, boolean isBatch) throws SQLException {
		Statement stmt;
		if (isBatch) {
			stmt = conn.createStatement();
		} else {
			int tmpResultSetHoldability = this.resultSetHoldability;
			if (tmpResultSetHoldability == -1) {
				tmpResultSetHoldability = conn.getHoldability();
			}

			stmt = conn.createStatement(this.resultSetType, this.resultSetConcurrency, tmpResultSetHoldability);
		}

		setRealStatement(stmt);
		stmt.setQueryTimeout(queryTimeout);
		stmt.setFetchSize(fetchSize);
		stmt.setMaxRows(maxRows);

		return stmt;
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		return executeInternal(sql, -1, null, null);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return executeInternal(sql, autoGeneratedKeys, null, null);
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return executeInternal(sql, -1, columnIndexes, null);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		return executeInternal(sql, -1, null, columnNames);
	}

	@Override
	public int[] executeBatch() throws SQLException {
		try {
			checkClosed();
			closeCurrentResultSet();

			if (batchedSqls == null || batchedSqls.isEmpty()) {
				return new int[0];
			}

			return executeWithFilter(new JDBCOperationCallback<int[]>() {
				@Override
				public int[] doAction(Connection conn) throws SQLException {
					return executeBatchOnConnection(conn, batchedSqls);
				}
			}, null, null, true, true);
		} finally {
			if (batchedSqls != null) {
				batchedSqls.clear();
			}
		}
	}

	private int[] executeBatchOnConnection(final Connection conn, final List<String> batchedSqls) throws SQLException {
		Statement stmt = createStatementInternal(conn, true);
		for (String sql : batchedSqls) {
			stmt.addBatch(processSQL(conn, sql, false));
		}
		return stmt.executeBatch();
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

	@Override
	public ResultSet executeQuery(final String sql) throws SQLException {
		checkClosed();
		closeCurrentResultSet();

		return executeWithFilter(new JDBCOperationCallback<ResultSet>() {
			@Override
			public ResultSet doAction(Connection conn) throws SQLException {
				return executeQueryOnConnection(conn, sql);
			}
		}, sql, null, false, false);
	}

	private ResultSet executeQueryOnConnection(Connection conn, String sql) throws SQLException {
		sql = processSQL(conn, sql, false);
		Statement stmt = createStatementInternal(conn, false);
		currentResultSet = new GroupResultSet(stmt.executeQuery(sql));
		return currentResultSet;
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		return executeUpdateInternal(sql, -1, null, null);
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		return executeUpdateInternal(sql, autoGeneratedKeys, null, null);
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		return executeUpdateInternal(sql, -1, columnIndexes, null);
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return executeUpdateInternal(sql, -1, null, columnNames);
	}

	private int executeUpdateInternal(final String sql, final int autoGeneratedKeys, final int[] columnIndexes,
			final String[] columnNames) throws SQLException {

		checkClosed();
		closeCurrentResultSet();

		return executeWithFilter(new JDBCOperationCallback<Integer>() {
			@Override
			public Integer doAction(Connection conn) throws SQLException {
				try {
					updateCount = executeUpdateOnConnection(conn, sql, autoGeneratedKeys, columnIndexes, columnNames);
				} catch (SQLException e) {
					if (conn instanceof SingleConnection) {
						((SingleConnection) conn).getDataSource().getPunisher().countAndPunish(e);
					}
					JDBCUtils.throwWrappedSQLException(e);
				}
				return updateCount;
			}
		}, sql, null, false, true);
	}

	private int executeUpdateOnConnection(Connection conn, String sql, int autoGeneratedKeys, int[] columnIndexes,
			String[] columnNames) throws SQLException {
		sql = processSQL(conn, sql, false);

		Statement stmt = createStatementInternal(conn, false);

		if (autoGeneratedKeys == -1 && columnIndexes == null && columnNames == null) {
			return stmt.executeUpdate(sql);
		} else if (autoGeneratedKeys != -1) {
			return stmt.executeUpdate(sql, autoGeneratedKeys);
		} else if (columnIndexes != null) {
			return stmt.executeUpdate(sql, columnIndexes);
		} else if (columnNames != null) {
			return stmt.executeUpdate(sql, columnNames);
		} else {
			return stmt.executeUpdate(sql);
		}
	}

	@SuppressWarnings({ "unchecked", "hiding" })
	protected <T> T executeWithFilter(final JDBCOperationCallback<T> callback, final String sql, Object params,
			boolean isBatch, final boolean forceWriter) throws SQLException {
		Connection conn;
		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public Connection getRealConnection(GroupStatement source, String sql, boolean forceWriter,
						JdbcFilter chain) throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).getRealConnection(source, sql, forceWriter, chain);
					} else {
						return source.getRealConnectionOrigin(sql, forceWriter);
					}
				}
			};
			conn = chain.getRealConnection(this, sql, forceWriter, chain);
		} else {
			conn = getRealConnectionOrigin(sql, forceWriter);
		}

		if (filters != null && filters.size() > 0) {
			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public <T> T executeGroupStatement(GroupStatement source, Connection conn, String sql,
						List<String> batchedSql, boolean isBatched, boolean autoCommit, Object params, JdbcFilter chain)
								throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).executeGroupStatement(source, conn, sql, (List<String>) batchedSql,
								isBatched, autoCommit, params, chain);
					} else {
						return (T) executeWithFilterOrigin(callback, conn);
					}
				}
			};
			return chain.executeGroupStatement(this, conn, sql, batchedSqls, isBatch,
					this.dpGroupConnection.getAutoCommit(), params, chain);
		} else {
			return executeWithFilterOrigin(callback, conn);
		}
	}

	private Connection getRealConnectionOrigin(String sql, boolean forceWriter) throws SQLException {
		return this.dpGroupConnection.getRealConnection(sql, forceWriter);
	}

	private <T> T executeWithFilterOrigin(JDBCOperationCallback<T> callback, Connection conn) throws SQLException {
		return callback.doAction(conn);
	}

	@Override
	public Connection getConnection() throws SQLException {
		return this.dpGroupConnection;
	}

	@Override
	public int getFetchDirection() throws SQLException {
		throw new UnsupportedOperationException("getFetchDirection");
	}

	@Override
	public void setFetchDirection(int direction) throws SQLException {
		throw new UnsupportedOperationException("setFetchDirection");
	}

	@Override
	public int getFetchSize() throws SQLException {
		return this.fetchSize;
	}

	@Override
	public void setFetchSize(int fetchSize) throws SQLException {
		this.fetchSize = fetchSize;
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		if (this.openedStatement != null) {
			return new GroupResultSet(this.openedStatement.getGeneratedKeys());
		} else {
			throw new SQLException("No update operations executed before getGeneratedKeys");
		}
	}

	@Override
	public int getMaxFieldSize() throws SQLException {
		throw new UnsupportedOperationException("getMaxFieldSize");
	}

	@Override
	public void setMaxFieldSize(int max) throws SQLException {
		throw new UnsupportedOperationException("setMaxFieldSize");
	}

	@Override
	public int getMaxRows() throws SQLException {
		return maxRows;
	}

	@Override
	public void setMaxRows(int maxRows) throws SQLException {
		this.maxRows = maxRows;
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		return moreResults;
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		throw new UnsupportedOperationException("getMoreResults");
	}

	@Override
	public int getQueryTimeout() throws SQLException {
		return queryTimeout;
	}

	@Override
	public void setQueryTimeout(int queryTimeout) throws SQLException {
		this.queryTimeout = queryTimeout;
	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		return currentResultSet;
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		return this.resultSetConcurrency;
	}

	public void setResultSetConcurrency(int resultSetConcurrency) {
		this.resultSetConcurrency = resultSetConcurrency;
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		return this.resultSetHoldability;
	}

	public void setResultSetHoldability(int resultSetHoldability) {
		this.resultSetHoldability = resultSetHoldability;
	}

	@Override
	public int getResultSetType() throws SQLException {
		return this.resultSetType;
	}

	public void setResultSetType(int resultSetType) {
		this.resultSetType = resultSetType;
	}

	@Override
	public int getUpdateCount() throws SQLException {
		return this.updateCount;
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		checkClosed();
		if (openedStatement != null) {
			return openedStatement.getWarnings();
		}
		return null;
	}

	public boolean isCloseOnCompletion() throws SQLException {
		throw new DalNotSupportException();
	}

	@Override
	public boolean isClosed() throws SQLException {
		return closed;
	}

	@Override
	public boolean isPoolable() throws SQLException {
		throw new DalNotSupportException();
	}

	@Override
	public void setPoolable(boolean poolable) throws SQLException {
		throw new DalNotSupportException();
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return this.getClass().isAssignableFrom(iface);
	}

	protected String processSQL(Connection conn, final String sql, boolean isPreparedStmt) throws SQLException {
		if (filters != null && filters.size() > 0) {
			SingleConnection singleConn = conn instanceof SingleConnection ? (SingleConnection) conn : null;

			JdbcFilter chain = new DefaultJdbcFilterChain(filters) {
				@Override
				public String sql(SingleConnection conn, String sql, boolean isPreparedStmt, JdbcFilter chain)
						throws SQLException {
					if (index < filters.size()) {
						return filters.get(index++).sql(conn, sql, isPreparedStmt, chain);
					} else {
						return sql;
					}
				}
			};
			return chain.sql(singleConn, sql, isPreparedStmt, chain);
		} else {
		}
		return sql;
	}

	@Override
	public void setCursorName(String name) throws SQLException {
		throw new UnsupportedOperationException("setCursorName");
	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		throw new UnsupportedOperationException("setEscapeProcessing");
	}

	void setRealStatement(Statement realStatement) {
		if (this.openedStatement != null) {
			try {
				this.openedStatement.close();
			} catch (SQLException e) {
				// ignore it
			}
		}
		this.openedStatement = realStatement;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		try {
			return (T) this;
		} catch (Exception e) {
			throw new SQLException(e);
		}
	}

}
