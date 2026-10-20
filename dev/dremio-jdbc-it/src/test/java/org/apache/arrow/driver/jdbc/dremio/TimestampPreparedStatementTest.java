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
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.apache.arrow.driver.jdbc.dremio.Dremio.Sample;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Timestamp / date / time bind parameters. For every sample the statement
 * {@code SELECT id FROM t WHERE ts = ?} must return exactly the rows whose stored wall-clock value
 * equals the wall-clock value of the bound {@link Timestamp} in the relevant zone (JVM default, or
 * the Calendar passed to {@code setTimestamp}).
 */
class TimestampPreparedStatementTest {

  private static Connection setupConn;
  private static String table;

  @BeforeAll
  static void setUp() throws Exception {
    setupConn = Dremio.connectOrSkip();
    table =
        Dremio.createScratchTable(
            setupConn,
            "ts_ps",
            "SELECT id, ts, CAST(ts AS DATE) AS d, CAST(ts AS TIME) AS t FROM ("
                + Dremio.valuesSql(Dremio.SAMPLES)
                + ") q");
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (setupConn != null) {
      Dremio.dropQuietly(setupConn, table);
      setupConn.close();
    }
  }

  static List<String> zones() {
    return Dremio.JVM_ZONES;
  }

  private interface Binder {
    void bind(PreparedStatement ps, Sample s) throws Exception;
  }

  private static List<Integer> ids(Connection c, String where, Binder binder, Sample s)
      throws Exception {
    List<Integer> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement("SELECT id FROM " + table + " WHERE " + where + " ORDER BY id")) {
      binder.bind(ps, s);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getInt(1));
        }
      }
    }
    return out;
  }

  private static List<Integer> idsWithWallClock(LocalDateTime wall) {
    return Dremio.SAMPLES.stream()
        .filter(o -> o.ldt().equals(wall))
        .map(Sample::id)
        .collect(Collectors.toList());
  }

  private static void forEachSample(
      Connection c,
      String where,
      Binder binder,
      java.util.function.Function<Sample, List<Integer>> expected)
      throws Exception {
    List<Executable> failures = new ArrayList<>();
    for (Sample s : Dremio.SAMPLES) {
      List<Integer> act;
      try {
        act = ids(c, where, binder, s);
      } catch (Exception e) {
        failures.add(
            () -> {
              throw new AssertionError("[" + s.label() + "] " + e, e);
            });
        continue;
      }
      List<Integer> exp = expected.apply(s);
      failures.add(() -> assertEquals(exp, act, "[" + s.label() + "] " + s.ldt()));
    }
    assertAll(failures);
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setTimestamp(i, Timestamp.valueOf(wall-clock)) matches the same wall-clock")
  void setTimestampMatchesWallClock(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                "ts = ?",
                (ps, s) -> ps.setTimestamp(1, Timestamp.valueOf(s.ldt())),
                // Timestamp.valueOf may shift a non-existent local time (DST gap); the wall-clock that
                // is actually sent is whatever the Timestamp renders as in the JVM zone.
                s -> idsWithWallClock(Timestamp.valueOf(s.ldt()).toLocalDateTime())));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setTimestamp(i, ts, Calendar) sends the wall-clock of ts in the calendar's zone")
  void setTimestampWithCalendar(String zone) throws Exception {
    for (String calZone : List.of("UTC", "Europe/Madrid", "America/New_York", "Asia/Kolkata")) {
      TimeZone tz = TimeZone.getTimeZone(calZone);
      Dremio.inJvmZone(
          zone,
          c ->
              forEachSample(
                  c,
                  "ts = ?",
                  (ps, s) ->
                      ps.setTimestamp(
                          1,
                          new Timestamp(Dremio.wallClockMillis(s.ldt(), tz))
                              {{
                                setNanos(s.ldt().getNano());
                              }},
                          Calendar.getInstance(tz)),
                  s -> {
                    // the instant is built from the wall-clock in tz, so reading it back in tz must
                    // give the wall-clock again (modulo DST gaps, where tz normalises it)
                    Timestamp t = new Timestamp(Dremio.wallClockMillis(s.ldt(), tz));
                    LocalDateTime back =
                        LocalDateTime.ofInstant(t.toInstant(), tz.toZoneId())
                            .withNano(s.ldt().getNano());
                    return idsWithWallClock(back);
                  }));
    }
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setObject(i, LocalDateTime) matches the exact wall-clock")
  void setObjectLocalDateTime(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                "ts = ?",
                (ps, s) -> ps.setObject(1, s.ldt()),
                s -> idsWithWallClock(s.ldt())));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setObject(i, Timestamp) and setObject(i, Timestamp, Types.TIMESTAMP) agree with setTimestamp")
  void setObjectTimestamp(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c -> {
          forEachSample(
              c,
              "ts = ?",
              (ps, s) -> ps.setObject(1, Timestamp.valueOf(s.ldt())),
              s -> idsWithWallClock(Timestamp.valueOf(s.ldt()).toLocalDateTime()));
          forEachSample(
              c,
              "ts = ?",
              (ps, s) -> ps.setObject(1, Timestamp.valueOf(s.ldt()), Types.TIMESTAMP),
              s -> idsWithWallClock(Timestamp.valueOf(s.ldt()).toLocalDateTime()));
        });
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("Range predicate with two TIMESTAMP parameters: [lo, hi) selects the right rows")
  void betweenParameters(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c -> {
          Timestamp lo = Timestamp.valueOf("1970-01-01 00:00:00");
          Timestamp hi = Timestamp.valueOf("2024-03-10 12:34:56.789");
          List<Integer> expected =
              Dremio.SAMPLES.stream()
                  .filter(
                      o ->
                          !o.ldt().isBefore(lo.toLocalDateTime())
                              && o.ldt().isBefore(hi.toLocalDateTime()))
                  .map(Sample::id)
                  .collect(Collectors.toList());
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id FROM " + table + " WHERE ts >= ? AND ts < ? ORDER BY id")) {
            ps.setTimestamp(1, lo);
            ps.setTimestamp(2, hi);
            List<Integer> actual = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                actual.add(rs.getInt(1));
              }
            }
            assertEquals(expected, actual);
          }
        });
  }

  @Test
  @DisplayName("Re-executing a prepared statement with a different Timestamp picks up the new value")
  void rebind() throws Exception {
    Dremio.inJvmZone(
        "Europe/Madrid",
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT id FROM " + table + " WHERE ts = ? ORDER BY id")) {
            for (Sample s : Dremio.SAMPLES.subList(0, 6)) {
              ps.setTimestamp(1, Timestamp.valueOf(s.ldt()));
              List<Integer> actual = new ArrayList<>();
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  actual.add(rs.getInt(1));
                }
              }
              assertEquals(idsWithWallClock(s.ldt()), actual, s.label());
            }
          }
        });
  }

  @Test
  @DisplayName("Sub-millisecond nanos in a bound Timestamp are truncated to Dremio's millisecond precision")
  void nanosTruncated() throws Exception {
    Dremio.inJvmZone(
        "UTC",
        c -> {
          Timestamp t = Timestamp.valueOf("2024-03-10 12:34:56.789");
          t.setNanos(789_123_456);
          try (PreparedStatement ps =
              c.prepareStatement("SELECT id FROM " + table + " WHERE ts = ?")) {
            ps.setTimestamp(1, t);
            List<Integer> actual = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                actual.add(rs.getInt(1));
              }
            }
            assertEquals(idsWithWallClock(LocalDateTime.parse("2024-03-10T12:34:56.789")), actual);
          }
        });
  }

  @Test
  @DisplayName("setNull(i, Types.TIMESTAMP) executes and matches nothing")
  void setNullTimestamp() throws Exception {
    try (Connection c = Dremio.connectOrSkip();
        PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE ts = ?")) {
      ps.setNull(1, Types.TIMESTAMP);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(!rs.next());
      }
    }
  }

  @Test
  @DisplayName("ParameterMetaData reports TIMESTAMP for a timestamp comparison parameter")
  void parameterMetaData() throws Exception {
    try (Connection c = Dremio.connectOrSkip();
        PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE ts = ?")) {
      assertEquals(Types.TIMESTAMP, ps.getParameterMetaData().getParameterType(1));
    }
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setDate(i, Date.valueOf(d)) matches rows whose DATE column equals d")
  void setDateMatchesDateColumn(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                "d = ?",
                (ps, s) -> ps.setDate(1, Date.valueOf(s.ldt().toLocalDate())),
                s ->
                    Dremio.SAMPLES.stream()
                        .filter(o -> o.ldt().toLocalDate().equals(Date.valueOf(s.ldt().toLocalDate()).toLocalDate()))
                        .map(Sample::id)
                        .collect(Collectors.toList())));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("setTime(i, Time.valueOf(t)) matches rows whose TIME column equals t (second precision)")
  void setTimeMatchesTimeColumn(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                "t = ?",
                (ps, s) -> ps.setTime(1, Time.valueOf(s.ldt().toLocalTime().withNano(0))),
                s ->
                    Dremio.SAMPLES.stream()
                        .filter(o -> o.ldt().toLocalTime().equals(s.ldt().toLocalTime().withNano(0)))
                        .map(Sample::id)
                        .collect(Collectors.toList())));
  }
}
