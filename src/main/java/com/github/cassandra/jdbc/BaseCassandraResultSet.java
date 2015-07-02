/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.github.cassandra.jdbc;

import static com.github.cassandra.jdbc.CassandraUtils.CURSOR_PREFIX;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
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
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

/**
 * This is the base class for implementing {@link java.sql.ResultSet} for
 * Cassandra.
 *
 * @author Zhichun Wu
 */
public abstract class BaseCassandraResultSet extends BaseJdbcObject implements
		ResultSet {
	protected final CassandraResultSetMetaData metadata = new CassandraResultSetMetaData();
	protected final BaseCassandraStatement statement;

	protected boolean wasNull;

	private int _rowIndex;
	private boolean _exhausted;

	protected BaseCassandraResultSet(BaseCassandraStatement statement) {
		super(statement == null || statement.quiet);

		this.statement = statement;
		this.wasNull = false;

		_rowIndex = 0;
		_exhausted = false;
	}

	protected int getCurrentRowIndex() {
		return _rowIndex;
	}

	protected void requestReadAccess(boolean random) throws SQLException {
		int type = getType();

		if (random && type == ResultSet.TYPE_FORWARD_ONLY) {
			throw CassandraErrors.notSupportedException();
		}
	}

	protected void requestWriteAccess() throws SQLException {
		int concurrency = getConcurrency();

		if (concurrency == ResultSet.CONCUR_READ_ONLY) {
			throw CassandraErrors.notSupportedException();
		}
	}

	protected void requestColumnAccess(int columnIndex) throws SQLException {
		if (columnIndex < 1) {
			throw new SQLException(
					"Column index starts from one and must be positive");
		} else if (columnIndex > metadata.getColumnCount()) {
			throw new SQLException("We only have " + metadata.getColumnCount()
					+ " columns but you're trying to get " + columnIndex);
		}
	}

	protected abstract <T> T getValue(int columnIndex, Class<T> clazz)
			throws SQLException;

	protected abstract <T> void setValue(int columnIndex, T value)
			throws SQLException;

	protected abstract boolean tryIterate() throws SQLException;

	/**
	 * Tries to move the cursor to a position according to given row index. This
	 * will also call {@link #updateCursorState(int)} automatically to ensure
	 * cursor state is up-to-date.
	 *
	 * @param rows
	 *            how many rows move forward(positive number) or
	 *            backward(negative number)
	 * @param relativeIndex
	 *            true if the rows is a relative number; false for absolute
	 *            index
	 * @return true if the cursor moved to the desired position successfully;
	 *         false otherwise
	 * @throws SQLException
	 *             when the operation failed
	 */
	protected abstract boolean tryMoveTo(int rows, boolean relativeIndex)
			throws SQLException;

	/**
	 * Tests if we can retrieve more results.
	 *
	 * @return true if we have more results; false otherwise
	 */
	protected abstract boolean hasMore();

	/**
	 * Update cursor state based on given row index. This method should be only
	 * called from {@link #tryMoveTo(int, boolean)}.
	 *
	 * @param rowIndex
	 *            row index the cursor is pointing to now
	 */
	protected void updateCursorState(int rowIndex) {
		if (_exhausted) {
			if (rowIndex > this._rowIndex) {
				this._rowIndex = rowIndex;
				_exhausted = false;
			}
		} else {
			if (rowIndex < 0) {
				this._rowIndex = -1;
				_exhausted = true;
			}
		}
	}

	public boolean next() throws SQLException {
		if (_exhausted) {
			if (_rowIndex >= 0) {
				_rowIndex = -1;
			}

			return false;
		}

		boolean movedToNext = tryIterate();
		if (movedToNext) {
			_rowIndex++;
			_exhausted = !hasMore();
		} else {
			_exhausted = true;
			_rowIndex = -1;
		}

		return movedToNext;
	}

	@Override
	public void close() throws SQLException {
		super.close();

		metadata.clear();

		if (statement != null && statement.isCloseOnCompletion()) {
			statement.close();
		}
	}

	public boolean wasNull() throws SQLException {
		return wasNull;
	}

	public String getString(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, String.class);
	}

	public boolean getBoolean(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);
		boolean result = false;

		if (obj instanceof Boolean) {
			result = (Boolean) obj;
		} else if (obj != null) {
			result = Boolean.toString(true).equalsIgnoreCase(
					String.valueOf(obj));
		}

		return result;
	}

	public byte getByte(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		byte result = 0;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).byteValue();
			} else {
				result = Byte.parseByte(String.valueOf(obj));
			}
		}

		return result;
	}

	public short getShort(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		short result = 0;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).shortValue();
			} else {
				result = Short.parseShort(String.valueOf(obj));
			}
		}

		return result;
	}

	public int getInt(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		int result = 0;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).intValue();
			} else {
				result = Integer.parseInt(String.valueOf(obj));
			}
		}

		return result;
	}

	public long getLong(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		long result = 0L;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).longValue();
			} else {
				result = Long.parseLong(String.valueOf(obj));
			}
		}

		return result;
	}

	public float getFloat(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		float result = 0.0F;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).floatValue();
			} else {
				result = Float.parseFloat(String.valueOf(obj));
			}
		}

		return result;
	}

	public double getDouble(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		double result = 0.0D;
		if (obj != null) {
			if (obj instanceof Number) {
				result = ((Number) obj).doubleValue();
			} else {
				result = Double.parseDouble(String.valueOf(obj));
			}
		}

		return result;
	}

	public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		BigDecimal result = BigDecimal.ZERO;
		if (obj != null) {
			if (obj instanceof BigDecimal) {
				result = (BigDecimal) obj;
			} else if (obj instanceof Number) {
				result = BigDecimal.valueOf(((Number) obj).doubleValue());
			} else {
				result = new BigDecimal(String.valueOf(obj));
			}
		}

		return result;
	}

	public BigDecimal getBigDecimal(int columnIndex, int scale)
			throws SQLException {
		BigDecimal result = getBigDecimal(columnIndex);
		if (result != null) {
			result.setScale(scale);
		}

		return result;
	}

	public byte[] getBytes(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, byte[].class);
	}

	public Date getDate(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		Date result = null;
		if (obj != null) {
			if (obj instanceof Date) {
				result = (Date) obj;
			} else if (obj instanceof java.util.Date) {
				result = new Date(((java.util.Date) obj).getTime());
			}
		}

		return result;
	}

	public Time getTime(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		Time result = null;
		if (obj != null) {
			if (obj instanceof Time) {
				result = (Time) obj;
			} else if (obj instanceof java.util.Date) {
				result = new Time(((java.util.Date) obj).getTime());
			}
		}

		return result;
	}

	public Timestamp getTimestamp(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		Timestamp result = null;
		if (obj != null) {
			if (obj instanceof Timestamp) {
				result = (Timestamp) obj;
			} else if (obj instanceof java.util.Date) {
				result = new Timestamp(((java.util.Date) obj).getTime());
			}
		}

		return result;
	}

	public InputStream getAsciiStream(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, InputStream.class);
	}

	public InputStream getUnicodeStream(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, InputStream.class);
	}

	public InputStream getBinaryStream(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, InputStream.class);
	}

	public Object getObject(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Object.class);
	}

	public Object getObject(int columnIndex, Map<String, Class<?>> map)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		String cqlTypeName = metadata.getColumnTypeName(columnIndex);
		if (map != null && map.containsKey(cqlTypeName)) {
			return getValue(columnIndex, map.get(cqlTypeName));
		}

		return getValue(columnIndex, Object.class);
	}

	public Ref getRef(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Ref.class);
	}

	public Blob getBlob(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Blob.class);
	}

	public Clob getClob(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Clob.class);
	}

	public Array getArray(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Array.class);
	}

	public Reader getCharacterStream(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Reader.class);
	}

	public Date getDate(int columnIndex, Calendar cal) throws SQLException {
		Date result = getDate(columnIndex);
		if (result != null && cal != null) {
			// FIXME use calendar
		}

		return result;
	}

	public Time getTime(int columnIndex, Calendar cal) throws SQLException {
		Time result = getTime(columnIndex);
		if (result != null && cal != null) {
			// FIXME use calendar
		}

		return result;
	}

	public Timestamp getTimestamp(int columnIndex, Calendar cal)
			throws SQLException {
		Timestamp result = getTimestamp(columnIndex);
		if (result != null && cal != null) {
			// FIXME use calendar
		}

		return result;
	}

	public URL getURL(int columnIndex) throws SQLException {
		Object obj = getObject(columnIndex);

		URL result = null;
		if (obj != null) {
			if (obj instanceof URL) {
				result = ((URL) obj);
			} else {
				try {
					result = new URL(String.valueOf(obj));
				} catch (MalformedURLException e) {
					if (!quiet) {
						throw new SQLException(e);
					}
				}
			}
		}

		return result;
	}

	public NClob getNClob(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, NClob.class);
	}

	public SQLXML getSQLXML(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, SQLXML.class);
	}

	public String getNString(int columnIndex) throws SQLException {
		return getString(columnIndex);
	}

	public Reader getNCharacterStream(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, Reader.class);
	}

	public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, type);
	}

	public String getString(String columnLabel) throws SQLException {
		return getString(findColumn(columnLabel));
	}

	public boolean getBoolean(String columnLabel) throws SQLException {
		return getBoolean(findColumn(columnLabel));
	}

	public byte getByte(String columnLabel) throws SQLException {
		return getByte(findColumn(columnLabel));
	}

	public short getShort(String columnLabel) throws SQLException {
		return getShort(findColumn(columnLabel));
	}

	public int getInt(String columnLabel) throws SQLException {
		return getInt(findColumn(columnLabel));
	}

	public long getLong(String columnLabel) throws SQLException {
		return getLong(findColumn(columnLabel));
	}

	public float getFloat(String columnLabel) throws SQLException {
		return getFloat(findColumn(columnLabel));
	}

	public double getDouble(String columnLabel) throws SQLException {
		return getDouble(findColumn(columnLabel));
	}

	public BigDecimal getBigDecimal(String columnLabel, int scale)
			throws SQLException {
		return getBigDecimal(findColumn(columnLabel), scale);
	}

	public byte[] getBytes(String columnLabel) throws SQLException {
		return getBytes(findColumn(columnLabel));
	}

	public Date getDate(String columnLabel) throws SQLException {
		return getDate(findColumn(columnLabel));
	}

	public Time getTime(String columnLabel) throws SQLException {
		return getTime(findColumn(columnLabel));
	}

	public Timestamp getTimestamp(String columnLabel) throws SQLException {
		return getTimestamp(findColumn(columnLabel));
	}

	public InputStream getAsciiStream(String columnLabel) throws SQLException {
		return getAsciiStream(findColumn(columnLabel));
	}

	public InputStream getUnicodeStream(String columnLabel) throws SQLException {
		return getUnicodeStream(findColumn(columnLabel));
	}

	public InputStream getBinaryStream(String columnLabel) throws SQLException {
		return getBinaryStream(findColumn(columnLabel));
	}

	public Object getObject(String columnLabel) throws SQLException {
		return getObject(findColumn(columnLabel));
	}

	public Reader getCharacterStream(String columnLabel) throws SQLException {
		return getCharacterStream(findColumn(columnLabel));
	}

	public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
		return getBigDecimal(findColumn(columnLabel));
	}

	public Object getObject(String columnLabel, Map<String, Class<?>> map)
			throws SQLException {
		return getObject(findColumn(columnLabel), map);
	}

	public Ref getRef(String columnLabel) throws SQLException {
		return getRef(findColumn(columnLabel));
	}

	public Blob getBlob(String columnLabel) throws SQLException {
		return getBlob(findColumn(columnLabel));
	}

	public Clob getClob(String columnLabel) throws SQLException {
		return getClob(findColumn(columnLabel));
	}

	public Array getArray(String columnLabel) throws SQLException {
		return getArray(findColumn(columnLabel));
	}

	public Date getDate(String columnLabel, Calendar cal) throws SQLException {
		return getDate(findColumn(columnLabel), cal);
	}

	public Time getTime(String columnLabel, Calendar cal) throws SQLException {
		return getTime(findColumn(columnLabel), cal);
	}

	public Timestamp getTimestamp(String columnLabel, Calendar cal)
			throws SQLException {
		return getTimestamp(findColumn(columnLabel), cal);
	}

	public URL getURL(String columnLabel) throws SQLException {
		return getURL(findColumn(columnLabel));
	}

	public NClob getNClob(String columnLabel) throws SQLException {
		return getNClob(findColumn(columnLabel));
	}

	public SQLXML getSQLXML(String columnLabel) throws SQLException {
		return getSQLXML(findColumn(columnLabel));
	}

	public String getNString(String columnLabel) throws SQLException {
		return getNString(findColumn(columnLabel));
	}

	public Reader getNCharacterStream(String columnLabel) throws SQLException {
		return getNCharacterStream(findColumn(columnLabel));
	}

	public <T> T getObject(String columnLabel, Class<T> type)
			throws SQLException {
		return getObject(findColumn(columnLabel), type);
	}

	public String getCursorName() throws SQLException {
		validateState();

		return statement == null ? CURSOR_PREFIX : statement.getCursorName();
	}

	public ResultSetMetaData getMetaData() throws SQLException {
		return metadata;
	}

	public int findColumn(String columnLabel) throws SQLException {
		return metadata.getColumnIndex(columnLabel) + 1;
	}

	public boolean isBeforeFirst() throws SQLException {
		validateState();

		return !_exhausted && _rowIndex == 0;
	}

	public boolean isAfterLast() throws SQLException {
		validateState();

		return _exhausted && _rowIndex < 0;
	}

	public boolean isFirst() throws SQLException {
		validateState();

		return _rowIndex == 1;
	}

	public boolean isLast() throws SQLException {
		validateState();

		return _exhausted && _rowIndex >= 0;
	}

	public void beforeFirst() throws SQLException {
		requestReadAccess(true);

		tryMoveTo(0, false);

	}

	public void afterLast() throws SQLException {
		requestReadAccess(true);

		tryMoveTo(-1, false);
	}

	public boolean first() throws SQLException {
		requestReadAccess(true);

		return tryMoveTo(1, false);
	}

	public boolean last() throws SQLException {
		afterLast();

		return tryMoveTo(-1, true);
	}

	public int getRow() throws SQLException {
		validateState();

		if (_rowIndex < 0) {
			throw new SQLException("Reached end of the result set already");
		}

		return _rowIndex;
	}

	public boolean absolute(int row) throws SQLException {
		requestReadAccess(true);

		return tryMoveTo(row, false);
	}

	public boolean relative(int rows) throws SQLException {
		requestReadAccess(true);

		return tryMoveTo(rows, true);
	}

	public boolean previous() throws SQLException {
		requestReadAccess(true);

		return tryMoveTo(-1, true);
	}

	public int getFetchDirection() throws SQLException {
		validateState();

		return statement == null ? ResultSet.FETCH_FORWARD : statement
				.getFetchDirection();
	}

	public void setFetchDirection(int direction) throws SQLException {
		validateState();

		if (statement != null) {
			statement.setFetchDirection(direction);
		}
	}

	public int getFetchSize() throws SQLException {
		validateState();

		return statement == null ? 0 : statement.getFetchSize();
	}

	public void setFetchSize(int rows) throws SQLException {
		validateState();

		if (statement != null) {
			statement.setFetchSize(rows);
		}
	}

	public int getType() throws SQLException {
		validateState();

		return statement == null ? ResultSet.TYPE_FORWARD_ONLY : statement
				.getResultSetType();
	}

	public int getConcurrency() throws SQLException {
		validateState();

		return statement == null ? ResultSet.CONCUR_READ_ONLY : statement
				.getResultSetConcurrency();
	}

	public boolean rowUpdated() throws SQLException {
		return false;
	}

	public boolean rowInserted() throws SQLException {
		return false;
	}

	public boolean rowDeleted() throws SQLException {
		return false;
	}

	public void updateNull(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, null);
	}

	public void updateBoolean(int columnIndex, boolean x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateByte(int columnIndex, byte x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateShort(int columnIndex, short x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateInt(int columnIndex, int x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateLong(int columnIndex, long x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateFloat(int columnIndex, float x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateDouble(int columnIndex, double x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateBigDecimal(int columnIndex, BigDecimal x)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateString(int columnIndex, String x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateBytes(int columnIndex, byte[] x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateDate(int columnIndex, Date x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateTime(int columnIndex, Time x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateTimestamp(int columnIndex, Timestamp x)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateAsciiStream(int columnIndex, InputStream x, int length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateBinaryStream(int columnIndex, InputStream x, int length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateCharacterStream(int columnIndex, Reader x, int length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateObject(int columnIndex, Object x, int scaleOrLength)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateObject(int columnIndex, Object x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateNull(String columnLabel) throws SQLException {
		updateNull(findColumn(columnLabel));
	}

	public void updateBoolean(String columnLabel, boolean x)
			throws SQLException {
		updateBoolean(findColumn(columnLabel), x);
	}

	public void updateByte(String columnLabel, byte x) throws SQLException {
		updateByte(findColumn(columnLabel), x);
	}

	public void updateShort(String columnLabel, short x) throws SQLException {
		updateShort(findColumn(columnLabel), x);
	}

	public void updateInt(String columnLabel, int x) throws SQLException {
		updateInt(findColumn(columnLabel), x);
	}

	public void updateLong(String columnLabel, long x) throws SQLException {
		updateLong(findColumn(columnLabel), x);
	}

	public void updateFloat(String columnLabel, float x) throws SQLException {
		updateFloat(findColumn(columnLabel), x);
	}

	public void updateDouble(String columnLabel, double x) throws SQLException {
		updateDouble(findColumn(columnLabel), x);
	}

	public void updateBigDecimal(String columnLabel, BigDecimal x)
			throws SQLException {
		updateBigDecimal(findColumn(columnLabel), x);
	}

	public void updateString(String columnLabel, String x) throws SQLException {
		updateString(findColumn(columnLabel), x);
	}

	public void updateBytes(String columnLabel, byte[] x) throws SQLException {
		updateBytes(findColumn(columnLabel), x);
	}

	public void updateDate(String columnLabel, Date x) throws SQLException {
		updateDate(findColumn(columnLabel), x);
	}

	public void updateTime(String columnLabel, Time x) throws SQLException {
		updateTime(findColumn(columnLabel), x);
	}

	public void updateTimestamp(String columnLabel, Timestamp x)
			throws SQLException {
		updateTimestamp(findColumn(columnLabel), x);
	}

	public void updateAsciiStream(String columnLabel, InputStream x, int length)
			throws SQLException {
		updateAsciiStream(findColumn(columnLabel), x, length);
	}

	public void updateBinaryStream(String columnLabel, InputStream x, int length)
			throws SQLException {
		updateBinaryStream(findColumn(columnLabel), x, length);
	}

	public void updateCharacterStream(String columnLabel, Reader reader,
			int length) throws SQLException {
		updateCharacterStream(findColumn(columnLabel), reader, length);
	}

	public void updateObject(String columnLabel, Object x, int scaleOrLength)
			throws SQLException {
		updateObject(findColumn(columnLabel), x, scaleOrLength);
	}

	public void updateObject(String columnLabel, Object x) throws SQLException {
		updateObject(findColumn(columnLabel), x);
	}

	public void insertRow() throws SQLException {
		requestWriteAccess();
	}

	public void updateRow() throws SQLException {
		requestWriteAccess();
	}

	public void deleteRow() throws SQLException {
		requestWriteAccess();
	}

	public void refreshRow() throws SQLException {
		requestWriteAccess();
	}

	public void cancelRowUpdates() throws SQLException {
		requestWriteAccess();
	}

	public void moveToInsertRow() throws SQLException {
		requestWriteAccess();
	}

	public void moveToCurrentRow() throws SQLException {
		requestWriteAccess();
	}

	public Statement getStatement() throws SQLException {
		return this.statement;
	}

	public void updateRef(int columnIndex, Ref x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateRef(String columnLabel, Ref x) throws SQLException {
		updateRef(findColumn(columnLabel), x);
	}

	public void updateBlob(int columnIndex, Blob x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateBlob(String columnLabel, Blob x) throws SQLException {
		updateBlob(findColumn(columnLabel), x);
	}

	public void updateClob(int columnIndex, Clob x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateClob(String columnLabel, Clob x) throws SQLException {
		updateClob(findColumn(columnLabel), x);
	}

	public void updateArray(int columnIndex, Array x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateNString(int columnIndex, String nString)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, nString);
	}

	public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, nClob);
	}

	public void updateSQLXML(int columnIndex, SQLXML xmlObject)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, xmlObject);
	}

	public void updateAsciiStream(int columnIndex, InputStream x, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, x);
	}

	public void updateBinaryStream(int columnIndex, InputStream x, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, x);
	}

	public void updateCharacterStream(int columnIndex, Reader x, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, x);
	}

	public void updateBlob(int columnIndex, InputStream inputStream, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, inputStream);
	}

	public void updateClob(int columnIndex, Reader reader, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, reader);
	}

	public void updateNClob(int columnIndex, Reader reader, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, reader);
	}

	public void updateNCharacterStream(int columnIndex, Reader x, long length)
			throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		// FIXME limit bytes/chars read here
		setValue(columnIndex, x);
	}

	public void updateNCharacterStream(int columnIndex, Reader x)
			throws SQLException {
		updateNCharacterStream(columnIndex, x, 0);
	}

	public void updateAsciiStream(int columnIndex, InputStream x)
			throws SQLException {
		updateAsciiStream(columnIndex, x, 0);
	}

	public void updateBinaryStream(int columnIndex, InputStream x)
			throws SQLException {
		updateBinaryStream(columnIndex, x, 0);
	}

	public void updateCharacterStream(int columnIndex, Reader x)
			throws SQLException {
		updateCharacterStream(columnIndex, x, 0);
	}

	public void updateBlob(int columnIndex, InputStream inputStream)
			throws SQLException {
		updateBlob(columnIndex, inputStream, 0);
	}

	public void updateClob(int columnIndex, Reader reader) throws SQLException {
		updateClob(columnIndex, reader, 0);
	}

	public void updateNClob(int columnIndex, Reader reader) throws SQLException {
		updateNClob(columnIndex, reader, 0);
	}

	public void updateArray(String columnLabel, Array x) throws SQLException {
		updateArray(findColumn(columnLabel), x);
	}

	public void updateNString(String columnLabel, String nString)
			throws SQLException {
		updateNString(findColumn(columnLabel), nString);
	}

	public void updateNClob(String columnLabel, NClob nClob)
			throws SQLException {
		updateNClob(findColumn(columnLabel), nClob);
	}

	public void updateSQLXML(String columnLabel, SQLXML xmlObject)
			throws SQLException {
		updateSQLXML(findColumn(columnLabel), xmlObject);
	}

	public void updateNCharacterStream(String columnLabel, Reader reader,
			long length) throws SQLException {
		updateNCharacterStream(findColumn(columnLabel), reader, length);
	}

	public void updateAsciiStream(String columnLabel, InputStream x, long length)
			throws SQLException {
		updateAsciiStream(findColumn(columnLabel), x, length);
	}

	public void updateBinaryStream(String columnLabel, InputStream x,
			long length) throws SQLException {
		updateBinaryStream(findColumn(columnLabel), x, length);
	}

	public void updateCharacterStream(String columnLabel, Reader reader,
			long length) throws SQLException {
		updateCharacterStream(findColumn(columnLabel), reader, length);
	}

	public void updateBlob(String columnLabel, InputStream inputStream,
			long length) throws SQLException {
		updateBlob(findColumn(columnLabel), inputStream, length);
	}

	public void updateClob(String columnLabel, Reader reader, long length)
			throws SQLException {
		updateClob(findColumn(columnLabel), reader, length);
	}

	public void updateNClob(String columnLabel, Reader reader, long length)
			throws SQLException {
		updateNClob(findColumn(columnLabel), reader, length);
	}

	public void updateNCharacterStream(String columnLabel, Reader reader)
			throws SQLException {
		updateNCharacterStream(findColumn(columnLabel), reader);
	}

	public void updateAsciiStream(String columnLabel, InputStream x)
			throws SQLException {
		updateAsciiStream(findColumn(columnLabel), x);
	}

	public void updateBinaryStream(String columnLabel, InputStream x)
			throws SQLException {
		updateBinaryStream(findColumn(columnLabel), x);
	}

	public void updateCharacterStream(String columnLabel, Reader reader)
			throws SQLException {
		updateCharacterStream(findColumn(columnLabel), reader);
	}

	public void updateBlob(String columnLabel, InputStream inputStream)
			throws SQLException {
		updateBlob(findColumn(columnLabel), inputStream);
	}

	public void updateClob(String columnLabel, Reader reader)
			throws SQLException {
		updateClob(findColumn(columnLabel), reader);
	}

	public void updateNClob(String columnLabel, Reader reader)
			throws SQLException {
		updateNClob(findColumn(columnLabel), reader);
	}

	public RowId getRowId(int columnIndex) throws SQLException {
		requestColumnAccess(columnIndex);
		requestReadAccess(false);

		return getValue(columnIndex, RowId.class);
	}

	public RowId getRowId(String columnLabel) throws SQLException {
		return getRowId(findColumn(columnLabel));
	}

	public void updateRowId(int columnIndex, RowId x) throws SQLException {
		requestColumnAccess(columnIndex);
		requestWriteAccess();

		setValue(columnIndex, x);
	}

	public void updateRowId(String columnLabel, RowId x) throws SQLException {
		updateRowId(findColumn(columnLabel), x);
	}

	public int getHoldability() throws SQLException {
		validateState();

		return statement == null ? ResultSet.HOLD_CURSORS_OVER_COMMIT
				: statement.getResultSetHoldability();
	}
}