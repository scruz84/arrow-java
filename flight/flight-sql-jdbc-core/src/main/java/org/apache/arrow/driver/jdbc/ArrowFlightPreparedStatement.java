/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.driver.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import org.apache.arrow.driver.jdbc.client.ArrowFlightSqlClientHandler;
import org.apache.arrow.driver.jdbc.client.ClientSidePreparedStatement;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.apache.arrow.driver.jdbc.utils.ConvertUtils;
import org.apache.arrow.driver.jdbc.utils.SqlValueFactory;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.util.Preconditions;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.avatica.AvaticaPreparedStatement;
import org.apache.calcite.avatica.ColumnMetaData.Rep;
import org.apache.calcite.avatica.Meta.Signature;
import org.apache.calcite.avatica.Meta.StatementHandle;
import org.apache.calcite.avatica.remote.TypedValue;

/** Arrow Flight JBCS's implementation {@link PreparedStatement}. */
public class ArrowFlightPreparedStatement extends AvaticaPreparedStatement
    implements ArrowFlightInfoStatement {

  private final ArrowFlightSqlClientHandler.PreparedStatement preparedStatement;

  private ArrowFlightPreparedStatement(
      final ArrowFlightConnection connection,
      final ArrowFlightSqlClientHandler.PreparedStatement preparedStatement,
      final StatementHandle handle,
      final Signature signature,
      final int resultSetType,
      final int resultSetConcurrency,
      final int resultSetHoldability)
      throws SQLException {
    super(connection, handle, signature, resultSetType, resultSetConcurrency, resultSetHoldability);
    this.preparedStatement = Preconditions.checkNotNull(preparedStatement);
  }

  static ArrowFlightPreparedStatement newPreparedStatement(
      final ArrowFlightConnection connection,
      final ArrowFlightSqlClientHandler.PreparedStatement preparedStmt,
      final StatementHandle statementHandle,
      final Signature signature,
      final int resultSetType,
      final int resultSetConcurrency,
      final int resultSetHoldability)
      throws SQLException {
    return new ArrowFlightPreparedStatement(
        connection,
        preparedStmt,
        statementHandle,
        signature,
        resultSetType,
        resultSetConcurrency,
        resultSetHoldability);
  }

  @Override
  public ArrowFlightConnection getConnection() throws SQLException {
    return (ArrowFlightConnection) super.getConnection();
  }

  /**
   * Gets the result set metadata. For a client-side query there is no server-side prepared
   * statement to describe the result set, so the first call asks the server once, if the {@code
   * clientSideMetadataProbe} property allows it.
   */
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    if (preparedStatement instanceof ClientSidePreparedStatement) {
      final Signature signature = getSignature();
      if (signature != null && signature.columns.isEmpty()) {
        final Schema schema =
            ((ClientSidePreparedStatement) preparedStatement).probeDataSetSchema();
        signature.columns.addAll(
            ConvertUtils.convertArrowFieldsToColumnMetaDataList(schema.getFields()));
      }
    }
    return super.getMetaData();
  }

  // Parameters of client-side statements.
  //
  // Avatica rejects or ignores many parameter types: java.time values, UUID, BigInteger, arrays,
  // structs, LOBs and streams. A client-side statement renders its parameters into the SQL text
  // itself, so it can take them all. These are converted to a SqlValue when they are set, which
  // reports an unsupported value at the setter and reads streams only once. Statements prepared on
  // the server keep Avatica's behavior.

  private boolean isClientSide() {
    return preparedStatement instanceof ClientSidePreparedStatement;
  }

  private static boolean isNativeToAvatica(final Object x) {
    return x == null
        || x instanceof Boolean
        || x instanceof Byte
        || x instanceof Short
        || x instanceof Integer
        || x instanceof Long
        || x instanceof Float
        || x instanceof Double
        || x instanceof BigDecimal
        || x instanceof Character
        || x instanceof String
        || x instanceof byte[];
  }

  /**
   * Tells whether a client-side statement converts the object itself. Avatica turns dates, times
   * and timestamps into milliseconds in the zone of the connection, which loses the nanoseconds and
   * ignores the default zone of the JVM, so these are not left to it. Neither does it honor a
   * target date or time type, so a value set with one is converted here too.
   */
  private static boolean isConvertedByDriver(final Object x, final int targetSqlType) {
    return !isNativeToAvatica(x) || SqlValueFactory.isDateTimeType(targetSqlType);
  }

  private void setValue(final int parameterIndex, final SqlValue value) throws SQLException {
    getSite(parameterIndex); // checks the statement is open and the index is valid
    slots[parameterIndex - 1] = TypedValue.ofLocal(Rep.OBJECT, value);
  }

  private void setRaw(final int parameterIndex, final Object value) throws SQLException {
    setValue(parameterIndex, SqlValueFactory.fromJava(value));
  }

  private static void checkLength(final long length) throws SQLException {
    if (length < 0) {
      throw new SQLException("Parameter length cannot be negative: " + length);
    }
    if (length > SqlValueFactory.MAX_LOB_LENGTH) {
      throw new SQLException("Parameter is larger than the size allowed in a statement");
    }
  }

  private static String readCharacters(final Reader reader, final long length) throws SQLException {
    checkLength(length);
    final StringBuilder sb = new StringBuilder();
    final char[] buffer = new char[8192];
    try {
      long remaining = length;
      while (remaining > 0) {
        final int read = reader.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) {
          break;
        }
        sb.append(buffer, 0, read);
        remaining -= read;
      }
    } catch (final IOException e) {
      throw new SQLException("Failed to read a character stream parameter", e);
    }
    return sb.toString();
  }

  private static byte[] readBytes(final InputStream stream, final long length) throws SQLException {
    checkLength(length);
    try {
      return stream.readNBytes((int) length);
    } catch (final IOException e) {
      throw new SQLException("Failed to read a binary stream parameter", e);
    }
  }

  private static byte[] readAllBytes(final InputStream stream) throws SQLException {
    try {
      final byte[] bytes = stream.readNBytes(SqlValueFactory.MAX_LOB_LENGTH + 1);
      if (bytes.length > SqlValueFactory.MAX_LOB_LENGTH) {
        throw new SQLException("Parameter is larger than the size allowed in a statement");
      }
      return bytes;
    } catch (final IOException e) {
      throw new SQLException("Failed to read a binary stream parameter", e);
    }
  }

  @Override
  public void setObject(final int parameterIndex, final Object x) throws SQLException {
    if (isClientSide() && !isNativeToAvatica(x)) {
      setRaw(parameterIndex, x);
    } else {
      super.setObject(parameterIndex, x);
    }
  }

  @Override
  public void setObject(final int parameterIndex, final Object x, final int targetSqlType)
      throws SQLException {
    if (isClientSide() && x != null && isConvertedByDriver(x, targetSqlType)) {
      setValue(parameterIndex, SqlValueFactory.fromJava(x, targetSqlType));
    } else {
      super.setObject(parameterIndex, x, targetSqlType);
    }
  }

  @Override
  public void setObject(
      final int parameterIndex, final Object x, final int targetSqlType, final int scaleOrLength)
      throws SQLException {
    if (isClientSide() && x != null && isConvertedByDriver(x, targetSqlType)) {
      setValue(parameterIndex, SqlValueFactory.fromJava(x, targetSqlType));
    } else {
      super.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
  }

  @Override
  public void setDate(final int parameterIndex, final Date x) throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromDate(x, null));
    } else {
      super.setDate(parameterIndex, x);
    }
  }

  @Override
  public void setDate(final int parameterIndex, final Date x, final Calendar calendar)
      throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromDate(x, calendar));
    } else {
      super.setDate(parameterIndex, x, calendar);
    }
  }

  @Override
  public void setTime(final int parameterIndex, final Time x) throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromTime(x, null));
    } else {
      super.setTime(parameterIndex, x);
    }
  }

  @Override
  public void setTime(final int parameterIndex, final Time x, final Calendar calendar)
      throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromTime(x, calendar));
    } else {
      super.setTime(parameterIndex, x, calendar);
    }
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x) throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromTimestamp(x, null));
    } else {
      super.setTimestamp(parameterIndex, x);
    }
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x, final Calendar calendar)
      throws SQLException {
    if (isClientSide() && x != null) {
      setValue(parameterIndex, SqlValueFactory.fromTimestamp(x, calendar));
    } else {
      super.setTimestamp(parameterIndex, x, calendar);
    }
  }

  @Override
  public void setArray(final int parameterIndex, final java.sql.Array x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, x);
    } else {
      super.setArray(parameterIndex, x);
    }
  }

  @Override
  public void setNString(final int parameterIndex, final String value) throws SQLException {
    if (isClientSide() && value != null) {
      setValue(parameterIndex, new SqlScalar(value, SqlType.of(Types.NVARCHAR)));
    } else {
      super.setNString(parameterIndex, value);
    }
  }

  @Override
  public void setURL(final int parameterIndex, final URL x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, x.toString());
    } else {
      super.setURL(parameterIndex, x);
    }
  }

  @Override
  public void setSQLXML(final int parameterIndex, final SQLXML xmlObject) throws SQLException {
    if (isClientSide() && xmlObject != null) {
      setRaw(parameterIndex, xmlObject);
    } else {
      super.setSQLXML(parameterIndex, xmlObject);
    }
  }

  @Override
  public void setClob(final int parameterIndex, final Clob x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, x);
    } else {
      super.setClob(parameterIndex, x);
    }
  }

  @Override
  public void setNClob(final int parameterIndex, final NClob value) throws SQLException {
    if (isClientSide() && value != null) {
      setRaw(parameterIndex, value);
    } else {
      super.setNClob(parameterIndex, value);
    }
  }

  @Override
  public void setBlob(final int parameterIndex, final Blob x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, x);
    } else {
      super.setBlob(parameterIndex, x);
    }
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader) throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, reader);
    } else {
      super.setClob(parameterIndex, reader);
    }
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, readCharacters(reader, length));
    } else {
      super.setClob(parameterIndex, reader, length);
    }
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader) throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, reader);
    } else {
      super.setNClob(parameterIndex, reader);
    }
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, readCharacters(reader, length));
    } else {
      super.setNClob(parameterIndex, reader, length);
    }
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream) throws SQLException {
    if (isClientSide() && inputStream != null) {
      setRaw(parameterIndex, inputStream);
    } else {
      super.setBlob(parameterIndex, inputStream);
    }
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream, final long length)
      throws SQLException {
    if (isClientSide() && inputStream != null) {
      setRaw(parameterIndex, readBytes(inputStream, length));
    } else {
      super.setBlob(parameterIndex, inputStream, length);
    }
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader)
      throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, reader);
    } else {
      super.setCharacterStream(parameterIndex, reader);
    }
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader, final int length)
      throws SQLException {
    setCharacterStream(parameterIndex, reader, (long) length);
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader, final long length)
      throws SQLException {
    if (isClientSide() && reader != null) {
      setRaw(parameterIndex, readCharacters(reader, length));
    } else {
      super.setCharacterStream(parameterIndex, reader, length);
    }
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value)
      throws SQLException {
    if (isClientSide() && value != null) {
      setRaw(parameterIndex, value);
    } else {
      super.setNCharacterStream(parameterIndex, value);
    }
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value, final long length)
      throws SQLException {
    if (isClientSide() && value != null) {
      setRaw(parameterIndex, readCharacters(value, length));
    } else {
      super.setNCharacterStream(parameterIndex, value, length);
    }
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, x);
    } else {
      super.setBinaryStream(parameterIndex, x);
    }
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final int length)
      throws SQLException {
    setBinaryStream(parameterIndex, x, (long) length);
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final long length)
      throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, readBytes(x, length));
    } else {
      super.setBinaryStream(parameterIndex, x, length);
    }
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x) throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, new String(readAllBytes(x), StandardCharsets.US_ASCII));
    } else {
      super.setAsciiStream(parameterIndex, x);
    }
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final int length)
      throws SQLException {
    setAsciiStream(parameterIndex, x, (long) length);
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final long length)
      throws SQLException {
    if (isClientSide() && x != null) {
      setRaw(parameterIndex, new String(readBytes(x, length), StandardCharsets.US_ASCII));
    } else {
      super.setAsciiStream(parameterIndex, x, length);
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    this.preparedStatement.close();
    super.close();
  }

  @Override
  public FlightInfo executeFlightInfoQuery() throws SQLException {
    return preparedStatement.executeQuery();
  }
}
