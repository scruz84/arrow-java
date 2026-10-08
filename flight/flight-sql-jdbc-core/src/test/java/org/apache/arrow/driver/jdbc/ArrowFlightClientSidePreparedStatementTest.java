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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import org.apache.arrow.driver.jdbc.utils.MockFlightSqlProducer;
import org.apache.arrow.driver.jdbc.utils.SqlValueFactory;
import org.apache.arrow.flight.sql.FlightSqlUtils;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests statements whose parameters are written into the SQL on the client. The mock server only
 * knows the fully rendered statements, and counts the prepared statements it is asked to create.
 */
public class ArrowFlightClientSidePreparedStatementTest {
  private static final String CREATE_PREPARED =
      FlightSqlUtils.FLIGHT_SQL_CREATE_PREPARED_STATEMENT.getType();
  private static final Schema ID_SCHEMA =
      new Schema(Collections.singletonList(Field.nullable("id", new ArrowType.Int(32, true))));

  public static final MockFlightSqlProducer PRODUCER = new MockFlightSqlProducer();

  @RegisterExtension
  public static final FlightServerTestExtension FLIGHT_SERVER_TEST_EXTENSION =
      FlightServerTestExtension.createStandardTestExtension(PRODUCER);

  private static final Map<String, Object> ALL_CLIENT_SIDE =
      Map.of("disableServerPreparedStatements", true);
  private static final Map<String, Object> QUERIES_CLIENT_SIDE =
      Map.of("disableServerPreparedQueries", true);

  private TimeZone defaultTimeZone;

  @BeforeEach
  public void before() {
    defaultTimeZone = TimeZone.getDefault();
    PRODUCER.clearActionTypeCounter();
    PRODUCER.clearReceivedStatements();
  }

  @AfterEach
  public void after() {
    TimeZone.setDefault(defaultTimeZone);
  }

  /** Registers a query on the mock server that returns one row with the given id. */
  private static void addQuery(final String sql, final int id) {
    PRODUCER.addSelectQuery(
        sql,
        ID_SCHEMA,
        Collections.singletonList(
            listener -> {
              try (final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                  final VectorSchemaRoot root = VectorSchemaRoot.create(ID_SCHEMA, allocator)) {
                root.allocateNew();
                ((IntVector) root.getVector(0)).setSafe(0, id);
                root.setRowCount(1);
                listener.start(root);
                listener.putNext();
              } catch (final Throwable t) {
                listener.error(t);
              } finally {
                listener.completed();
              }
            }));
  }

  private static void assertSingleRow(final ResultSet resultSet, final int id) throws SQLException {
    assertTrue(resultSet.next());
    assertEquals(id, resultSet.getInt(1));
    assertFalse(resultSet.next());
  }

  private static void assertNotPreparedOnServer() {
    assertFalse(PRODUCER.getActionTypeCounter().containsKey(CREATE_PREPARED));
  }

  private PreparedStatement prepare(final Connection connection, final String sql)
      throws SQLException {
    return connection.prepareStatement(sql);
  }

  @Test
  public void testParametersAreRenderedIntoTheQuery() throws Exception {
    addQuery("SELECT id FROM t WHERE a = 5 AND b = 'it''s'", 1);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t WHERE a = ? AND b = ?")) {
      ps.setInt(1, 5);
      ps.setString(2, "it's");
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 1);
      }
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testStatementCanBeExecutedAgainWithOtherValues() throws Exception {
    addQuery("SELECT id FROM t2 WHERE a = 1", 11);
    addQuery("SELECT id FROM t2 WHERE a = 2", 22);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t2 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 11);
      }
      ps.setInt(1, 2);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 22);
      }
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testExecuteReportsAResultSet() throws Exception {
    addQuery("SELECT id FROM t3 WHERE a = 1", 3);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t3 WHERE a = ?")) {
      ps.setInt(1, 1);
      assertTrue(ps.execute());
      assertSingleRow(ps.getResultSet(), 3);
    }
  }

  @Test
  public void testMarkersInsideLiteralsAreNotParameters() throws Exception {
    addQuery("SELECT id FROM t4 WHERE note = 'what?' AND a = 1 -- really?", 4);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t4 WHERE note = 'what?' AND a = ? -- really?")) {
      assertEquals(1, ps.getParameterMetaData().getParameterCount());
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 4);
      }
    }
  }

  @Test
  public void testNegativeNumberCannotTurnIntoAComment() throws Exception {
    addQuery("SELECT id FROM t5 WHERE a = 1-(-5)", 5);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t5 WHERE a = 1-?")) {
      ps.setInt(1, -5);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 5);
      }
    }
  }

  @Test
  public void testNullAndUnsetParameters() throws Exception {
    addQuery("SELECT id FROM t6 WHERE a IS NOT DISTINCT FROM NULL", 6);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t6 WHERE a IS NOT DISTINCT FROM ?")) {
      ps.setNull(1, Types.INTEGER);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 6);
      }
    }
  }

  @Test
  public void testTypesThatAvaticaDoesNotAccept() throws Exception {
    final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    addQuery(
        "SELECT id FROM t7 WHERE a = DATE '2020-01-02' AND b = TIMESTAMP '2020-01-02 03:04:05'"
            + " AND c = TIMESTAMP WITH TIME ZONE '2020-01-02 03:04:05+02:00'"
            + " AND d = '123e4567-e89b-12d3-a456-426614174000'"
            + " AND e = 12345678901234567890"
            + " AND f = INTERVAL '0 01:30:00' DAY TO SECOND"
            + " AND g = ARRAY[1, 2]",
        7);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(
                connection,
                "SELECT id FROM t7 WHERE a = ? AND b = ? AND c = ? AND d = ? AND e = ? AND f = ?"
                    + " AND g = ?")) {
      ps.setObject(1, LocalDate.of(2020, 1, 2));
      ps.setObject(2, LocalDateTime.of(2020, 1, 2, 3, 4, 5));
      ps.setObject(3, OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)));
      ps.setObject(4, uuid);
      ps.setObject(5, new BigInteger("12345678901234567890"));
      ps.setObject(6, Duration.ofMinutes(90));
      ps.setObject(7, new int[] {1, 2});
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 7);
      }
    }
  }

  @Test
  public void testNegativeIntervalsCarryTheirSignInsideTheQuotes() throws Exception {
    addQuery(
        "SELECT id FROM tneg WHERE a = INTERVAL '-0 05:00:00' DAY TO SECOND"
            + " AND b = INTERVAL '-1-2' YEAR TO MONTH",
        91);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM tneg WHERE a = ? AND b = ?")) {
      ps.setObject(1, Duration.ofHours(-5));
      ps.setObject(2, Period.ofMonths(-14));
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 91);
      }
    }
  }

  @Test
  public void testStreamsAndLobs() throws Exception {
    addQuery("SELECT id FROM t8 WHERE a = 'text' AND b = X'0102' AND c = N'nat'", 8);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t8 WHERE a = ? AND b = ? AND c = ?")) {
      ps.setCharacterStream(1, new java.io.StringReader("text-ignored"), 4);
      ps.setBinaryStream(2, new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 2);
      ps.setNString(3, "nat");
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 8);
      }
    }
  }

  @Test
  public void testUnsupportedValueFailsWhenItIsSet() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t9 WHERE a = ?")) {
      assertThrows(SQLException.class, () -> ps.setObject(1, new java.util.HashMap<>()));
    }
  }

  @Test
  public void testValueTheDialectCannotRenderFailsWhenExecuting() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t10 WHERE a = ?")) {
      ps.setDouble(1, Double.NaN);
      assertThrows(SQLException.class, ps::executeQuery);
    }
  }

  @Test
  public void testPlainStatementIsSentAsWritten() throws Exception {
    addQuery("SELECT id FROM t11 WHERE a ? 'x'", 11);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final Statement statement = connection.createStatement();
        final ResultSet rs = statement.executeQuery("SELECT id FROM t11 WHERE a ? 'x'")) {
      assertSingleRow(rs, 11);
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testUpdateWithParameters() throws Exception {
    PRODUCER.addUpdateQuery("INSERT INTO t12 VALUES (1, 'a')", 1);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t12 VALUES (?, ?)")) {
      ps.setInt(1, 1);
      ps.setString(2, "a");
      assertEquals(1, ps.executeUpdate());
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testBatchHasOneUpdateCountPerRow() throws Exception {
    PRODUCER.addUpdateQuery("INSERT INTO t13 VALUES (1)", 1);
    PRODUCER.addUpdateQuery("INSERT INTO t13 VALUES (2)", 5);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t13 VALUES (?)")) {
      ps.setInt(1, 1);
      ps.addBatch();
      ps.setInt(1, 2);
      ps.addBatch();
      assertArrayEquals(new int[] {1, 5}, ps.executeBatch());
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testQueriesOnlyModeStillPreparesUpdatesOnTheServer() throws Exception {
    addQuery("SELECT id FROM t14 WHERE a = 1", 14);
    PRODUCER.addUpdateQuery("INSERT INTO t14 VALUES (1)", 1);
    try (final Connection connection =
        FLIGHT_SERVER_TEST_EXTENSION.getConnection(QUERIES_CLIENT_SIDE)) {
      try (final PreparedStatement ps = prepare(connection, "SELECT id FROM t14 WHERE a = ?")) {
        ps.setInt(1, 1);
        try (final ResultSet rs = ps.executeQuery()) {
          assertSingleRow(rs, 14);
        }
      }
      assertNotPreparedOnServer();

      try (final PreparedStatement ps = prepare(connection, "INSERT INTO t14 VALUES (1)")) {
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(1, PRODUCER.getActionTypeCounter().get(CREATE_PREPARED));
    }
  }

  @Test
  public void testResultSetMetadataIsProbedBeforeExecution() throws Exception {
    addQuery("SELECT id FROM t15 WHERE a = NULL", 15);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t15 WHERE a = ?")) {
      final ResultSetMetaData metaData = ps.getMetaData();
      assertEquals(1, metaData.getColumnCount());
      assertEquals("id", metaData.getColumnName(1));
      assertEquals(Types.INTEGER, metaData.getColumnType(1));
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testMetadataProbeCanBeDisabled() throws Exception {
    addQuery("SELECT id FROM t16 WHERE a = NULL", 16);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(
                Map.of("disableServerPreparedStatements", true, "clientSideMetadataProbe", false));
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t16 WHERE a = ?")) {
      assertEquals(0, ps.getMetaData().getColumnCount());
    }
  }

  @Test
  public void testFailedMetadataProbeIsNotAnError() throws Exception {
    // The server does not know the probe statement.
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM unknown WHERE a = ?")) {
      assertEquals(0, ps.getMetaData().getColumnCount());
    }
  }

  @Test
  public void testUnknownDialectIsRejectedWhenConnecting() {
    assertThrows(
        SQLException.class,
        () -> FLIGHT_SERVER_TEST_EXTENSION.getConnection(Map.of("dialect", "no-such-dialect")));
  }

  @Test
  public void testDialectClass() throws Exception {
    addQuery("SELECT id FROM t17 WHERE a = 1", 17);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(
                Map.of(
                    "disableServerPreparedStatements",
                    true,
                    "dialectClass",
                    "org.apache.arrow.driver.jdbc.dialect.AnsiSqlDialect"));
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t17 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 17);
      }
    }
  }

  @Test
  public void testDialectChosenByNameIsDiscovered() throws Exception {
    // The test dialect writes booleans as numbers, where ANSI writes TRUE.
    addQuery("SELECT id FROM t19 WHERE a = 1", 19);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(
                Map.of("disableServerPreparedStatements", true, "dialect", "example"));
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t19 WHERE a = ?")) {
      ps.setBoolean(1, true);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 19);
      }
    }
  }

  @Test
  public void testServerPreparedStatementsStayTheDefault() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(false)) {
      addQuery("SELECT id FROM t18", 18);
      try (final PreparedStatement ps = prepare(connection, "SELECT id FROM t18");
          final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 18);
      }
    }
    assertEquals(1, PRODUCER.getActionTypeCounter().get(CREATE_PREPARED));
  }

  /** Sets the only parameter of a statement. */
  private interface ParameterSetter {
    void set(PreparedStatement statement) throws Exception;
  }

  private static Arguments setter(
      final String name, final ParameterSetter setter, final String literal) {
    return Arguments.of(name, setter, literal);
  }

  private static Arguments setter(final String name, final ParameterSetter setter) {
    return Arguments.of(name, setter);
  }

  private static ByteArrayInputStream bytes(final int... values) {
    final byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return new ByteArrayInputStream(bytes);
  }

  private static ByteArrayInputStream ascii(final String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
  }

  private static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  public void testSetterRendersItsValue(
      final String name, final ParameterSetter setter, final String literal) throws Exception {
    final String sql = "SELECT id FROM setters WHERE a = " + literal;
    addQuery(sql, 20);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM setters WHERE a = ?")) {
      setter.set(ps);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 20);
      }
    }
    assertEquals(Collections.singletonList(sql), PRODUCER.getReceivedStatements());
    assertNotPreparedOnServer();
  }

  private static Stream<Arguments> testSetterRendersItsValue() {
    final Instant instant = Instant.parse("2020-01-02T03:04:05.123Z");
    return Stream.of(
        // Setters that Avatica handles.
        setter("setBoolean", ps -> ps.setBoolean(1, true), "TRUE"),
        setter("setByte", ps -> ps.setByte(1, (byte) 5), "5"),
        setter("setShort", ps -> ps.setShort(1, (short) -5), "(-5)"),
        setter("setInt", ps -> ps.setInt(1, 5), "5"),
        setter("setLong", ps -> ps.setLong(1, Long.MAX_VALUE), "9223372036854775807"),
        setter("setFloat", ps -> ps.setFloat(1, 0.1f), "0.1"),
        setter("setDouble", ps -> ps.setDouble(1, 1.5d), "1.5"),
        setter("setBigDecimal", ps -> ps.setBigDecimal(1, new BigDecimal("1.50")), "1.50"),
        setter("setString", ps -> ps.setString(1, "it's"), "'it''s'"),
        setter("setBytes", ps -> ps.setBytes(1, new byte[] {1, 2}), "X'0102'"),
        setter("setDate", ps -> ps.setDate(1, Date.valueOf("2020-01-02")), "DATE '2020-01-02'"),
        setter(
            "setDate with a calendar",
            ps -> ps.setDate(1, new Date(instant.toEpochMilli()), utc()),
            "DATE '2020-01-02'"),
        setter("setTime", ps -> ps.setTime(1, Time.valueOf("03:04:05")), "TIME '03:04:05'"),
        setter(
            "setTime with a calendar",
            ps -> ps.setTime(1, new Time(instant.toEpochMilli()), utc()),
            "TIME '03:04:05.123'"),
        setter(
            "setTimestamp",
            ps -> ps.setTimestamp(1, Timestamp.valueOf("2020-01-02 03:04:05.123")),
            "TIMESTAMP '2020-01-02 03:04:05.123'"),
        setter(
            "setTimestamp with a calendar",
            ps -> ps.setTimestamp(1, new Timestamp(instant.toEpochMilli()), utc()),
            "TIMESTAMP '2020-01-02 03:04:05.123'"),
        setter("setObject with a String", ps -> ps.setObject(1, "text"), "'text'"),
        setter("setObject with a Character", ps -> ps.setObject(1, 'c'), "'c'"),
        setter("setObject with a target type", ps -> ps.setObject(1, 5, Types.INTEGER), "5"),
        // Setters that only a client-side statement handles.
        setter(
            "setObject with a LocalTime",
            ps -> ps.setObject(1, LocalTime.of(3, 4, 5)),
            "TIME '03:04:05'"),
        setter(
            "setObject with a LocalDate and a target type",
            ps -> ps.setObject(1, LocalDate.of(2020, 1, 2), Types.DATE),
            "DATE '2020-01-02'"),
        setter(
            "setObject with a BigInteger, a target type and a scale",
            ps -> ps.setObject(1, BigInteger.TEN, Types.NUMERIC, 0),
            "10"),
        setter("setObject with a List", ps -> ps.setObject(1, Arrays.asList(1, 2)), "ARRAY[1, 2]"),
        setter(
            "setArray",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("INTEGER", new Integer[] {1, 2})),
            "ARRAY[1, 2]"),
        setter("setNString", ps -> ps.setNString(1, "nat"), "N'nat'"),
        setter(
            "setURL",
            ps -> ps.setURL(1, new URL("https://arrow.apache.org/docs")),
            "'https://arrow.apache.org/docs'"),
        setter(
            "setSQLXML",
            ps -> {
              final SQLXML xml = mock(SQLXML.class);
              when(xml.getString()).thenReturn("<a/>");
              ps.setSQLXML(1, xml);
            },
            "'<a/>'"),
        setter("setClob", ps -> ps.setClob(1, new SerialClob("text".toCharArray())), "'text'"),
        setter("setClob with a reader", ps -> ps.setClob(1, new StringReader("text")), "'text'"),
        setter(
            "setClob with a reader and a length",
            ps -> ps.setClob(1, new StringReader("text-ignored"), 4L),
            "'text'"),
        setter(
            "setNClob",
            ps -> {
              final NClob clob = mock(NClob.class);
              when(clob.length()).thenReturn(4L);
              when(clob.getSubString(1, 4)).thenReturn("text");
              ps.setNClob(1, clob);
            },
            "'text'"),
        setter("setNClob with a reader", ps -> ps.setNClob(1, new StringReader("text")), "'text'"),
        setter(
            "setNClob with a reader and a length",
            ps -> ps.setNClob(1, new StringReader("text-ignored"), 4L),
            "'text'"),
        setter("setBlob", ps -> ps.setBlob(1, new SerialBlob(new byte[] {1, 2})), "X'0102'"),
        setter("setBlob with a stream", ps -> ps.setBlob(1, bytes(1, 2)), "X'0102'"),
        setter(
            "setBlob with a stream and a length",
            ps -> ps.setBlob(1, bytes(1, 2, 3), 2L),
            "X'0102'"),
        setter(
            "setCharacterStream",
            ps -> ps.setCharacterStream(1, new StringReader("text")),
            "'text'"),
        setter(
            "setCharacterStream with an int length",
            ps -> ps.setCharacterStream(1, new StringReader("text-ignored"), 4),
            "'text'"),
        setter(
            "setCharacterStream with a long length",
            ps -> ps.setCharacterStream(1, new StringReader("text-ignored"), 4L),
            "'text'"),
        setter(
            "setCharacterStream with a length past the end",
            ps -> ps.setCharacterStream(1, new StringReader("text"), 100L),
            "'text'"),
        setter(
            "setNCharacterStream",
            ps -> ps.setNCharacterStream(1, new StringReader("text")),
            "'text'"),
        setter(
            "setNCharacterStream with a length",
            ps -> ps.setNCharacterStream(1, new StringReader("text-ignored"), 4L),
            "'text'"),
        setter("setBinaryStream", ps -> ps.setBinaryStream(1, bytes(1, 2)), "X'0102'"),
        setter(
            "setBinaryStream with an int length",
            ps -> ps.setBinaryStream(1, bytes(1, 2, 3), 2),
            "X'0102'"),
        setter(
            "setBinaryStream with a long length",
            ps -> ps.setBinaryStream(1, bytes(1, 2, 3), 2L),
            "X'0102'"),
        setter("setAsciiStream", ps -> ps.setAsciiStream(1, ascii("text")), "'text'"),
        setter(
            "setAsciiStream with an int length",
            ps -> ps.setAsciiStream(1, ascii("text-ignored"), 4),
            "'text'"),
        setter(
            "setAsciiStream with a long length",
            ps -> ps.setAsciiStream(1, ascii("text-ignored"), 4L),
            "'text'"),
        // A null argument is a SQL null, whatever the setter.
        setter("setNull", ps -> ps.setNull(1, Types.VARCHAR), "NULL"),
        setter("setNull with a type name", ps -> ps.setNull(1, Types.STRUCT, "point"), "NULL"),
        setter("setObject with null", ps -> ps.setObject(1, null), "NULL"),
        setter(
            "setObject with null and a target type",
            ps -> ps.setObject(1, null, Types.INTEGER),
            "NULL"),
        setter("setString with null", ps -> ps.setString(1, null), "NULL"),
        setter("setBigDecimal with null", ps -> ps.setBigDecimal(1, null), "NULL"),
        setter("setBytes with null", ps -> ps.setBytes(1, null), "NULL"),
        setter("setDate with null", ps -> ps.setDate(1, null), "NULL"),
        setter("setTimestamp with null", ps -> ps.setTimestamp(1, null), "NULL"),
        setter("setArray with null", ps -> ps.setArray(1, null), "NULL"),
        setter("setNString with null", ps -> ps.setNString(1, null), "NULL"),
        setter("setURL with null", ps -> ps.setURL(1, null), "NULL"),
        setter("setSQLXML with null", ps -> ps.setSQLXML(1, null), "NULL"),
        setter("setClob with null", ps -> ps.setClob(1, (Clob) null), "NULL"),
        setter("setNClob with null", ps -> ps.setNClob(1, (NClob) null), "NULL"),
        setter("setBlob with null", ps -> ps.setBlob(1, (Blob) null), "NULL"),
        setter("setCharacterStream with null", ps -> ps.setCharacterStream(1, null), "NULL"),
        setter("setBinaryStream with null", ps -> ps.setBinaryStream(1, null), "NULL"),
        setter("setAsciiStream with null", ps -> ps.setAsciiStream(1, null), "NULL"));
  }

  // Dates and times keep their precision and use the zone of the JVM, or of the calendar.

  private static final Instant NANO_INSTANT = Instant.parse("2020-01-02T03:04:05.123456789Z");

  private static Calendar calendar(final String zone) {
    return Calendar.getInstance(TimeZone.getTimeZone(zone));
  }

  private static Struct struct(final Object... attributes) throws SQLException {
    final Struct struct = mock(Struct.class);
    when(struct.getSQLTypeName()).thenReturn("point");
    when(struct.getAttributes()).thenReturn(attributes);
    return struct;
  }

  private void assertRendersWithJvmZone(
      final String jvmZone,
      final Map<String, Object> properties,
      final ParameterSetter setter,
      final String literal)
      throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
    PRODUCER.clearReceivedStatements();
    final String sql = "SELECT id FROM date_times WHERE a = " + literal;
    addQuery(sql, 30);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(properties);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM date_times WHERE a = ?")) {
      setter.set(ps);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 30);
      }
    }
    assertEquals(Collections.singletonList(sql), PRODUCER.getReceivedStatements());
    assertNotPreparedOnServer();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  public void testDateTimeSetterRendersItsValue(
      final String name, final ParameterSetter setter, final String literal) throws Exception {
    assertRendersWithJvmZone("Europe/Madrid", ALL_CLIENT_SIDE, setter, literal);
  }

  private static Stream<Arguments> testDateTimeSetterRendersItsValue() {
    final Timestamp nanos = Timestamp.from(NANO_INSTANT);
    return Stream.of(
        setter(
            "setTimestamp keeps the nanoseconds",
            ps -> ps.setTimestamp(1, Timestamp.valueOf("2020-01-02 03:04:05.123456789")),
            "TIMESTAMP '2020-01-02 03:04:05.123456789'"),
        setter(
            "setObject with a Timestamp keeps the nanoseconds",
            ps -> ps.setObject(1, Timestamp.valueOf("2020-01-02 03:04:05.123456789")),
            "TIMESTAMP '2020-01-02 03:04:05.123456789'"),
        setter(
            "setTimestamp without a calendar uses the zone of the JVM",
            ps -> ps.setTimestamp(1, nanos),
            "TIMESTAMP '2020-01-02 04:04:05.123456789'"),
        setter(
            "setTimestamp with a calendar of another zone",
            ps -> ps.setTimestamp(1, nanos, calendar("Asia/Kolkata")),
            "TIMESTAMP '2020-01-02 08:34:05.123456789'"),
        setter(
            "setTimestamp with a UTC calendar",
            ps -> ps.setTimestamp(1, nanos, calendar("UTC")),
            "TIMESTAMP '2020-01-02 03:04:05.123456789'"),
        setter(
            "setTimestamp with a null calendar",
            ps -> ps.setTimestamp(1, nanos, null),
            "TIMESTAMP '2020-01-02 04:04:05.123456789'"),
        setter(
            "setTime with a calendar of another zone",
            ps -> ps.setTime(1, new Time(NANO_INSTANT.toEpochMilli()), calendar("Asia/Kolkata")),
            "TIME '08:34:05.123'"),
        setter(
            "setDate with a calendar of another zone",
            ps ->
                ps.setDate(
                    1,
                    new Date(Instant.parse("2020-01-02T20:00:00Z").toEpochMilli()),
                    calendar("Asia/Kolkata")),
            "DATE '2020-01-03'"),
        setter(
            "setDate before the Gregorian calendar",
            ps -> ps.setDate(1, Date.valueOf("1500-01-01")),
            "DATE '1500-01-01'"),
        setter(
            "setObject with a LocalDateTime and DATE",
            ps -> ps.setObject(1, LocalDateTime.of(2020, 1, 2, 3, 4, 5), Types.DATE),
            "DATE '2020-01-02'"),
        setter(
            "setObject with a LocalDateTime and TIME",
            ps -> ps.setObject(1, LocalDateTime.of(2020, 1, 2, 3, 4, 5), Types.TIME),
            "TIME '03:04:05'"),
        setter(
            "setObject with a String and DATE",
            ps -> ps.setObject(1, "2020-01-01", Types.DATE),
            "DATE '2020-01-01'"),
        setter(
            "setObject with a String and TIMESTAMP",
            ps -> ps.setObject(1, "2020-01-02 03:04:05.123456789", Types.TIMESTAMP),
            "TIMESTAMP '2020-01-02 03:04:05.123456789'"),
        setter(
            "setObject with a String, TIMESTAMP_WITH_TIMEZONE and a scale",
            ps -> ps.setObject(1, "2020-01-02T03:04:05+02:00", Types.TIMESTAMP_WITH_TIMEZONE, 0),
            "TIMESTAMP WITH TIME ZONE '2020-01-02 03:04:05+02:00'"),
        setter(
            "setObject with an OffsetDateTime and TIME_WITH_TIMEZONE",
            ps ->
                ps.setObject(
                    1,
                    OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)),
                    Types.TIME_WITH_TIMEZONE),
            "TIME WITH TIME ZONE '03:04:05+02:00'"),
        setter(
            "setObject with a LocalDate and VARCHAR",
            ps -> ps.setObject(1, LocalDate.of(2020, 1, 2), Types.VARCHAR),
            "'2020-01-02'"),
        setter(
            "setObject with a Timestamp and TIMESTAMP",
            ps -> ps.setObject(1, nanos, Types.TIMESTAMP),
            "TIMESTAMP '2020-01-02 04:04:05.123456789'"),
        setter(
            "setObject with a Calendar",
            ps -> {
              final Calendar calendar = calendar("Asia/Kolkata");
              calendar.setTimeInMillis(NANO_INSTANT.toEpochMilli());
              ps.setObject(1, calendar);
            },
            "TIMESTAMP '2020-01-02 08:34:05.123'"),
        // The same conversion as a lone parameter, inside an array and inside a row.
        setter(
            "setObject with an array of dates",
            ps ->
                ps.setObject(
                    1, new Date[] {Date.valueOf("2020-01-02"), Date.valueOf("2020-01-03")}),
            "ARRAY[DATE '2020-01-02', DATE '2020-01-03']"),
        setter(
            "setObject with a list of timestamps",
            ps -> ps.setObject(1, Arrays.asList(nanos, nanos)),
            "ARRAY[TIMESTAMP '2020-01-02 04:04:05.123456789', "
                + "TIMESTAMP '2020-01-02 04:04:05.123456789']"),
        setter(
            "setArray of times",
            ps ->
                ps.setArray(
                    1,
                    ps.getConnection()
                        .createArrayOf("TIME", new Time[] {Time.valueOf("03:04:05")})),
            "ARRAY[TIME '03:04:05']"),
        setter(
            "setObject with a struct of a timestamp and a date",
            ps -> ps.setObject(1, struct(nanos, Date.valueOf("2020-01-02"))),
            "ROW(TIMESTAMP '2020-01-02 04:04:05.123456789', DATE '2020-01-02')"));
  }

  @Test
  public void testDefaultZoneOfTheJvmWinsOverTheTimeZoneProperty() throws Exception {
    final Timestamp timestamp = Timestamp.from(NANO_INSTANT);
    final String literal = "TIMESTAMP '2020-01-02 04:04:05.123456789'";
    for (final String property : new String[] {"UTC", "Asia/Tokyo", "America/Sao_Paulo"}) {
      final Map<String, Object> properties = new java.util.HashMap<>(ALL_CLIENT_SIDE);
      properties.put("timeZone", property);
      try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(properties)) {
        // The property is in effect, so the result below does not come from ignoring it.
        assertEquals(property, connection.unwrap(ArrowFlightConnection.class).config().timeZone());
      }
      assertRendersWithJvmZone(
          "Europe/Madrid", properties, ps -> ps.setTimestamp(1, timestamp), literal);
      assertRendersWithJvmZone(
          "Europe/Madrid", properties, ps -> ps.setObject(1, timestamp), literal);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  public void testConversionNotInTheTableIsRejectedByTheSetter(
      final String name, final ParameterSetter setter) throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM date_times WHERE a = ?")) {
      assertThrows(SQLFeatureNotSupportedException.class, () -> setter.set(ps));
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
  }

  private static Stream<Arguments> testConversionNotInTheTableIsRejectedByTheSetter() {
    return Stream.of(
        setter("a LocalDate as TIME", ps -> ps.setObject(1, LocalDate.of(2020, 1, 2), Types.TIME)),
        setter("an Integer as DATE", ps -> ps.setObject(1, 5, Types.DATE)),
        setter(
            "a LocalDateTime as TIMESTAMP_WITH_TIMEZONE",
            ps ->
                ps.setObject(
                    1, LocalDateTime.of(2020, 1, 2, 3, 4), Types.TIMESTAMP_WITH_TIMEZONE)));
  }

  @Test
  public void testUnsetParameterIsNull() throws Exception {
    addQuery("SELECT id FROM t20 WHERE a = 1 AND b IS NOT DISTINCT FROM NULL", 20);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t20 WHERE a = ? AND b IS NOT DISTINCT FROM ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 20);
      }
    }
  }

  @Test
  public void testClearedParametersAreNull() throws Exception {
    addQuery("SELECT id FROM t21 WHERE a = 1", 1);
    addQuery("SELECT id FROM t21 WHERE a = NULL", 21);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t21 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 1);
      }
      ps.clearParameters();
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 21);
      }
    }
  }

  @Test
  public void testParameterIndexOutOfRange() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t22 WHERE a = ?")) {
      assertThrows(SQLException.class, () -> ps.setInt(0, 1));
      assertThrows(SQLException.class, () -> ps.setInt(2, 1));
      // The setters only a client-side statement handles check the index too.
      assertThrows(SQLException.class, () -> ps.setObject(0, LocalDate.of(2020, 1, 2)));
      assertThrows(SQLException.class, () -> ps.setObject(2, LocalDate.of(2020, 1, 2)));
      assertThrows(SQLException.class, () -> ps.setNString(2, "nat"));
      assertThrows(SQLException.class, () -> ps.setBinaryStream(2, bytes(1, 2), 2L));
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
  }

  @Test
  public void testParameterOfAClosedStatement() throws Exception {
    try (final Connection connection =
        FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE)) {
      final PreparedStatement ps = prepare(connection, "SELECT id FROM t23 WHERE a = ?");
      ps.close();
      assertThrows(SQLException.class, () -> ps.setInt(1, 1));
      assertThrows(SQLException.class, () -> ps.setObject(1, LocalDate.of(2020, 1, 2)));
      assertThrows(SQLException.class, () -> ps.setNString(1, "nat"));
    }
  }

  @Test
  public void testStreamLargerThanTheLimitIsRejected() throws Exception {
    final long tooLarge = SqlValueFactory.MAX_LOB_LENGTH + 1L;
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t24 WHERE a = ?")) {
      assertThrows(
          SQLException.class, () -> ps.setCharacterStream(1, new StringReader("text"), tooLarge));
      assertThrows(SQLException.class, () -> ps.setClob(1, new StringReader("text"), tooLarge));
      assertThrows(SQLException.class, () -> ps.setBinaryStream(1, bytes(1, 2), tooLarge));
      assertThrows(SQLException.class, () -> ps.setBlob(1, bytes(1, 2), tooLarge));
      assertThrows(SQLException.class, () -> ps.setAsciiStream(1, ascii("text"), tooLarge));
    }
  }

  @Test
  public void testNegativeStreamLengthIsRejected() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t25 WHERE a = ?")) {
      assertThrows(
          SQLException.class, () -> ps.setCharacterStream(1, new StringReader("text"), -1));
      assertThrows(
          SQLException.class, () -> ps.setCharacterStream(1, new StringReader("text"), -1L));
      assertThrows(
          SQLException.class, () -> ps.setNCharacterStream(1, new StringReader("text"), -1L));
      assertThrows(SQLException.class, () -> ps.setClob(1, new StringReader("text"), -1L));
      assertThrows(SQLException.class, () -> ps.setNClob(1, new StringReader("text"), -1L));
      assertThrows(SQLException.class, () -> ps.setBinaryStream(1, bytes(1, 2), -1));
      assertThrows(SQLException.class, () -> ps.setBinaryStream(1, bytes(1, 2), -1L));
      assertThrows(SQLException.class, () -> ps.setBlob(1, bytes(1, 2), -1L));
      assertThrows(SQLException.class, () -> ps.setAsciiStream(1, ascii("text"), -1));
      assertThrows(SQLException.class, () -> ps.setAsciiStream(1, ascii("text"), -1L));
    }
  }

  @Test
  public void testParameterMetadata() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps =
            prepare(connection, "SELECT id FROM t26 WHERE a = ? AND b = ?")) {
      final ParameterMetaData metaData = ps.getParameterMetaData();
      assertEquals(2, metaData.getParameterCount());
      // There is no server to tell the types.
      assertEquals(Types.OTHER, metaData.getParameterType(1));
      assertEquals(Types.OTHER, metaData.getParameterType(2));
      assertEquals("OTHER", metaData.getParameterTypeName(1));
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
    assertNotPreparedOnServer();
  }

  @Test
  public void testParameterMetadataOfAStatementWithoutParameters() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t27 WHERE a = '?'")) {
      assertEquals(0, ps.getParameterMetaData().getParameterCount());
    }
  }

  @Test
  public void testMetadataProbeIsSentOnlyOnce() throws Exception {
    addQuery("SELECT id FROM t28 WHERE a = NULL", 28);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t28 WHERE a = ?")) {
      // Preparing the statement sends nothing.
      assertTrue(PRODUCER.getReceivedStatements().isEmpty());
      assertEquals(1, ps.getMetaData().getColumnCount());
      assertEquals(1, ps.getMetaData().getColumnCount());
    }
    assertEquals(
        Collections.singletonList("SELECT id FROM t28 WHERE a = NULL"),
        PRODUCER.getReceivedStatements());
  }

  @Test
  public void testFailedMetadataProbeIsNotRepeated() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM unknown2 WHERE a = ?")) {
      assertEquals(0, ps.getMetaData().getColumnCount());
      assertEquals(0, ps.getMetaData().getColumnCount());
    }
    assertEquals(
        Collections.singletonList("SELECT id FROM unknown2 WHERE a = NULL"),
        PRODUCER.getReceivedStatements());
  }

  @Test
  public void testMetadataOfAnUpdateIsNotProbed() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t29 VALUES (?)")) {
      assertEquals(0, ps.getMetaData().getColumnCount());
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
  }

  @Test
  public void testMetadataAfterExecutionNeedsNoProbe() throws Exception {
    addQuery("SELECT id FROM t30 WHERE a = 1", 30);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t30 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        final ResultSetMetaData metaData = ps.getMetaData();
        assertEquals(1, metaData.getColumnCount());
        assertEquals("id", metaData.getColumnName(1));
        assertSingleRow(rs, 30);
      }
    }
    assertEquals(
        Collections.singletonList("SELECT id FROM t30 WHERE a = 1"),
        PRODUCER.getReceivedStatements());
  }

  @Test
  public void testResultSetMetadataDoesNotDependOnTheProbe() throws Exception {
    addQuery("SELECT id FROM t31 WHERE a = 1", 31);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(
                Map.of("disableServerPreparedStatements", true, "clientSideMetadataProbe", false));
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t31 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        final ResultSetMetaData metaData = rs.getMetaData();
        assertEquals(1, metaData.getColumnCount());
        assertEquals("id", metaData.getColumnName(1));
        assertEquals(Types.INTEGER, metaData.getColumnType(1));
        assertSingleRow(rs, 31);
      }
    }
  }

  @Test
  public void testExecuteReportsAnUpdateCount() throws Exception {
    PRODUCER.addUpdateQuery("UPDATE t32 SET a = 1 WHERE b = 'x'", 7);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "UPDATE t32 SET a = ? WHERE b = ?")) {
      ps.setInt(1, 1);
      ps.setString(2, "x");
      assertFalse(ps.execute());
      assertEquals(7, ps.getUpdateCount());
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testPlainStatementUpdate() throws Exception {
    PRODUCER.addUpdateQuery("UPDATE t33 SET a = 1 WHERE b ? 'x'", 3);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final Statement statement = connection.createStatement()) {
      assertEquals(3, statement.executeUpdate("UPDATE t33 SET a = 1 WHERE b ? 'x'"));
    }
    assertEquals(
        Collections.singletonList("UPDATE t33 SET a = 1 WHERE b ? 'x'"),
        PRODUCER.getReceivedStatements());
    assertNotPreparedOnServer();
  }

  @Test
  public void testPlainStatementExecuteReportsAnUpdateCount() throws Exception {
    PRODUCER.addUpdateQuery("CREATE TABLE t34 (a INTEGER)", 0);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final Statement statement = connection.createStatement()) {
      assertFalse(statement.execute("CREATE TABLE t34 (a INTEGER)"));
      assertEquals(0, statement.getUpdateCount());
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testQueriesOnlyModeAppliesToPlainStatements() throws Exception {
    addQuery("SELECT id FROM t35", 35);
    PRODUCER.addUpdateQuery("UPDATE t35 SET a = 1", 2);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(QUERIES_CLIENT_SIDE);
        final Statement statement = connection.createStatement()) {
      try (final ResultSet rs = statement.executeQuery("SELECT id FROM t35")) {
        assertSingleRow(rs, 35);
      }
      assertNotPreparedOnServer();

      assertEquals(2, statement.executeUpdate("UPDATE t35 SET a = 1"));
      assertEquals(1, PRODUCER.getActionTypeCounter().get(CREATE_PREPARED));
    }
    // The update went through its prepared statement.
    assertEquals(Collections.singletonList("SELECT id FROM t35"), PRODUCER.getReceivedStatements());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "select id from t36 where a = ?",
        "  \n SELECT id FROM t36 WHERE a = ?",
        "-- note\nSELECT id FROM t36 WHERE a = ?",
        "/* note */ SELECT id FROM t36 WHERE a = ?",
        "(SELECT id FROM t36 WHERE a = ?)",
        "WITH x AS (SELECT id FROM t36) SELECT id FROM x WHERE a = ?",
        "VALUES (?)"
      })
  public void testStatementsTakenAsQueries(final String sql) throws Exception {
    addQuery(sql.replace("?", "1"), 36);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(QUERIES_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, sql)) {
      ps.setInt(1, 1);
      assertTrue(ps.execute());
      assertSingleRow(ps.getResultSet(), 36);
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testEmptyBatch() throws Exception {
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t37 VALUES (?)")) {
      assertArrayEquals(new int[0], ps.executeBatch());
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
  }

  @Test
  public void testBatchOfAStatementWithoutParameters() throws Exception {
    PRODUCER.addUpdateQuery("INSERT INTO t38 VALUES (1)", 1);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t38 VALUES (1)")) {
      ps.addBatch();
      ps.addBatch();
      assertArrayEquals(new int[] {1, 1}, ps.executeBatch());
    }
    assertEquals(
        Arrays.asList("INSERT INTO t38 VALUES (1)", "INSERT INTO t38 VALUES (1)"),
        PRODUCER.getReceivedStatements());
  }

  @Test
  public void testLargeBatch() throws Exception {
    PRODUCER.addUpdateQuery("INSERT INTO t39 VALUES (1)", 1);
    PRODUCER.addUpdateQuery("INSERT INTO t39 VALUES (2)", 3_000_000_000L);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t39 VALUES (?)")) {
      ps.setInt(1, 1);
      ps.addBatch();
      ps.setInt(1, 2);
      ps.addBatch();
      assertArrayEquals(new long[] {1, 3_000_000_000L}, ps.executeLargeBatch());
    }
  }

  @Test
  public void testBatchStopsAtTheFirstFailure() throws Exception {
    PRODUCER.addUpdateQuery("INSERT INTO t40 VALUES (1)", 1);
    PRODUCER.addUpdateQuery("INSERT INTO t40 VALUES (3)", 1);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(ALL_CLIENT_SIDE);
        final PreparedStatement ps = prepare(connection, "INSERT INTO t40 VALUES (?)")) {
      for (int i = 1; i <= 3; i++) {
        ps.setInt(1, i);
        ps.addBatch();
      }
      // The server does not know the second statement.
      assertThrows(SQLException.class, ps::executeBatch);
    }
    assertEquals(
        Arrays.asList("INSERT INTO t40 VALUES (1)", "INSERT INTO t40 VALUES (2)"),
        PRODUCER.getReceivedStatements());
  }

  @Test
  public void testPropertiesInTheUrl() throws Exception {
    addQuery("SELECT id FROM t41 WHERE a = 1", 41);
    final String url =
        "jdbc:arrow-flight-sql://"
            + FLIGHT_SERVER_TEST_EXTENSION.getHost()
            + ":"
            + FLIGHT_SERVER_TEST_EXTENSION.getPort()
            + "?useEncryption=false&disableServerPreparedStatements=true&dialect=ANSI";
    try (final Connection connection =
            DriverManager.getConnection(
                url,
                FlightServerTestExtension.DEFAULT_USER,
                FlightServerTestExtension.DEFAULT_PASSWORD);
        final PreparedStatement ps = prepare(connection, "SELECT id FROM t41 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 41);
      }
    }
    assertNotPreparedOnServer();
  }

  @Test
  public void testDialectClassThatCannotBeLoadedIsRejectedWhenConnecting() {
    assertThrows(
        SQLException.class,
        () ->
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(
                Map.of("dialectClass", "org.apache.arrow.driver.jdbc.dialect.NoSuchDialect")));
    // A class that is not a dialect.
    assertThrows(
        SQLException.class,
        () ->
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(Map.of("dialectClass", "java.lang.String")));
  }
}
