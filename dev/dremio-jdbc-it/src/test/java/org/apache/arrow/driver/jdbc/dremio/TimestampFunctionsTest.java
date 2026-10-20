/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.driver.jdbc.dremio;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.arrow.driver.jdbc.dremio.Dremio.Sample;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Dremio's own date/time functions, read back through the JDBC driver and compared with a
 * {@code java.time} oracle. Part A uses constant expressions (folded by the planner); part B applies
 * the same functions to Parquet-backed columns so they run in the execution engine, which is where
 * pre-1970 and extreme values tend to misbehave.
 *
 * <p>Everything runs with the JVM zone set to Europe/Madrid so that any leak of the client zone into
 * server-side semantics (Dremio TIMESTAMP is zone-less) would show up.
 */
class TimestampFunctionsTest {

  private static Connection conn;
  private static String table;

  @BeforeAll
  static void setUp() throws Exception {
    conn = Dremio.connectOrSkip();
    table =
        Dremio.createScratchTable(
            conn, "ts_fn", "SELECT id, ts FROM (" + Dremio.valuesSql(Dremio.SAMPLES) + ") q");
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (conn != null) {
      Dremio.dropQuietly(conn, table);
      conn.close();
    }
  }

  // ---------------------------------------------------------------- A: constant expressions

  private static Arguments ts(String name, String sql, String expectedIso) {
    return Arguments.of(name, sql, LocalDateTime.parse(expectedIso));
  }

  static Stream<Arguments> constantTimestampExpressions() {
    return Stream.of(
        ts("TO_TIMESTAMP(str, fmt)",
            "TO_TIMESTAMP('2024-03-10 12:34:56', 'YYYY-MM-DD HH24:MI:SS')", "2024-03-10T12:34:56"),
        ts("TO_TIMESTAMP(epoch seconds) is UTC", "TO_TIMESTAMP(1710074096)", "2024-03-10T12:34:56"),
        ts("TO_TIMESTAMP(negative epoch seconds)", "TO_TIMESTAMP(-1)", "1969-12-31T23:59:59"),
        ts("CAST(bigint epoch millis)", "CAST(1710074096789 AS TIMESTAMP)", "2024-03-10T12:34:56.789"),
        ts("CAST(negative bigint epoch millis)", "CAST(-1 AS TIMESTAMP)", "1969-12-31T23:59:59.999"),
        ts("CAST(DATE)", "CAST(DATE '2024-03-10' AS TIMESTAMP)", "2024-03-10T00:00:00"),
        ts("CAST(string)", "CAST('2024-03-10 12:34:56.789' AS TIMESTAMP)", "2024-03-10T12:34:56.789"),
        ts("CAST(string with +02:00 offset is normalised to UTC)",
            "CAST('2024-03-10 12:34:56+02:00' AS TIMESTAMP)", "2024-03-10T10:34:56"),
        ts("sub-millisecond literal is truncated (.7897)",
            "TIMESTAMP '2024-03-10 12:34:56.7897'", "2024-03-10T12:34:56.789"),
        ts("sub-millisecond literal is truncated (.7895)",
            "TIMESTAMP '2024-03-10 12:34:56.7895'", "2024-03-10T12:34:56.789"),
        ts("DATE_ADD over a Madrid DST gap is naive",
            "DATE_ADD(TIMESTAMP '2024-03-30 02:30:00', 1)", "2024-03-31T02:30:00"),
        ts("+ INTERVAL DAY across NY DST is naive",
            "TIMESTAMP '2024-03-09 02:30:00' + INTERVAL '1' DAY", "2024-03-10T02:30:00"),
        ts("+ INTERVAL HOUR across NY DST is naive",
            "TIMESTAMP '2024-03-10 01:30:00' + INTERVAL '1' HOUR", "2024-03-10T02:30:00"),
        ts("+ INTERVAL MONTH clamps to month end",
            "TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '1' MONTH", "2024-02-29T10:00:00"),
        ts("- INTERVAL YEAR from leap day",
            "TIMESTAMP '2024-02-29 10:00:00' - INTERVAL '1' YEAR", "2023-02-28T10:00:00"),
        ts("TIMESTAMPADD(SECOND)", "TIMESTAMPADD(SECOND, 90, TIMESTAMP '2000-01-01 00:00:00')",
            "2000-01-01T00:01:30"),
        ts("TIMESTAMPADD(SECOND) backwards across epoch",
            "TIMESTAMPADD(SECOND, -1, TIMESTAMP '1970-01-01 00:00:00')", "1969-12-31T23:59:59"),
        ts("DATE_TRUNC('YEAR') pre-epoch", "DATE_TRUNC('YEAR', TIMESTAMP '1969-07-20 20:17:40.123')",
            "1969-01-01T00:00:00"),
        ts("DATE_TRUNC('QUARTER')", "DATE_TRUNC('QUARTER', TIMESTAMP '2024-05-17 20:17:40.123')",
            "2024-04-01T00:00:00"),
        ts("DATE_TRUNC('WEEK') is Monday based", "DATE_TRUNC('WEEK', TIMESTAMP '2024-03-10 12:34:56.789')",
            "2024-03-04T00:00:00"),
        ts("CONVERT_TIMEZONE NY->UTC in DST",
            "CONVERT_TIMEZONE('America/New_York', 'UTC', TIMESTAMP '2024-03-10 12:00:00')",
            "2024-03-10T16:00:00"),
        ts("CONVERT_TIMEZONE NY->UTC before DST",
            "CONVERT_TIMEZONE('America/New_York', 'UTC', TIMESTAMP '2024-03-09 12:00:00')",
            "2024-03-09T17:00:00"),
        ts("CONVERT_TIMEZONE Madrid->UTC in DST",
            "CONVERT_TIMEZONE('Europe/Madrid', 'UTC', TIMESTAMP '2024-07-01 12:00:00')",
            "2024-07-01T10:00:00"),
        ts("CONVERT_TIMEZONE UTC->Kolkata",
            "CONVERT_TIMEZONE('UTC', 'Asia/Kolkata', TIMESTAMP '2024-03-10 12:00:00')",
            "2024-03-10T17:30:00"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constantTimestampExpressions")
  @DisplayName("Constant expression returning TIMESTAMP")
  void constantExpression(String name, String expr, LocalDateTime expected) throws Exception {
    Dremio.inJvmZone(
        "Europe/Madrid",
        c -> assertEquals(expected, Dremio.scalarLdt(c, "SELECT " + expr + " AS v"), expr));
  }

  static Stream<Arguments> constantNumbers() {
    String t = "TIMESTAMP '2024-03-10 12:34:56.789'";
    return Stream.of(
        Arguments.of("EXTRACT(YEAR)", "EXTRACT(YEAR FROM " + t + ")", 2024L),
        Arguments.of("EXTRACT(MONTH)", "EXTRACT(MONTH FROM " + t + ")", 3L),
        Arguments.of("EXTRACT(DAY)", "EXTRACT(DAY FROM " + t + ")", 10L),
        Arguments.of("EXTRACT(HOUR)", "EXTRACT(HOUR FROM " + t + ")", 12L),
        Arguments.of("EXTRACT(MINUTE)", "EXTRACT(MINUTE FROM " + t + ")", 34L),
        Arguments.of("EXTRACT(SECOND)", "EXTRACT(SECOND FROM " + t + ")", 56L),
        Arguments.of("EXTRACT(DOY)", "EXTRACT(DOY FROM " + t + ")", 70L),
        Arguments.of("EXTRACT(QUARTER)", "EXTRACT(QUARTER FROM " + t + ")", 1L),
        Arguments.of("TIMESTAMPDIFF(SECOND)",
            "TIMESTAMPDIFF(SECOND, TIMESTAMP '2000-01-01 00:00:00', TIMESTAMP '2000-01-02 00:00:01')",
            86401L),
        Arguments.of("TIMESTAMPDIFF(DAY) across Madrid DST is 24h-agnostic",
            "TIMESTAMPDIFF(DAY, TIMESTAMP '2024-03-30 12:00:00', TIMESTAMP '2024-03-31 12:00:00')", 1L),
        Arguments.of("TIMESTAMPDIFF(HOUR) across Madrid DST has no hour lost",
            "TIMESTAMPDIFF(HOUR, TIMESTAMP '2024-03-31 00:00:00', TIMESTAMP '2024-03-31 06:00:00')",
            6L),
        Arguments.of("TIMESTAMPDIFF(MONTH)",
            "TIMESTAMPDIFF(MONTH, TIMESTAMP '2024-01-31 00:00:00', TIMESTAMP '2024-03-01 00:00:00')",
            1L),
        Arguments.of("UNIX_TIMESTAMP(str, fmt) is UTC",
            "UNIX_TIMESTAMP('2024-03-10 12:34:56', 'YYYY-MM-DD HH24:MI:SS')", 1710074096L),
        Arguments.of("CAST(ts AS BIGINT) is epoch millis (UTC)", "CAST(" + t + " AS BIGINT)",
            1710074096789L),
        Arguments.of("CAST(pre-epoch ts AS BIGINT)",
            "CAST(TIMESTAMP '1969-12-31 23:59:59.999' AS BIGINT)", -1L));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constantNumbers")
  @DisplayName("Constant expression returning a number")
  void constantNumber(String name, String expr, long expected) throws Exception {
    Dremio.inJvmZone(
        "Europe/Madrid",
        c -> assertEquals(expected, ((Number) Dremio.scalar(c, "SELECT " + expr + " AS v")).longValue()));
  }

  @Test
  @DisplayName("CURRENT_TIMESTAMP / LOCALTIMESTAMP / NOW() are the server's UTC wall-clock")
  void currentTimestampIsUtcWallClock() throws Exception {
    for (String zone : Dremio.JVM_ZONES) {
      Dremio.inJvmZone(
          zone,
          c -> {
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            for (String fn : List.of("CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "NOW()")) {
              LocalDateTime server = Dremio.scalarLdt(c, "SELECT " + fn + " AS v");
              long skew = Math.abs(Duration.between(nowUtc, server).getSeconds());
              assertTrue(skew < 120, zone + " " + fn + " = " + server + " vs client UTC " + nowUtc);
            }
          });
    }
  }

  @Test
  @DisplayName("CURRENT_DATE and CURRENT_TIME agree with CURRENT_TIMESTAMP")
  void currentDateAndTime() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT CURRENT_TIMESTAMP, CURRENT_DATE, CURRENT_TIME")) {
      rs.next();
      LocalDateTime ts = rs.getObject(1, LocalDateTime.class);
      LocalDate d = rs.getDate(2).toLocalDate();
      LocalTime t = LocalTime.parse(rs.getString(3));
      // allow the statement to straddle midnight
      assertTrue(Math.abs(ChronoUnit.DAYS.between(ts.toLocalDate(), d)) <= 1, "date " + d + " vs " + ts);
      assertTrue(
          Math.abs(Duration.between(ts.toLocalTime(), t).getSeconds()) < 5
              || Math.abs(Duration.between(ts.toLocalTime(), t).getSeconds()) > 86395,
          "time " + t + " vs " + ts);
    }
  }

  // ---------------------------------------------------------------- B: column expressions

  /** One server-side expression over the {@code ts} column and its java.time counterpart. */
  private record ColumnCase(String name, String sqlExpr, Function<LocalDateTime, Object> expected) {}

  private static List<ColumnCase> columnCases() {
    List<ColumnCase> l = new ArrayList<>();
    l.add(new ColumnCase("identity", "ts", x -> x));
    l.add(new ColumnCase("CAST(ts AS DATE)", "CAST(ts AS DATE)", LocalDateTime::toLocalDate));
    l.add(new ColumnCase("CAST(ts AS TIME)", "CAST(ts AS TIME)", LocalDateTime::toLocalTime));
    // Controls: format on the server so the driver's value conversion is out of the picture.
    l.add(new ColumnCase("control: TO_CHAR(CAST(ts AS TIME))", "TO_CHAR(CAST(ts AS TIME), 'HH24:MI:SS.FFF')",
        x -> DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(x)));
    l.add(new ColumnCase("control: TO_CHAR(DATE_TRUNC DAY)",
        "TO_CHAR(DATE_TRUNC('DAY', ts), 'YYYY-MM-DD HH24:MI:SS.FFF')",
        x -> DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").format(x.truncatedTo(ChronoUnit.DAYS))));
    l.add(new ColumnCase("DATE_TRUNC SECOND", "DATE_TRUNC('SECOND', ts)", x -> x.truncatedTo(ChronoUnit.SECONDS)));
    l.add(new ColumnCase("DATE_TRUNC MINUTE", "DATE_TRUNC('MINUTE', ts)", x -> x.truncatedTo(ChronoUnit.MINUTES)));
    l.add(new ColumnCase("DATE_TRUNC HOUR", "DATE_TRUNC('HOUR', ts)", x -> x.truncatedTo(ChronoUnit.HOURS)));
    l.add(new ColumnCase("DATE_TRUNC DAY", "DATE_TRUNC('DAY', ts)", x -> x.truncatedTo(ChronoUnit.DAYS)));
    l.add(new ColumnCase("DATE_TRUNC MONTH", "DATE_TRUNC('MONTH', ts)", x -> x.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)));
    l.add(new ColumnCase("DATE_TRUNC YEAR", "DATE_TRUNC('YEAR', ts)", x -> x.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)));
    l.add(new ColumnCase("EXTRACT YEAR", "EXTRACT(YEAR FROM ts)", x -> (long) x.getYear()));
    l.add(new ColumnCase("EXTRACT MONTH", "EXTRACT(MONTH FROM ts)", x -> (long) x.getMonthValue()));
    l.add(new ColumnCase("EXTRACT DAY", "EXTRACT(DAY FROM ts)", x -> (long) x.getDayOfMonth()));
    l.add(new ColumnCase("EXTRACT HOUR", "EXTRACT(HOUR FROM ts)", x -> (long) x.getHour()));
    l.add(new ColumnCase("EXTRACT MINUTE", "EXTRACT(MINUTE FROM ts)", x -> (long) x.getMinute()));
    l.add(new ColumnCase("EXTRACT SECOND", "EXTRACT(SECOND FROM ts)", x -> (long) x.getSecond()));
    l.add(new ColumnCase("EXTRACT DOY", "EXTRACT(DOY FROM ts)", x -> (long) x.getDayOfYear()));
    l.add(new ColumnCase("EXTRACT QUARTER", "EXTRACT(QUARTER FROM ts)", x -> (long) x.get(IsoFields.QUARTER_OF_YEAR)));
    l.add(new ColumnCase("TO_CHAR", "TO_CHAR(ts, 'YYYY-MM-DD HH24:MI:SS.FFF')",
        x -> DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").format(x)));
    l.add(new ColumnCase("DATE_ADD 1 day", "DATE_ADD(ts, 1)", x -> x.plusDays(1)));
    l.add(new ColumnCase("+ INTERVAL 25 HOUR", "ts + INTERVAL '25' HOUR", x -> x.plusHours(25)));
    l.add(new ColumnCase("TIMESTAMPADD(SECOND, -3600)", "TIMESTAMPADD(SECOND, -3600, ts)", x -> x.minusHours(1)));
    l.add(new ColumnCase("CAST(ts AS BIGINT) epoch millis", "CAST(ts AS BIGINT)",
        x -> x.toInstant(ZoneOffset.UTC).toEpochMilli()));
    return l;
  }

  private static Object read(ResultSet rs, Object sample) throws Exception {
    if (sample instanceof LocalDateTime) {
      return rs.getObject(2, LocalDateTime.class);
    } else if (sample instanceof LocalDate) {
      return rs.getDate(2).toLocalDate();
    } else if (sample instanceof LocalTime) {
      return LocalTime.parse(rs.getString(2));
    } else if (sample instanceof Long) {
      return rs.getLong(2);
    }
    return rs.getString(2);
  }

  @Test
  @DisplayName("Server functions applied to stored TIMESTAMP columns match java.time")
  void columnExpressions() throws Exception {
    List<Executable> failures = new ArrayList<>();
    Dremio.inJvmZone(
        "Europe/Madrid",
        c -> {
          for (ColumnCase cc : columnCases()) {
            Map<Integer, Object> actual = new HashMap<>();
            try (Statement s = c.createStatement();
                ResultSet rs =
                    s.executeQuery("SELECT id, " + cc.sqlExpr() + " AS v FROM " + table)) {
              while (rs.next()) {
                int id = rs.getInt(1);
                Sample sm = Dremio.SAMPLES.get(id - 1);
                actual.put(id, read(rs, cc.expected().apply(sm.ldt())));
              }
            } catch (Exception e) {
              failures.add(
                  () -> {
                    throw new AssertionError("[" + cc.name() + "] query failed: " + e.getMessage(), e);
                  });
              continue;
            }
            for (Sample sm : Dremio.SAMPLES) {
              Object exp = cc.expected().apply(sm.ldt());
              Object act = actual.get(sm.id());
              failures.add(
                  () ->
                      assertEquals(
                          exp, act, "[" + cc.name() + "] " + sm.label() + " (" + sm.ldt() + ")"));
            }
          }
        });
    assertAll(failures);
  }
}
