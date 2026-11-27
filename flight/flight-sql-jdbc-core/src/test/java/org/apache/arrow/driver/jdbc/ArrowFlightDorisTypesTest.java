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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Writes every kind of parameter value through a {@code PreparedStatement} of a connection with
 * {@code disableServerPreparedStatements=true} and the {@code doris} dialect, and reads it back
 * from a real Apache Doris, in an insert, in a condition, in an update and in a delete.
 *
 * <p>The test needs a Doris server, so it only runs when the {@code doris.flight.url} system
 * property holds the JDBC URL, for example {@code jdbc:arrow-flight-sql://localhost:8070}, and
 * {@code doris.flight.user} and {@code doris.flight.password} hold the credentials (an empty
 * password by default).
 */
public class ArrowFlightDorisTypesTest {
  private static final String URL = System.getProperty("doris.flight.url");
  private static final String USER = System.getProperty("doris.flight.user", "root");
  private static final String PASSWORD = System.getProperty("doris.flight.password", "");
  private static final String TABLE = "vdptest.types_test";

  private static final List<String> JVM_ZONES =
      List.of(
          "UTC",
          "Europe/Madrid",
          "America/New_York",
          "Asia/Kolkata",
          "Pacific/Chatham",
          "Australia/Lord_Howe");

  private TimeZone defaultZone;
  private static ZoneId sessionZone;
  private static int nextId = 1;

  @FunctionalInterface
  interface Setter {
    void set(PreparedStatement ps, int index) throws Exception;
  }

  private record Case(
      String name,
      String column,
      Setter setter,
      Function<ZoneId, String> expected,
      boolean comparable) {}

  private static Case expect(String name, String column, Setter setter, String expected) {
    return new Case(name, column, setter, z -> expected, true);
  }

  private static Case noWhere(String name, String column, Setter setter, String expected) {
    return new Case(name, column, setter, z -> expected, false);
  }

  private static Case zoned(
      String name, String column, Setter setter, Function<ZoneId, String> expected) {
    return new Case(name, column, setter, expected, true);
  }

  @BeforeAll
  public static void connectToDoris() throws Exception {
    assumeTrue(URL != null, "Set -Ddoris.flight.url to run against Apache Doris");
    try (Connection connection = open();
        Statement st = connection.createStatement()) {
      st.execute("DROP TABLE IF EXISTS " + TABLE);
      st.execute(
          "CREATE TABLE "
              + TABLE
              + " (id INT NOT NULL, b BOOLEAN, t1 TINYINT, s2 SMALLINT, i4 INT, i8 BIGINT,"
              + " i16 LARGEINT, f4 FLOAT, f8 DOUBLE, d9 DECIMAL(27,9), d2 DECIMAL(10,2),"
              + " c10 CHAR(10), v100 VARCHAR(100), str STRING, dt DATE, dtm DATETIME(6),"
              + " dt0 DATETIME(0), js JSON, ai ARRAY<INT>, astr ARRAY<STRING>, ip4 IPV4, ip6 IPV6)"
              + " UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
      try (ResultSet rs = st.executeQuery("SELECT @@time_zone")) {
        rs.next();
        sessionZone = ZoneId.of(rs.getString(1), ZoneId.SHORT_IDS);
      }
    }
  }

  private static Connection open() throws SQLException {
    return DriverManager.getConnection(
        URL
            + (URL.contains("?") ? "&" : "?")
            + "useEncryption=0&disableServerPreparedStatements=true&dialect=doris",
        USER,
        PASSWORD);
  }

  @BeforeEach
  public void rememberZone() {
    defaultZone = TimeZone.getDefault();
  }

  @AfterEach
  public void restoreZone() {
    TimeZone.setDefault(defaultZone);
  }

  private static String text(Connection connection, String column, int id) throws SQLException {
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT CAST(" + column + " AS STRING) FROM " + TABLE + " WHERE id = " + id)) {
      return rs.next()
          ? String.valueOf(rs.getString(1) == null ? "<null>" : rs.getString(1))
          : "<no row>";
    }
  }

  private static long count(Connection connection, String where, Setter setter) throws Exception {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE + " WHERE " + where)) {
      setter.set(ps, 1);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void run(Case test, String zone) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(zone));
    final int id = nextId++;
    final String expected = test.expected().apply(sessionZone);
    final String other = test.column().equals("s2") ? "t1" : "s2";
    try (Connection connection = open()) {
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO " + TABLE + " (id, " + test.column() + ") VALUES (?, ?)")) {
        ps.setInt(1, id);
        test.setter().set(ps, 2);
        ps.executeUpdate();
      }
      assertEquals(expected, text(connection, test.column(), id), "inserted value");
      if (test.comparable()) {
        assertEquals(
            1L,
            count(connection, test.column() + " = ? AND id = " + id, test.setter()),
            "the value found in a condition");
        try (PreparedStatement ps =
            connection.prepareStatement(
                "UPDATE " + TABLE + " SET " + other + " = ? WHERE " + test.column() + " = ?")) {
          ps.setInt(1, 42);
          test.setter().set(ps, 2);
          ps.executeUpdate();
        }
        assertEquals("42", text(connection, other, id), "update by the value");
        try (PreparedStatement ps =
            connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE " + test.column() + " = ?")) {
          test.setter().set(ps, 1);
          ps.executeUpdate();
        }
        assertEquals("<no row>", text(connection, "id", id), "delete by the value");
      }
    }
  }

  private static String ts(LocalDateTime t, int digits) {
    final long scale = (long) Math.pow(10, 9 - digits);
    final long nanos = t.getNano();
    final long rounded = (nanos + scale / 2) / scale * scale;
    LocalDateTime r = t.withNano(0).plusNanos(rounded);
    final StringBuilder sb =
        new StringBuilder(r.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    if (digits > 0) {
      sb.append('.').append(String.format("%09d", r.getNano()), 0, digits);
    }
    return sb.toString();
  }

  private static List<Case> cases() {
    final List<Case> cases = new ArrayList<>();
    // Booleans and integers
    cases.add(expect("boolean true", "b", (ps, i) -> ps.setBoolean(i, true), "1"));
    cases.add(expect("boolean false object", "b", (ps, i) -> ps.setObject(i, Boolean.FALSE), "0"));
    cases.add(expect("tinyint", "t1", (ps, i) -> ps.setByte(i, (byte) -128), "-128"));
    cases.add(expect("tinyint object", "t1", (ps, i) -> ps.setObject(i, (byte) 127), "127"));
    cases.add(expect("smallint", "s2", (ps, i) -> ps.setShort(i, (short) -32768), "-32768"));
    cases.add(expect("int min", "i4", (ps, i) -> ps.setInt(i, Integer.MIN_VALUE), "-2147483648"));
    cases.add(expect("int max", "i4", (ps, i) -> ps.setInt(i, Integer.MAX_VALUE), "2147483647"));
    cases.add(
        expect(
            "bigint min", "i8", (ps, i) -> ps.setLong(i, Long.MIN_VALUE), "-9223372036854775808"));
    cases.add(
        expect(
            "bigint max", "i8", (ps, i) -> ps.setObject(i, Long.MAX_VALUE), "9223372036854775807"));
    cases.add(
        expect(
            "largeint max",
            "i16",
            (ps, i) -> ps.setObject(i, new BigInteger("170141183460469231731687303715884105727")),
            "170141183460469231731687303715884105727"));
    cases.add(
        expect(
            "largeint min",
            "i16",
            (ps, i) -> ps.setObject(i, new BigInteger("-170141183460469231731687303715884105728")),
            "-170141183460469231731687303715884105728"));
    cases.add(expect("largeint from long", "i16", (ps, i) -> ps.setLong(i, -5), "-5"));
    // Floating point
    cases.add(noWhere("float", "f4", (ps, i) -> ps.setFloat(i, 0.1f), "0.1"));
    cases.add(noWhere("float negative", "f4", (ps, i) -> ps.setFloat(i, -1.5e-7f), "-1.5e-07"));
    cases.add(expect("double", "f8", (ps, i) -> ps.setDouble(i, 0.1), "0.1"));
    cases.add(expect("double large", "f8", (ps, i) -> ps.setDouble(i, 1.0E300), "1e+300"));
    cases.add(expect("double negative", "f8", (ps, i) -> ps.setDouble(i, -2.5E-10), "-2.5e-10"));
    cases.add(noWhere("double nan", "f8", (ps, i) -> ps.setDouble(i, Double.NaN), "NaN"));
    cases.add(
        noWhere(
            "double infinity",
            "f8",
            (ps, i) -> ps.setDouble(i, Double.POSITIVE_INFINITY),
            "Infinity"));
    cases.add(
        noWhere(
            "double -infinity",
            "f8",
            (ps, i) -> ps.setObject(i, Double.NEGATIVE_INFINITY),
            "-Infinity"));
    // Decimals
    cases.add(
        expect(
            "decimal",
            "d9",
            (ps, i) -> ps.setBigDecimal(i, new BigDecimal("12345678901234567.123456789")),
            "12345678901234567.123456789"));
    cases.add(
        expect(
            "decimal negative",
            "d2",
            (ps, i) -> ps.setBigDecimal(i, new BigDecimal("-0.05")),
            "-0.05"));
    cases.add(
        expect(
            "decimal from object",
            "d2",
            (ps, i) -> ps.setObject(i, new BigDecimal("1E+3")),
            "1000.00"));
    cases.add(
        expect(
            "decimal with scale",
            "d2",
            (ps, i) -> ps.setObject(i, new BigDecimal("3.14159"), Types.DECIMAL, 2),
            "3.14"));
    // Text
    cases.add(expect("varchar quote", "v100", (ps, i) -> ps.setString(i, "it's"), "it's"));
    cases.add(
        expect(
            "varchar backslash",
            "v100",
            (ps, i) -> ps.setString(i, "a\\b\\\\c\\'d"),
            "a\\b\\\\c\\'d"));
    cases.add(
        expect(
            "varchar markers",
            "v100",
            (ps, i) -> ps.setString(i, "? -- /* # ` \" ;"),
            "? -- /* # ` \" ;"));
    cases.add(
        expect(
            "varchar unicode",
            "v100",
            (ps, i) -> ps.setString(i, "ñandú €  日本語 😀"),
            "ñandú €  日本語 😀"));
    cases.add(
        expect(
            "varchar newline", "v100", (ps, i) -> ps.setString(i, "a\nb\r\nc\td"), "a\nb\r\nc\td"));
    cases.add(expect("nstring", "v100", (ps, i) -> ps.setNString(i, "ñ'"), "ñ'"));
    cases.add(expect("empty string", "v100", (ps, i) -> ps.setString(i, ""), ""));
    cases.add(expect("char", "c10", (ps, i) -> ps.setString(i, "abc"), "abc"));
    cases.add(
        expect(
            "string long", "str", (ps, i) -> ps.setString(i, "x".repeat(5000)), "x".repeat(5000)));
    cases.add(
        expect(
            "uuid",
            "v100",
            (ps, i) -> ps.setObject(i, UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
            "123e4567-e89b-12d3-a456-426614174000"));
    cases.add(expect("character", "v100", (ps, i) -> ps.setObject(i, 'z'), "z"));
    cases.add(
        noWhere(
            "character stream",
            "str",
            (ps, i) -> ps.setCharacterStream(i, new java.io.StringReader("it's \\ streamed")),
            "it's \\ streamed"));
    cases.add(
        noWhere(
            "ascii stream",
            "str",
            (ps, i) ->
                ps.setAsciiStream(
                    i,
                    new java.io.ByteArrayInputStream(
                        "ascii'".getBytes(java.nio.charset.StandardCharsets.US_ASCII))),
            "ascii'"));
    cases.add(
        noWhere("bytes", "str", (ps, i) -> ps.setBytes(i, new byte[] {0x41, 0x42, 0x43}), "ABC"));
    cases.add(
        noWhere(
            "json",
            "js",
            (ps, i) -> ps.setString(i, "{\"a\": [1, \"it's\"]}"),
            "{\"a\":[1,\"it's\"]}"));
    cases.add(noWhere("ipv4", "ip4", (ps, i) -> ps.setString(i, "192.168.0.1"), "192.168.0.1"));
    cases.add(noWhere("ipv6", "ip6", (ps, i) -> ps.setString(i, "2001:db8::1"), "2001:db8::1"));
    // Dates
    cases.add(
        expect(
            "date sql",
            "dt",
            (ps, i) -> ps.setDate(i, java.sql.Date.valueOf("2024-02-29")),
            "2024-02-29"));
    cases.add(
        expect(
            "date local",
            "dt",
            (ps, i) -> ps.setObject(i, LocalDate.of(2024, 12, 31)),
            "2024-12-31"));
    cases.add(
        expect("date min", "dt", (ps, i) -> ps.setObject(i, LocalDate.of(1, 1, 1)), "0001-01-01"));
    cases.add(
        expect(
            "date max",
            "dt",
            (ps, i) -> ps.setObject(i, LocalDate.of(9999, 12, 31)),
            "9999-12-31"));
    cases.add(
        expect(
            "date with calendar",
            "dt",
            (ps, i) ->
                ps.setDate(
                    i,
                    java.sql.Date.valueOf("2024-06-15"),
                    Calendar.getInstance(TimeZone.getTimeZone("Pacific/Kiritimati"))),
            "2024-06-15"));
    // Times of day go into text columns: Doris has no time column
    cases.add(
        expect(
            "time sql",
            "v100",
            (ps, i) -> ps.setTime(i, java.sql.Time.valueOf("23:59:58")),
            "23:59:58"));
    cases.add(
        expect(
            "time local",
            "v100",
            (ps, i) -> ps.setObject(i, LocalTime.of(1, 2, 3, 456_000_000)),
            "01:02:03.456"));
    // Timestamps
    final LocalDateTime wall = LocalDateTime.of(2024, 1, 31, 10, 15, 30, 123_456_789);
    cases.add(
        expect(
            "timestamp sql",
            "dtm",
            (ps, i) -> ps.setTimestamp(i, Timestamp.valueOf(wall)),
            ts(wall, 6)));
    cases.add(expect("timestamp local", "dtm", (ps, i) -> ps.setObject(i, wall), ts(wall, 6)));
    cases.add(
        expect(
            "timestamp local object with type",
            "dtm",
            (ps, i) -> ps.setObject(i, wall, Types.TIMESTAMP),
            ts(wall, 6)));
    cases.add(
        expect(
            "timestamp without fraction",
            "dtm",
            (ps, i) -> ps.setTimestamp(i, Timestamp.valueOf("2024-07-01 00:00:00")),
            "2024-07-01 00:00:00.000000"));
    cases.add(
        noWhere(
            "timestamp rounds seconds",
            "dt0",
            (ps, i) -> ps.setObject(i, LocalDateTime.of(2024, 1, 31, 10, 15, 30, 600_000_000)),
            "2024-01-31 10:15:31"));
    cases.add(
        expect(
            "timestamp min",
            "dtm",
            (ps, i) -> ps.setObject(i, LocalDateTime.of(1, 1, 1, 0, 0)),
            "0001-01-01 00:00:00.000000"));
    cases.add(
        expect(
            "timestamp max",
            "dtm",
            (ps, i) -> ps.setObject(i, LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000)),
            "9999-12-31 23:59:59.999999"));
    cases.add(
        expect(
            "timestamp dst gap",
            "dtm",
            (ps, i) -> ps.setObject(i, LocalDateTime.of(2024, 3, 31, 2, 30)),
            "2024-03-31 02:30:00.000000"));
    cases.add(
        expect(
            "timestamp dst overlap",
            "dtm",
            (ps, i) -> ps.setObject(i, LocalDateTime.of(2024, 10, 27, 2, 30)),
            "2024-10-27 02:30:00.000000"));
    final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cases.add(
        expect(
            "timestamp with calendar",
            "dtm",
            (ps, i) ->
                ps.setTimestamp(
                    i,
                    new Timestamp(1_706_696_130_000L),
                    Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))),
            "2024-01-31 19:15:30.000000"));
    cases.add(
        expect(
            "timestamp with utc calendar",
            "dtm",
            (ps, i) -> ps.setTimestamp(i, new Timestamp(1_706_696_130_000L), utc),
            "2024-01-31 10:15:30.000000"));
    cases.add(
        zoned(
            "java.util.Date",
            "dtm",
            (ps, i) -> ps.setObject(i, new java.util.Date(86_400_000L * 365)),
            z ->
                ts(
                    LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(86_400_000L * 365), TimeZone.getDefault().toZoneId()),
                    6)));
    cases.add(
        zoned(
            "timestamp dst gap sql",
            "dtm",
            (ps, i) -> ps.setTimestamp(i, Timestamp.valueOf(LocalDateTime.of(2024, 3, 31, 2, 30))),
            z -> ts(Timestamp.valueOf(LocalDateTime.of(2024, 3, 31, 2, 30)).toLocalDateTime(), 6)));
    cases.add(
        zoned(
            "timestamp from epoch",
            "dtm",
            (ps, i) -> ps.setTimestamp(i, new Timestamp(1_711_848_600_000L)),
            z -> ts(new Timestamp(1_711_848_600_000L).toLocalDateTime(), 6)));
    // Values with an offset become the time of the session zone
    final OffsetDateTime odt =
        OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 123_456_789, ZoneOffset.ofHours(2));
    cases.add(
        zoned(
            "offset date time",
            "dtm",
            (ps, i) -> ps.setObject(i, odt),
            z -> ts(odt.atZoneSameInstant(z).toLocalDateTime(), 6)));
    final OffsetDateTime odtSummer =
        OffsetDateTime.of(2024, 7, 31, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30));
    cases.add(
        zoned(
            "offset date time summer",
            "dtm",
            (ps, i) -> ps.setObject(i, odtSummer),
            z -> ts(odtSummer.atZoneSameInstant(z).toLocalDateTime(), 6)));
    final OffsetDateTime odtNegative =
        OffsetDateTime.of(2024, 3, 31, 1, 30, 0, 0, ZoneOffset.ofHoursMinutes(-3, -30));
    cases.add(
        zoned(
            "offset date time negative",
            "dtm",
            (ps, i) -> ps.setObject(i, odtNegative),
            z -> ts(odtNegative.atZoneSameInstant(z).toLocalDateTime(), 6)));
    final ZonedDateTime zdt =
        ZonedDateTime.of(2024, 10, 27, 2, 30, 0, 0, ZoneId.of("Europe/Madrid"));
    cases.add(
        zoned(
            "zoned date time",
            "dtm",
            (ps, i) -> ps.setObject(i, zdt),
            z -> ts(zdt.withZoneSameInstant(z).toLocalDateTime(), 6)));
    final Instant instant = Instant.parse("2024-06-30T23:59:59.999999Z");
    cases.add(
        zoned(
            "instant",
            "dtm",
            (ps, i) -> ps.setObject(i, instant),
            z -> ts(instant.atZone(z).toLocalDateTime(), 6)));
    final OffsetDateTime odtNanos =
        OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 999_999_999, ZoneOffset.UTC);
    cases.add(
        zoned(
            "offset date time rounds",
            "dtm",
            (ps, i) -> ps.setObject(i, odtNanos),
            z -> ts(odtNanos.atZoneSameInstant(z).toLocalDateTime(), 6)));
    // Arrays
    cases.add(
        noWhere(
            "array of int",
            "ai",
            (ps, i) -> ps.setObject(i, new Integer[] {1, -2, null}),
            "[1, -2, null]"));
    cases.add(
        noWhere(
            "array of string",
            "astr",
            (ps, i) -> ps.setObject(i, new String[] {"a'b", "c\\d"}),
            "[\"a'b\", \"c\\d\"]"));
    cases.add(noWhere("empty array", "ai", (ps, i) -> ps.setObject(i, new Integer[0]), "[]"));
    cases.add(
        noWhere("array from list", "ai", (ps, i) -> ps.setObject(i, List.of(3, 4)), "[3, 4]"));
    // Nulls
    for (String column :
        List.of(
            "b", "t1", "s2", "i4", "i8", "i16", "f4", "f8", "d9", "d2", "c10", "v100", "str", "dt",
            "dtm", "dt0", "js", "ai", "astr", "ip4", "ip6")) {
      cases.add(noWhere("null " + column, column, (ps, i) -> ps.setNull(i, Types.NULL), "<null>"));
      cases.add(
          noWhere("null object " + column, column, (ps, i) -> ps.setObject(i, null), "<null>"));
    }
    return cases;
  }

  @TestFactory
  public List<DynamicTest> types() {
    assumeTrue(URL != null, "Set -Ddoris.flight.url to run against Apache Doris");
    final List<DynamicTest> tests = new ArrayList<>();
    for (final Case test : cases()) {
      for (final String zone : JVM_ZONES) {
        tests.add(
            DynamicTest.dynamicTest(
                test.name() + " [" + zone + "]",
                () -> {
                  try {
                    run(test, zone);
                  } catch (final Throwable t) {
                    throw new AssertionError(test.name() + " [" + zone + "]: " + t.getMessage(), t);
                  }
                }));
      }
    }
    return tests;
  }

  @Test
  public void batchOfInsertsIsGroupedAndWritesEveryRow() throws Exception {
    assumeTrue(URL != null, "Set -Ddoris.flight.url to run against Apache Doris");
    final int rows = 2500;
    try (Connection connection = open();
        Statement st = connection.createStatement()) {
      st.execute("DELETE FROM " + TABLE + " WHERE id >= 100000");
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO " + TABLE + " (id, i4, v100, dtm) VALUES (?, ?, ?, ?)")) {
        for (int i = 0; i < rows; i++) {
          ps.setInt(1, 100000 + i);
          ps.setInt(2, i);
          ps.setString(3, "it's (" + i + ") \\ ?");
          ps.setObject(4, LocalDateTime.of(2024, 1, 31, 10, 15, 30, 123456000));
          ps.addBatch();
        }
        final int[] counts = ps.executeBatch();
        assertEquals(rows, counts.length);
      }
      try (ResultSet rs =
          st.executeQuery(
              "SELECT COUNT(*), SUM(i4), MIN(v100), COUNT(DISTINCT CAST(dtm AS STRING)) FROM "
                  + TABLE
                  + " WHERE id >= 100000")) {
        rs.next();
        assertEquals(rows, rs.getLong(1));
        assertEquals((long) rows * (rows - 1) / 2, rs.getLong(2));
        assertEquals("it's (0) \\ ?", rs.getString(3));
        assertEquals(1, rs.getLong(4));
      }
      st.execute("DELETE FROM " + TABLE + " WHERE id >= 100000");
    }
  }

  @TestFactory
  public List<DynamicTest> conditions() {
    assumeTrue(URL != null, "Set -Ddoris.flight.url to run against Apache Doris");
    final List<DynamicTest> tests = new ArrayList<>();
    for (final String zone : JVM_ZONES) {
      tests.add(DynamicTest.dynamicTest("conditions [" + zone + "]", () -> conditions(zone)));
    }
    return tests;
  }

  private static void insertRow(Connection connection, int id, String sql) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("INSERT INTO " + TABLE + " (id, " + sql);
    }
  }

  private void conditions(String zone) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(zone));
    final int base = 100_000 + nextId++ * 10;
    try (Connection connection = open()) {
      insertRow(
          connection,
          base,
          "i4, i8, v100, dtm, dt, d2) VALUES ("
              + base
              + ", 3, 30, 'it''s', '2024-01-31 10:15:30', '2024-01-31', 1.50)");
      insertRow(
          connection,
          base + 1,
          "i4, i8, v100, dtm, dt, d2) VALUES ("
              + (base + 1)
              + ", -3, -30, 'b\\\\c', '2024-03-31 03:30:00', '2024-03-31', -2.25)");
      insertRow(
          connection,
          base + 2,
          "i4, i8, v100, dtm, dt, d2) VALUES ("
              + (base + 2)
              + ", 9, 90, 'x', '2024-10-27 02:30:00', '2024-10-27', 0.00)");
      final String ids = " AND id BETWEEN " + base + " AND " + (base + 2);
      // BETWEEN and IN with negative numbers
      assertEquals(
          2L,
          count(
              connection,
              "i4 BETWEEN ? AND ?" + ids,
              (ps, i) -> {
                ps.setInt(i, -3);
                ps.setInt(i + 1, 3);
              },
              2));
      assertEquals(
          2L,
          count(
              connection,
              "i4 IN (?, ?, ?)" + ids,
              (ps, i) -> {
                ps.setInt(i, -3);
                ps.setInt(i + 1, 9);
                ps.setInt(i + 2, 100);
              },
              3));
      // An operator before a negative number must not fuse with its sign
      assertEquals(1L, count(connection, "i4 - ? = 6" + ids, (ps, i) -> ps.setInt(i, -3), 1));
      assertEquals(1L, count(connection, "i4 = 1 - ?" + ids, (ps, i) -> ps.setInt(i, 4), 1));
      assertEquals(1L, count(connection, "i8 = - ?" + ids, (ps, i) -> ps.setLong(i, -30), 1));
      // Text
      assertEquals(
          1L, count(connection, "v100 LIKE ?" + ids, (ps, i) -> ps.setString(i, "it'%"), 1));
      assertEquals(1L, count(connection, "v100 = ?" + ids, (ps, i) -> ps.setString(i, "b\\c"), 1));
      // Dates and timestamps
      assertEquals(
          2L,
          count(
              connection,
              "dt >= ? AND dt <= ?" + ids,
              (ps, i) -> {
                ps.setDate(i, java.sql.Date.valueOf("2024-01-31"));
                ps.setObject(i + 1, LocalDate.of(2024, 3, 31));
              },
              2));
      assertEquals(
          1L,
          count(
              connection,
              "dtm = ?" + ids,
              (ps, i) -> ps.setTimestamp(i, Timestamp.valueOf("2024-01-31 10:15:30")),
              1));
      assertEquals(
          3L,
          count(
              connection,
              "dtm BETWEEN ? AND ?" + ids,
              (ps, i) -> {
                ps.setObject(i, LocalDateTime.of(2024, 1, 1, 0, 0));
                ps.setTimestamp(i + 1, Timestamp.valueOf("2024-12-31 23:59:59.999999"));
              },
              2));
      assertEquals(
          3L,
          count(
              connection,
              "dtm < ?" + ids,
              (ps, i) -> ps.setObject(i, LocalDateTime.of(2025, 1, 1, 0, 0)),
              1));
      assertEquals(
          1L,
          count(
              connection,
              "dt = CAST(? AS DATE)" + ids,
              (ps, i) -> ps.setObject(i, "2024-10-27"),
              1));
      // A value with an offset is compared as the instant it is, in the time zone of the session
      final OffsetDateTime sameInstant =
          LocalDateTime.of(2024, 1, 31, 10, 15, 30)
              .atZone(sessionZone)
              .toOffsetDateTime()
              .withOffsetSameInstant(ZoneOffset.ofHours(-7));
      assertEquals(
          1L, count(connection, "dtm = ?" + ids, (ps, i) -> ps.setObject(i, sameInstant), 1));
      // Decimals
      assertEquals(
          1L,
          count(
              connection,
              "d2 = ?" + ids,
              (ps, i) -> ps.setBigDecimal(i, new BigDecimal("1.5")),
              1));
      assertEquals(
          1L,
          count(
              connection, "d2 < ?" + ids, (ps, i) -> ps.setBigDecimal(i, new BigDecimal("-2")), 1));
      // A parameter alone in a SELECT list
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT CAST(? AS STRING), CAST(? AS STRING), CAST(? AS STRING)")) {
        ps.setString(1, "a'b");
        ps.setBigDecimal(2, new BigDecimal("-0.5"));
        ps.setObject(3, LocalDate.of(2024, 2, 29));
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals("a'b", rs.getString(1));
          assertEquals("-0.5", rs.getString(2));
          assertEquals("2024-02-29", rs.getString(3));
        }
      }
      try (Statement st = connection.createStatement()) {
        st.execute("DELETE FROM " + TABLE + " WHERE id BETWEEN " + base + " AND " + (base + 2));
      }
    }
  }

  private static long count(Connection connection, String where, Setter setter, int ignored)
      throws Exception {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE + " WHERE " + where)) {
      setter.set(ps, 1);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
