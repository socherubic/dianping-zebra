/**
 * Project: zebra-client
 *
 * File Created at 2011-6-22
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
package com.dianping.zebra.shard.merge;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 数据池， 用于隐藏真实数据的来源。<br>
 * 真实数据来源包括两个可能：
 * <ol>
 * <li>由若干个<tt>ResultSet</tt>简单串联组成的复合<tt>ResultSet</tt>
 * ，这种情况通常不需要Zebra进行数据处理（没有跨表跨库orderby并且没有跨库跨表聚合函数列存在）。</li>
 * <li>由若干个<tt>ResultSet</tt>中的所有数据经过全局排序以及数据合并（主要针对跨库跨表的全局聚合函数）而得到的
 * <tt>List</tt>组成。这种情况的数据池称作内存数据池</li>
 * </ol>
 * 在遍历数据的时候，<br>
 * 对于第一种情况，我们只要简单的按照顺序遍历每一个<tt>ResultSet</tt>并且调用具体的<tt>ResultSet</tt>方法即可。<br>
 * 对于第二种情况，我们需要遍历<tt>List</tt>，同时进行必要的数据类型转换。<br>
 * <p/>
 * 数据池支持limit子句的，通过设定对应的<tt>skip</tt>，<tt>max</tt>属性并调用<tt>procLimit</tt>
 * 方法以调整数据池的初始状态。<br>
 *
 * @author Leo Liang
 */
public class DataPool {

	private List<ResultSet> resultSets = new ArrayList<ResultSet>();

	private List<RowData> memoryData;

	private boolean inMemory;

	private int resultSetIndex = 0;

	private int rowNum = 0;

	private int skip = MergeContext.NO_OFFSET;

	private int max = MergeContext.NO_LIMIT;

	private boolean wasNull = false;

	/**
	 * 滚动数据池游标到下一条记录
	 *
	 * @return
	 * @throws java.sql.SQLException
	 */
	public boolean next() throws SQLException {
		rowNum++;
		if (!inMemory) {
			if (max != MergeContext.NO_LIMIT && rowNum > max) {
				return false;
			}
			if (!resultSets.get(resultSetIndex).next()) {
				while (++resultSetIndex < resultSets.size()) {
					if (resultSets.get(resultSetIndex).next()) {
						break;
					}
				}
				if (resultSetIndex >= resultSets.size()) {
					return false;
				} else {
					return true;
				}
			} else {
				return true;
			}
		} else {
			return rowNum - 1 < memoryData.size();
		}
	}

	/**
	 * @return the skip
	 */
	public int getSkip() {
		return skip;
	}

	/**
	 * @param skip
	 *            the skip to set
	 */
	public void setSkip(int skip) {
		this.skip = skip;
	}

	/**
	 * @return the max
	 */
	public int getMax() {
		return max;
	}

	/**
	 * @param max
	 *            the max to set
	 */
	public void setMax(int max) {
		this.max = max;
	}

	/**
	 * 设定内存数据(<tt>List</tt>)
	 *
	 * @param memoryData
	 *            the memoryData to set
	 */
	public void setMemoryData(List<RowData> memoryData) {
		this.memoryData = memoryData;
	}

	/**
	 * 是否内存数据池
	 *
	 * @return the inMemory
	 */
	public boolean isInMemory() {
		return inMemory;
	}

	/**
	 * @param inMemory
	 *            the inMemory to set
	 */
	public void setInMemory(boolean inMemory) {
		this.inMemory = inMemory;
	}

	/**
	 * @param resultSets
	 *            the resultSets to set
	 */
	public void setResultSets(List<ResultSet> resultSets) {
		this.resultSets = resultSets;
	}

	/**
	 * <p>
	 * 处理limit
	 * </p>
	 *
	 * @throws java.sql.SQLException
	 */
	public void procLimit() throws SQLException {
		if (inMemory) {
			int fromIndex = skip == MergeContext.NO_OFFSET ? 0 : skip;
			if (fromIndex >= memoryData.size()) {
				this.memoryData = new ArrayList<RowData>();
				return;
			}
			int toIndex = max == MergeContext.NO_LIMIT ? memoryData.size() : fromIndex + max;
			toIndex = toIndex > memoryData.size() ? memoryData.size() : toIndex;
			List<RowData> subDataList = memoryData.subList(fromIndex, toIndex);

			this.memoryData = new ArrayList<RowData>(subDataList);
		} else {
			if (skip != MergeContext.NO_OFFSET) {
				int rowSkipped = 0;
				for (int i = 0; i < resultSets.size(); i++) {
					resultSetIndex = i;
					while (resultSets.get(i).next()) {
						if (++rowSkipped >= skip) {
							break;
						}
					}

					if (rowSkipped >= skip) {
						break;
					}
				}
			}
		}
	}

	/**
	 * 清理数据池中所有数据，并重置所有状态位
	 */
	public void clear() {
		this.resultSets.clear();
		if (inMemory && this.memoryData != null) {
			this.memoryData.clear();
		}
		this.inMemory = false;
		this.resultSetIndex = 0;
		this.rowNum = 0;
	}

	/**
	 * 获得当前数据偏移辆（以1开始）
	 *
	 * @return
	 */
	public int getCurrentRowNo() {
		return rowNum;
	}

	public int findColumn(String columnName) throws SQLException {
		if (inMemory) {
			return memoryData.get(rowNum - 1).getIndexByName(columnName);
		} else {
			return resultSets.get(resultSetIndex).findColumn(columnName);
		}
	}

	public Array getArray(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Array) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getArray(columnIndex);
		}
	}

	public Array getArray(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Array) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getArray(columnName);
		}
	}

	public InputStream getAsciiStream(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getAsciiStream(columnIndex);
		}
	}

	public InputStream getAsciiStream(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getAsciiStream(columnName);
		}
	}

	public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (BigDecimal) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBigDecimal(columnIndex);
		}
	}

	public BigDecimal getBigDecimal(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (BigDecimal) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBigDecimal(columnName);
		}
	}

	@SuppressWarnings("deprecation")
	public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return ((BigDecimal) memoryData.get(rowNum - 1).get(columnIndex).getValue()).setScale(scale);
		} else {
			return resultSets.get(resultSetIndex).getBigDecimal(columnIndex, scale);
		}
	}

	@SuppressWarnings("deprecation")
	public BigDecimal getBigDecimal(String columnName, int scale) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return ((BigDecimal) memoryData.get(rowNum - 1).get(columnName).getValue()).setScale(scale);
		} else {
			return resultSets.get(resultSetIndex).getBigDecimal(columnName, scale);
		}
	}

	public InputStream getBinaryStream(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBinaryStream(columnIndex);
		}
	}

	public InputStream getBinaryStream(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBinaryStream(columnName);
		}
	}

	public Blob getBlob(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Blob) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBlob(columnIndex);
		}
	}

	public Blob getBlob(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Blob) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBlob(columnName);
		}
	}

	public boolean getBoolean(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Boolean) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBoolean(columnIndex);
		}
	}

	public boolean getBoolean(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Boolean) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBoolean(columnName);
		}
	}

	public byte getByte(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Byte) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getByte(columnIndex);
		}
	}

	public byte getByte(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Byte) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getByte(columnName);
		}
	}

	public byte[] getBytes(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (byte[]) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBytes(columnIndex);
		}
	}

	public byte[] getBytes(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (byte[]) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getBytes(columnName);
		}
	}

	public Reader getCharacterStream(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Reader) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getCharacterStream(columnIndex);
		}
	}

	public Reader getCharacterStream(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Reader) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getCharacterStream(columnName);
		}
	}

	public Clob getClob(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Clob) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getClob(columnIndex);
		}
	}

	public Clob getClob(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Clob) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getClob(columnName);
		}
	}

	public int getConcurrency() throws SQLException {

		if (inMemory) {
			return memoryData.get(rowNum - 1).getConcurrency();
		} else {
			return resultSets.get(resultSetIndex).getConcurrency();
		}

	}

	public String getCursorName() throws SQLException {
		if (inMemory) {
			return memoryData.get(rowNum - 1).getCursorName();
		} else {
			return resultSets.get(resultSetIndex).getCursorName();
		}
	}

	public Date getDate(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Date) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getDate(columnIndex);
		}
	}

	public Date getDate(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Date) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getDate(columnName);
		}
	}

	public Date getDate(int columnIndex, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getDate with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(columnIndex).getDate(columnIndex, cal);
		}
	}

	public Date getDate(String columnName, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getDate with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(resultSetIndex).getDate(columnName, cal);
		}
	}

	public double getDouble(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Double) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getDouble(columnIndex);
		}
	}

	public double getDouble(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Double) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getDouble(columnName);
		}
	}

	public float getFloat(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Float) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getFloat(columnIndex);
		}
	}

	public float getFloat(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Float) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getFloat(columnName);
		}
	}

	public int getHoldability() throws SQLException {
		if (inMemory) {
			return memoryData.get(rowNum - 1).getHoldability();
		} else {
			return resultSets.get(resultSetIndex).getHoldability();
		}
	}

	public int getInt(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			Object val = memoryData.get(rowNum - 1).get(columnIndex).getValue();
			if (val instanceof Integer) {
				return (Integer) val;
			} else {
				return Integer.parseInt(val.toString());
			}
		} else {
			return resultSets.get(resultSetIndex).getInt(columnIndex);
		}
	}

	public int getInt(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			Object val = memoryData.get(rowNum - 1).get(columnName).getValue();
			if (val instanceof Integer) {
				return (Integer) val;
			} else {
				return Integer.parseInt(val.toString());
			}
		} else {
			return resultSets.get(resultSetIndex).getInt(columnName);
		}
	}

	public long getLong(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Long) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getInt(columnIndex);
		}
	}

	public long getLong(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Long) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getLong(columnName);
		}
	}

	public ResultSetMetaData getMetaData() throws SQLException {
		if (inMemory) {
			return memoryData.get(rowNum == 0 ? 0 : rowNum - 1).getResultSetMetaData();
		} else {
			return resultSets.get(resultSetIndex).getMetaData();
		}
	}

	public Reader getNCharacterStream(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Reader) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNCharacterStream(columnIndex);
		}
	}

	public Reader getNCharacterStream(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Reader) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNCharacterStream(columnName);
		}
	}

	public NClob getNClob(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (NClob) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNClob(columnIndex);
		}
	}

	public NClob getNClob(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (NClob) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNClob(columnName);
		}
	}

	public String getNString(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (String) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNString(columnIndex);
		}
	}

	public String getNString(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (String) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getNString(columnName);
		}
	}

	public Object getObject(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getObject(columnIndex);
		}
	}

	public Object getObject(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getObject(columnName);
		}
	}

	public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
		// Mysql Connector-j doesn't use the parameter map at all....
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getObject(columnIndex);
		}
	}

	public Object getObject(String columnName, Map<String, Class<?>> map) throws SQLException {
		// Mysql Connector-j doesn't use the parameter map at all....
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getObject(columnName);
		}
	}

	public Ref getRef(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Ref) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getRef(columnIndex);
		}
	}

	public Ref getRef(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Ref) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getRef(columnName);
		}
	}

	public RowId getRowId(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (RowId) memoryData.get(rowNum - 1).get(columnIndex).getRowId();
		} else {
			return resultSets.get(resultSetIndex).getRowId(columnIndex);
		}
	}

	public RowId getRowId(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (RowId) memoryData.get(rowNum - 1).get(columnName).getRowId();
		} else {
			return resultSets.get(resultSetIndex).getRowId(columnName);
		}
	}

	public SQLXML getSQLXML(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (SQLXML) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getSQLXML(columnIndex);
		}
	}

	public SQLXML getSQLXML(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (SQLXML) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getSQLXML(columnName);
		}
	}

	public short getShort(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Short) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getShort(columnIndex);
		}
	}

	public short getShort(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Short) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getShort(columnName);
		}
	}

	public String getString(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (String) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getString(columnIndex);
		}
	}

	public String getString(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (String) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getString(columnName);
		}
	}

	public Time getTime(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Time) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getTime(columnIndex);
		}
	}

	public Time getTime(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Time) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getTime(columnName);
		}
	}

	public Time getTime(int columnIndex, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getTime with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(resultSetIndex).getTime(columnIndex, cal);
		}
	}

	public Time getTime(String columnName, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getTime with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(resultSetIndex).getTime(columnName, cal);
		}
	}

	public Timestamp getTimestamp(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (Timestamp) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getTimestamp(columnIndex);
		}
	}

	public Timestamp getTimestamp(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (Timestamp) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getTimestamp(columnName);
		}
	}

	public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getTimestamp with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(resultSetIndex).getTimestamp(columnIndex, cal);
		}
	}

	public Timestamp getTimestamp(String columnName, Calendar cal) throws SQLException {
		if (inMemory) {
			throw new UnsupportedOperationException(
					"Zebra unsupport getTimestamp with Calendar in a multi actual datasource query.");
		} else {
			return resultSets.get(resultSetIndex).getTimestamp(columnName, cal);
		}
	}

	public int getType() throws SQLException {
		if (inMemory) {
			if (rowNum >= 1) {
				return memoryData.get(rowNum - 1).getResultSetType();
			} else if (memoryData != null && memoryData.size() > 0) {
				return memoryData.get(0).getResultSetType();
			} else {
				return resultSets.get(resultSetIndex).getType();
			}
		} else {
			return resultSets.get(resultSetIndex).getType();
		}
	}

	public URL getURL(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (URL) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getURL(columnIndex);
		}
	}

	public URL getURL(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (URL) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getURL(columnName);
		}
	}

	@SuppressWarnings("deprecation")
	public InputStream getUnicodeStream(int columnIndex) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnIndex).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnIndex).getValue();
		} else {
			return resultSets.get(resultSetIndex).getUnicodeStream(columnIndex);
		}
	}

	@SuppressWarnings("deprecation")
	public InputStream getUnicodeStream(String columnName) throws SQLException {
		if (inMemory) {
			wasNull = memoryData.get(rowNum - 1).get(columnName).isWasNull();
			return (InputStream) memoryData.get(rowNum - 1).get(columnName).getValue();
		} else {
			return resultSets.get(resultSetIndex).getUnicodeStream(columnName);
		}
	}

	public boolean wasNull() throws SQLException {
		if (inMemory) {
			return wasNull;
		} else {
			return resultSets.get(resultSetIndex).wasNull();
		}
	}

}
