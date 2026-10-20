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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.arrow.driver.jdbc.dremio.Dremio.Sample;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Timestamps persisted as Parquet (CTAS into {@code $scratch}) and queried back: storage round trip,
 * predicates, ordering, aggregation, NULLs and a multi-batch result set.
 */
class TimestampTableTest {

  private static final int BIG_ROWS = 100_000;
  private static final LocalDateTime BIG_BASE = LocalDateTime.parse("1960-01-01T00:00:00");

  private static Connection conn;
  private static String samples;
  private static String withNulls;
  private static String big;

  @BeforeAll
  static void setUp() throws Exception {
    conn = Dremio.connectOrSkip();
    samples =
        Dremio.createScratchTable(
            conn,
            "ts_samples",
            "SELECT id, ts, CAST(ts AS DATE) AS d, CAST(ts AS TIME) AS t FROM ("
                + Dremio.valuesSql(Dremio.SAMPLES)
                + ") q");
    withNulls =
        Dremio.createScratchTable(
            conn,
            "ts_nulls",
            "SELECT id, CASE WHEN id % 3 = 0 THEN CAST(NULL AS TIMESTAMP) ELSE ts END AS ts FROM ("
                + Dremio.valuesSql(Dremio.SAMPLES)
                + ") q");
    String digits = "(VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9))";
    big =
        Dremio.createScratchTable(
            conn,
            "ts_big",
            "SELECT n, TIMESTAMPADD(SECOND, CAST((n * 7) % 86400 AS INTEGER),"
                + " TIMESTAMPADD(DAY, n, TIMESTAMP '"
                + BIG_BASE.toLocalDate()
                + " 00:00:00')) AS ts FROM (SELECT a.x*10000 + b.x*1000 + c.x*100 + d.x*10 + e.x"
                + " AS n FROM "
                + digits
                + " a(x) CROSS JOIN "
                + digits
                + " b(x) CROSS JOIN "
                + digits
                + " c(x) CROSS JOIN "
                + digits
                + " d(x) CROSS JOIN "
                + digits
                + " e(x)) g");
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (conn != null) {
      Dremio.dropQuietly(conn, samples);
      Dremio.dropQuietly(conn, withNulls);
      Dremio.dropQuietly(conn, big);
      conn.close();
    }
  }

  static List<String> zones() {
    return Dremio.JVM_ZONES;
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("Parquet round trip: TIMESTAMP, DATE and TIME columns come back exactly")
  void storedValuesRoundTrip(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c -> {
          List<org.junit.jupiter.api.function.Executable> failures = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT id, ts, d, t FROM " + samples + " ORDER BY id")) {
            for (Sample sm : Dremio.SAMPLES) {
              assertTrue(rs.next());
              // read eagerly: the cursor moves on and the ResultSet is closed before assertAll runs
              LocalDateTime ts = rs.getObject(2, LocalDateTime.class);
              LocalDate d = rs.getDate(3).toLocalDate();
              String t = rs.getString(4);
              failures.add(() -> assertEquals(sm.ldt(), ts, sm.label() + " TIMESTAMP"));
              failures.add(() -> assertEquals(sm.ldt().toLocalDate(), d, sm.label() + " DATE"));
              failures.add(
                  () ->
                      assertEquals(
                          sm.ldt().toLocalTime(),
                          LocalTime.parse(t),
                          sm.label() + " TIME (string " + t + ")"));
            }
          }
          assertAll(failures);
        });
  }

  @Test
  @DisplayName("Stored TIMESTAMP read with getTimestamp (JVM zone UTC) is the wall-clock value")
  void storedGetTimestampInUtc() throws Exception {
    Dremio.inJvmZone(
        "UTC",
        c -> {
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT id, ts FROM " + samples + " ORDER BY id")) {
            for (Sample sm : Dremio.SAMPLES) {
              rs.next();
              assertEquals(Timestamp.valueOf(sm.ldt()), rs.getTimestamp(2), sm.label());
            }
          }
        });
  }

  @Test
  @DisplayName("ORDER BY ts orders pre-epoch, epoch and far-future values chronologically")
  void orderBy() throws Exception {
    List<Integer> expected =
        Dremio.SAMPLES.stream()
            .sorted(Comparator.comparing(Sample::ldt).thenComparing(Sample::id))
            .map(Sample::id)
            .collect(Collectors.toList());
    List<Integer> actual = new ArrayList<>();
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM " + samples + " ORDER BY ts, id")) {
      while (rs.next()) {
        actual.add(rs.getInt(1));
      }
    }
    assertEquals(expected, actual);
  }

  @Test
  @DisplayName("MIN / MAX over TIMESTAMP")
  void minMax() throws Exception {
    LocalDateTime min =
        Dremio.SAMPLES.stream().map(Sample::ldt).min(Comparator.naturalOrder()).get();
    LocalDateTime max =
        Dremio.SAMPLES.stream().map(Sample::ldt).max(Comparator.naturalOrder()).get();
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT MIN(ts), MAX(ts) FROM " + samples)) {
      assertTrue(rs.next());
      assertEquals(min, rs.getObject(1, LocalDateTime.class));
      assertEquals(max, rs.getObject(2, LocalDateTime.class));
    }
  }

  @Test
  @DisplayName("Equality, range and BETWEEN predicates with TIMESTAMP literals")
  void predicates() throws Exception {
    LocalDateTime lo = LocalDateTime.parse("1970-01-01T00:00:00");
    LocalDateTime hi = LocalDateTime.parse("2024-03-10T12:34:56.789");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (Sample sm : Dremio.SAMPLES) {
      long expected = Dremio.SAMPLES.stream().filter(o -> o.ldt().equals(sm.ldt())).count();
      checks.add(
          () ->
              assertEquals(
                  expected,
                  count("ts = " + sm.sqlLiteral()),
                  "ts = literal for " + sm.label()));
    }
    checks.add(
        () ->
            assertEquals(
                Dremio.SAMPLES.stream()
                    .filter(o -> !o.ldt().isBefore(lo) && o.ldt().isBefore(hi))
                    .count(),
                count("ts >= TIMESTAMP '1970-01-01 00:00:00' AND ts < TIMESTAMP '2024-03-10 12:34:56.789'"),
                "half-open range"));
    checks.add(
        () ->
            assertEquals(
                Dremio.SAMPLES.stream()
                    .filter(o -> !o.ldt().isBefore(lo) && !o.ldt().isAfter(hi))
                    .count(),
                count("ts BETWEEN TIMESTAMP '1970-01-01 00:00:00' AND TIMESTAMP '2024-03-10 12:34:56.789'"),
                "BETWEEN is inclusive on both ends"));
    checks.add(
        () ->
            assertEquals(
                Dremio.SAMPLES.stream().filter(o -> o.ldt().isBefore(lo)).count(),
                count("ts < TIMESTAMP '1970-01-01 00:00:00'"),
                "pre-epoch values compare as smaller"));
    assertAll(checks);
  }

  private long count(String where) throws Exception {
    return count(samples, where);
  }

  private long count(String table, String where) throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  @DisplayName("GROUP BY DATE_TRUNC('MONTH'/'YEAR') buckets match java.time")
  void groupByTruncation() throws Exception {
    for (String unit : List.of("DAY", "MONTH", "YEAR")) {
      Map<LocalDateTime, Long> expected = new TreeMap<>();
      for (Sample sm : Dremio.SAMPLES) {
        LocalDateTime key;
        switch (unit) {
          case "DAY":
            key = sm.ldt().truncatedTo(ChronoUnit.DAYS);
            break;
          case "MONTH":
            key = sm.ldt().withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            break;
          default:
            key = sm.ldt().withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
            break;
        }
        expected.merge(key, 1L, Long::sum);
      }
      Map<LocalDateTime, Long> actual = new TreeMap<>();
      try (Statement s = conn.createStatement();
          ResultSet rs =
              s.executeQuery(
                  "SELECT DATE_TRUNC('"
                      + unit
                      + "', ts) AS k, COUNT(*) FROM "
                      + samples
                      + " GROUP BY 1")) {
        while (rs.next()) {
          actual.put(rs.getObject(1, LocalDateTime.class), rs.getLong(2));
        }
      }
      assertEquals(expected, actual, "DATE_TRUNC " + unit);
    }
  }

  @Test
  @DisplayName("NULL timestamps from storage: null getters, wasNull, IS NULL, COUNT(ts)")
  void nulls() throws Exception {
    long nullRows = Dremio.SAMPLES.stream().filter(sm -> sm.id() % 3 == 0).count();
    assertEquals(nullRows, count(withNulls, "ts IS NULL"));
    assertEquals(Dremio.SAMPLES.size() - nullRows, count(withNulls, "ts IS NOT NULL"));
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, ts FROM " + withNulls + " ORDER BY id")) {
      for (Sample sm : Dremio.SAMPLES) {
        assertTrue(rs.next());
        if (sm.id() % 3 == 0) {
          assertNull(rs.getTimestamp(2), "row " + sm.id());
          assertTrue(rs.wasNull());
          assertNull(rs.getObject(2, LocalDateTime.class));
          assertNull(rs.getString(2));
        } else {
          assertEquals(sm.ldt(), rs.getObject(2, LocalDateTime.class), "row " + sm.id());
          assertFalse(rs.wasNull());
        }
      }
    }
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("100k rows across several Arrow batches: every value exact, in order")
  void largeResultSet(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c -> {
          long start = System.nanoTime();
          int rows = 0;
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT n, ts FROM " + big + " ORDER BY n")) {
            while (rs.next()) {
              long n = rs.getLong(1);
              LocalDateTime expected = BIG_BASE.plusDays(n).plusSeconds((n * 7) % 86400);
              LocalDateTime actual = rs.getObject(2, LocalDateTime.class);
              if (!expected.equals(actual)) {
                throw new AssertionError("row n=" + n + " expected " + expected + " got " + actual);
              }
              assertEquals(rows, n, "row sequence");
              rows++;
            }
          }
          assertEquals(BIG_ROWS, rows);
          System.out.printf(
              "[%s] read %d timestamp rows in %d ms%n",
              zone, rows, (System.nanoTime() - start) / 1_000_000);
        });
  }
}
