/**
 * Project: zebra-client
 *
 * File Created at Feb 20, 2014
 *
 */
package com.dianping.zebra.group.jdbc;

import com.dianping.zebra.group.datasources.SingleConnection;
import com.dianping.zebra.group.filter.JdbcFilter;
import com.dianping.zebra.group.filter.JdbcMetaData;
import com.dianping.zebra.group.jdbc.param.*;
import com.dianping.zebra.group.util.JDBCExceptionUtils;
import com.dianping.zebra.group.util.SqlType;
import com.dianping.zebra.group.util.SqlUtils;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * @author Leo Liang
 */
public class GroupPreparedStatement extends GroupStatement implements PreparedStatement {

	private int autoGeneratedKeys = -1;

	private int[] columnIndexes;

	private String[] columnNames;

	private List<ParamContext> params = new ArrayList<ParamContext>();

	private List<List<ParamContext>> pstBatchedArgs;

	private String sql;

	public GroupPreparedStatement(GroupConnection connection, String sql, JdbcMetaData metaData, JdbcFilter filter) {
		super(connection, metaData, filter);
		this.sql = sql;
		this.metaData.setSql(sql);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#addBatch()
	 */
	@Override
	public void addBatch() throws SQLException {
		if (pstBatchedArgs == null) {
			pstBatchedArgs = new ArrayList<List<ParamContext>>();
		}
		List<ParamContext> newArgs = new ArrayList<ParamContext>(params.size());
		newArgs.addAll(params);

		params.clear();

		pstBatchedArgs.add(newArgs);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#clearParameters()
	 */
	@Override
	public void clearParameters() throws SQLException {
		params.clear();
	}

	private PreparedStatement createPreparedStatementInternal(Connection conn, String sql) throws SQLException {
		PreparedStatement pstmt;
		if (autoGeneratedKeys != -1) {
			pstmt = conn.prepareStatement(sql, autoGeneratedKeys);
		} else if (columnIndexes != null) {
			pstmt = conn.prepareStatement(sql, columnIndexes);
		} else if (columnNames != null) {
			pstmt = conn.prepareStatement(sql, columnNames);
		} else {
			int resultSetHoldability = this.resultSetHoldability;
			if (resultSetHoldability == -1) {
				resultSetHoldability = conn.getHoldability();
			}

			pstmt = conn.prepareStatement(sql, this.resultSetType, this.resultSetConcurrency, resultSetHoldability);
		}
		setRealStatement(pstmt);
		pstmt.setQueryTimeout(queryTimeout);
		pstmt.setFetchSize(fetchSize);
		pstmt.setMaxRows(maxRows);

		return pstmt;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#execute()
	 */
	@Override
	public boolean execute() throws SQLException {
		SqlType sqlType = SqlUtils.getSqlType(sql);
		if (sqlType.isQuery()) {
			executeQuery();
			return true;
		} else {
			this.updateCount = executeUpdate();
			return false;
		}
	}

	public int[] executeBatch() throws SQLException {
		try {
			checkClosed();
			closeCurrentResultSet();

			if (pstBatchedArgs == null || pstBatchedArgs.isEmpty()) {
				return new int[0];
			}

			return new JDBCOperationCallback<int[]>() {

				@Override
				public int[] doAction(Connection conn) throws SQLException {
					return executeBatchOnConnection(conn);
				}
			}.doAction(this.dpGroupConnection.getRealConnection(sql, true));

		} finally {
			if (pstBatchedArgs != null) {
				pstBatchedArgs.clear();
			}
		}
	}

	private int[] executeBatchOnConnection(Connection conn) throws SQLException {
		PreparedStatement pstmt = createPreparedStatementInternal(conn, sql);

		for (List<ParamContext> tmpParams : pstBatchedArgs) {
			setBatchParams(pstmt, tmpParams);
			pstmt.addBatch();
		}

		return pstmt.executeBatch();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#executeQuery()
	 */
	@Override
	public ResultSet executeQuery() throws SQLException {
		checkClosed();
		closeCurrentResultSet();

		return new GroupResultSet(new JDBCOperationCallback<ResultSet>() {

			@Override
			public ResultSet doAction(Connection conn) throws SQLException {
				return executeQueryOnConnection(conn, sql);
			}
		}.doAction(this.dpGroupConnection.getRealConnection(sql, false)));
	}

	private ResultSet executeQueryOnConnection(Connection conn, String sql) throws SQLException {
		PreparedStatement pstmt = createPreparedStatementInternal(conn, sql);
		setParams(pstmt);
		this.currentResultSet = pstmt.executeQuery();
		return this.currentResultSet;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#executeUpdate()
	 */
	@Override
	public int executeUpdate() throws SQLException {
		checkClosed();
		closeCurrentResultSet();

		return new JDBCOperationCallback<Integer>() {

			@Override
			public Integer doAction(Connection conn) throws SQLException {
				try {
					updateCount = executeUpdateOnConnection(conn);
				} catch (SQLException e) {
					if (conn instanceof SingleConnection) {
						((SingleConnection) conn).getDataSource().getPunisher().countAndPunish(e);
					}

					JDBCExceptionUtils.throwWrappedSQLException(e);
				}

				return updateCount;
			}

		}.doAction(this.dpGroupConnection.getRealConnection(sql, true));
	}

	private int executeUpdateOnConnection(final Connection conn) throws SQLException {
		PreparedStatement pstmt = createPreparedStatementInternal(conn, sql);

		setParams(pstmt);
		return pstmt.executeUpdate();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#getMetaData()
	 */
	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		throw new UnsupportedOperationException("getMetaData");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#getParameterMetaData()
	 */
	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		throw new UnsupportedOperationException("getParameterMetaData");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setArray(int, java.sql.Array)
	 */
	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		params.add(new ArrayParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setAsciiStream(int, java.io.InputStream)
	 */
	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setAsciiStream(int, java.io.InputStream, int)
	 */
	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setAsciiStream(int, java.io.InputStream, long)
	 */
	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x, length }));
	}

	public void setAutoGeneratedKeys(int autoGeneratedKeys) {
		this.autoGeneratedKeys = autoGeneratedKeys;
	}

	private void setBatchParams(PreparedStatement pstmt, List<ParamContext> params) throws SQLException {
		for (ParamContext param : params) {
			param.setParam(pstmt);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBigDecimal(int, java.math.BigDecimal)
	 */
	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
		params.add(new BigDecimalParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBinaryStream(int, java.io.InputStream)
	 */
	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBinaryStream(int, java.io.InputStream, int)
	 */
	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBinaryStream(int, java.io.InputStream, long)
	 */
	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		params.add(new BinaryStreamParamContext(parameterIndex, new Object[] { x, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBlob(int, java.sql.Blob)
	 */
	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBlob(int, java.io.InputStream)
	 */
	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { inputStream }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBlob(int, java.io.InputStream, long)
	 */
	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		params.add(new BlobParamContext(parameterIndex, new Object[] { inputStream, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBoolean(int, boolean)
	 */
	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		params.add(new BooleanParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setByte(int, byte)
	 */
	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		params.add(new ByteParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setBytes(int, byte[])
	 */
	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {
		params.add(new ByteArrayParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setCharacterStream(int, java.io.Reader)
	 */
	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setCharacterStream(int, java.io.Reader, int)
	 */
	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setCharacterStream(int, java.io.Reader, long)
	 */
	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new CharacterStreamParamContext(parameterIndex, new Object[] { reader, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setClob(int, java.sql.Clob)
	 */
	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setClob(int, java.io.Reader)
	 */
	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { reader }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setClob(int, java.io.Reader, long)
	 */
	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new ClobParamContext(parameterIndex, new Object[] { reader, length }));
	}

	public void setColumnIndexes(int[] columnIndexes) {
		this.columnIndexes = columnIndexes;
	}

	public void setColumnNames(String[] columnNames) {
		this.columnNames = columnNames;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setDate(int, java.sql.Date)
	 */
	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {
		params.add(new DateParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setDate(int, java.sql.Date, java.util.Calendar)
	 */
	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		params.add(new DateParamContext(parameterIndex, new Object[] { x, cal }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setDouble(int, double)
	 */
	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {
		params.add(new DoubleParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setFloat(int, float)
	 */
	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {
		params.add(new FloatParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setInt(int, int)
	 */
	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		params.add(new IntParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setLong(int, long)
	 */
	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		params.add(new LongParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNCharacterStream(int, java.io.Reader)
	 */
	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		params.add(new NCharacterStreamParamContext(parameterIndex, new Object[] { value }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNCharacterStream(int, java.io.Reader, long)
	 */
	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		params.add(new NCharacterStreamParamContext(parameterIndex, new Object[] { value, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNClob(int, java.sql.NClob)
	 */
	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { value }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNClob(int, java.io.Reader)
	 */
	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { reader }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNClob(int, java.io.Reader, long)
	 */
	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		params.add(new NClobParamContext(parameterIndex, new Object[] { reader, length }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNString(int, java.lang.String)
	 */
	@Override
	public void setNString(int parameterIndex, String value) throws SQLException {
		params.add(new NStringParamContext(parameterIndex, new Object[] { value }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNull(int, int)
	 */
	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		params.add(new NullParamContext(parameterIndex, new Object[] { sqlType }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setNull(int, int, java.lang.String)
	 */
	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		params.add(new NullParamContext(parameterIndex, new Object[] { sqlType, typeName }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setObject(int, java.lang.Object)
	 */
	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setObject(int, java.lang.Object, int)
	 */
	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x, targetSqlType }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setObject(int, java.lang.Object, int, int)
	 */
	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		params.add(new ObjectParamContext(parameterIndex, new Object[] { x, targetSqlType, scaleOrLength }));
	}

	protected void setParams(PreparedStatement pstmt) throws SQLException {
		for (ParamContext paramContext : params) {
			paramContext.setParam(pstmt);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setRef(int, java.sql.Ref)
	 */
	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		params.add(new RefParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setRowId(int, java.sql.RowId)
	 */
	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		params.add(new RowIdParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.sql.PreparedStatement#setSQLXML(int, java.sql.SQLXML)
	 */
	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		params.add(new SQLXMLParamContext(parameterIndex, new Object[] { xmlObject }));
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.sql.PreparedStatement#setShort(int, short)
	 */
	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		params.add(new ShortParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setString(int, java.lang.String)
	 */
	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		params.add(new StringParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setTime(int, java.sql.Time)
	 */
	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {
		params.add(new TimeParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setTime(int, java.sql.Time, java.util.Calendar)
	 */
	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
		params.add(new TimeParamContext(parameterIndex, new Object[] { x, cal }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setTimestamp(int, java.sql.Timestamp)
	 */
	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		params.add(new TimestampParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.sql.PreparedStatement#setTimestamp(int, java.sql.Timestamp, java.util.Calendar)
	 */
	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		params.add(new TimestampParamContext(parameterIndex, new Object[] { x, cal }));
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.sql.PreparedStatement#setURL(int, java.net.URL)
	 */
	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		params.add(new URLParamContext(parameterIndex, new Object[] { x }));
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.sql.PreparedStatement#setUnicodeStream(int, java.io.InputStream, int)
	 */
	@Override
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
		params.add(new AsciiParamContext(parameterIndex, new Object[] { x }));
	}

}
