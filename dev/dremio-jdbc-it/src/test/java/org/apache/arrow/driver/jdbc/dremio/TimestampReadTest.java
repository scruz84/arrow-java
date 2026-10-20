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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.apache.arrow.driver.jdbc.dremio.Dremio.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads Dremio TIMESTAMP values (literals produced by the server, no storage involved) through every
 * JDBC getter, with the JVM default time zone switched across several zones.
 *
 * <p>Dremio's TIMESTAMP is a zone-less wall-clock value, so the JDBC contract is:
 *
 * <ul>
 *   <li>{@code getTimestamp(i)} = that wall-clock in the JVM default zone
 *   <li>{@code getTimestamp(i, cal)} = that wall-clock in {@code cal}'s zone
 *   <li>{@code getObject(i, LocalDateTime.class)} = the wall-clock itself, independent of any zone
 * </ul>
 */
class TimestampReadTest {

  private static String sql() {
    return Dremio.valuesSql(Dremio.SAMPLES) + " ORDER BY id";
  }

  /** Calls {@code check} for every row; failures are reported together, one per sample. */
  private interface RowCheck {
    void check(ResultSet rs, Sample sample) throws Exception;
  }

  private static void forEachSample(Connection c, RowCheck check) throws Exception {
    List<org.junit.jupiter.api.function.Executable> asserts = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql())) {
      for (Sample sample : Dremio.SAMPLES) {
        assertTrue(rs.next(), "missing row for " + sample);
        assertEquals(sample.id(), rs.getInt(1), "row order");
        // Read eagerly while the cursor is on the row, but defer failure reporting.
        try {
          check.check(rs, sample);
        } catch (Throwable t) {
          asserts.add(
              () -> {
                throw new AssertionError(
                    "[" + sample.label() + " | " + sample.ldt() + "] " + t.getMessage(), t);
              });
        }
      }
      assertFalse(rs.next(), "unexpected extra rows");
    }
    assertAll(asserts);
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(
      strings = {
        "UTC",
        "Europe/Madrid",
        "America/New_York",
        "Asia/Kolkata",
        "Pacific/Auckland",
        "America/Sao_Paulo"
      })
  @DisplayName("getTimestamp(i) is the wall-clock value in the JVM default zone")
  void getTimestampUsesJvmZone(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) -> {
                  Timestamp expected = Timestamp.valueOf(s.ldt());
                  assertEquals(expected, rs.getTimestamp(2), "getTimestamp(2)");
                  assertTrue(
                      Dremio.acceptableMillis(s.ldt(), TimeZone.getDefault())
                          .contains(rs.getTimestamp(2).getTime()),
                      "epoch millis " + rs.getTimestamp(2).getTime() + " vs oracle");
                  assertEquals(expected, rs.getTimestamp("ts"), "getTimestamp(label)");
                }));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(
      strings = {
        "UTC",
        "Europe/Madrid",
        "America/New_York",
        "Asia/Kolkata",
        "Pacific/Auckland",
        "America/Sao_Paulo"
      })
  @DisplayName("getObject(i) returns java.sql.Timestamp equal to getTimestamp(i)")
  void getObjectIsTimestamp(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) -> {
                  Object o = rs.getObject(2);
                  assertInstanceOf(Timestamp.class, o);
                  assertEquals(rs.getTimestamp(2), o);
                  assertEquals(Timestamp.valueOf(s.ldt()), o);
                  assertEquals(o, rs.getObject("ts"));
                }));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(
      strings = {
        "UTC",
        "Europe/Madrid",
        "America/New_York",
        "Asia/Kolkata",
        "Pacific/Auckland",
        "America/Sao_Paulo"
      })
  @DisplayName("getObject(i, LocalDateTime.class) is the exact wall-clock, zone independent")
  void getLocalDateTimeIsExact(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) -> assertEquals(s.ldt(), rs.getObject(2, LocalDateTime.class))));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(
      strings = {
        "UTC",
        "Europe/Madrid",
        "America/New_York",
        "Asia/Kolkata",
        "Pacific/Auckland",
        "America/Sao_Paulo"
      })
  @DisplayName("getString(i) matches Timestamp.toString() of the JVM-zone value")
  void getStringMatchesTimestampToString(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) ->
                    assertEquals(Timestamp.valueOf(s.ldt()).toString(), rs.getString(2))));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(
      strings = {
        "UTC",
        "Europe/Madrid",
        "America/New_York",
        "Asia/Kolkata",
        "Pacific/Auckland",
        "America/Sao_Paulo"
      })
  @DisplayName("getDate / getTime on a TIMESTAMP keep the calendar date / wall-clock time")
  void getDateAndTimeFromTimestamp(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) -> {
                  Date d = rs.getDate(2);
                  assertEquals(s.ldt().toLocalDate(), d.toLocalDate(), "getDate");
                  Time t = rs.getTime(2);
                  assertEquals(s.ldt().toLocalTime().withNano(0), t.toLocalTime(), "getTime");
                  assertEquals(
                      s.ldt().getNano() / 1_000_000,
                      Math.floorMod(t.getTime(), 1000L),
                      "getTime millis");
                }));
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> jvmZoneByCalendarZone() {
    List<String> calendars =
        List.of("UTC", "Europe/Madrid", "America/New_York", "Asia/Kolkata", "Pacific/Auckland");
    return Dremio.JVM_ZONES.stream()
        .flatMap(
            jvm ->
                calendars.stream().map(cal -> org.junit.jupiter.params.provider.Arguments.of(jvm, cal)));
  }

  @ParameterizedTest(name = "JVM zone {0}, Calendar zone {1}")
  @MethodSource("jvmZoneByCalendarZone")
  @DisplayName("getTimestamp(i, Calendar) is the wall-clock value interpreted in the calendar zone")
  void getTimestampWithCalendar(String jvmZone, String calZone) throws Exception {
    Dremio.inJvmZone(
        jvmZone,
        c ->
            forEachSample(
                c,
                (rs, s) -> {
                  TimeZone tz = TimeZone.getTimeZone(calZone);
                  Calendar cal = Calendar.getInstance(tz);
                  Timestamp actual = rs.getTimestamp(2, cal);
                  assertTrue(
                      Dremio.acceptableMillis(s.ldt(), tz).contains(actual.getTime()),
                      "epoch millis "
                          + actual.getTime()
                          + " for "
                          + calZone
                          + " not in "
                          + Dremio.acceptableMillis(s.ldt(), tz));
                  assertEquals(
                      s.ldt().getNano() / 1_000_000,
                      actual.getNanos() / 1_000_000,
                      "fractional millis");
                }));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(strings = {"UTC", "Europe/Madrid", "America/New_York", "Asia/Kolkata"})
  @DisplayName("getTimestamp(i, Calendar-in-JVM-zone) equals getTimestamp(i)")
  void calendarInJvmZoneEqualsPlainGetter(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) ->
                    assertEquals(
                        rs.getTimestamp(2),
                        rs.getTimestamp(2, Calendar.getInstance()),
                        "JDBC: a calendar for the default zone must be a no-op")));
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @ValueSource(strings = {"UTC", "Europe/Madrid", "America/New_York"})
  @DisplayName("getDate / getTime with Calendar interpret the value in the calendar zone")
  void getDateAndTimeWithCalendar(String zone) throws Exception {
    TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
    Dremio.inJvmZone(
        zone,
        c ->
            forEachSample(
                c,
                (rs, s) -> {
                  Calendar cal = Calendar.getInstance(tokyo);
                  // Date/Time are formatted by the receiver in its own zone; compare in Tokyo.
                  java.text.SimpleDateFormat dfDate = new java.text.SimpleDateFormat("yyyy-MM-dd");
                  dfDate.setTimeZone(tokyo);
                  java.text.SimpleDateFormat dfTime = new java.text.SimpleDateFormat("HH:mm:ss");
                  dfTime.setTimeZone(tokyo);
                  assertEquals(
                      s.ldt().toLocalDate().toString(),
                      dfDate.format(rs.getDate(2, cal)),
                      "getDate(cal) in Tokyo");
                  assertEquals(
                      s.ldt().toLocalTime().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                      dfTime.format(rs.getTime(2, cal)),
                      "getTime(cal) in Tokyo");
                }));
  }

  @Test
  @DisplayName("NULL timestamps: getters return null and wasNull() is true")
  void nullHandling() throws Exception {
    try (Connection c = Dremio.connectOrSkip();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT CAST(NULL AS TIMESTAMP) AS n, TIMESTAMP '2024-03-10 12:34:56.789' AS v")) {
      assertTrue(rs.next());
      assertNull(rs.getTimestamp(1));
      assertTrue(rs.wasNull(), "wasNull after getTimestamp");
      assertNull(rs.getObject(1));
      assertTrue(rs.wasNull(), "wasNull after getObject");
      assertNull(rs.getObject(1, LocalDateTime.class));
      assertTrue(rs.wasNull(), "wasNull after getObject(LocalDateTime)");
      assertNull(rs.getString(1));
      assertTrue(rs.wasNull(), "wasNull after getString");
      assertNull(rs.getDate(1));
      assertNull(rs.getTime(1));
      assertNull(rs.getTimestamp(1, Calendar.getInstance()));
      assertNotNullThenWasNullFalse(rs);
    }
  }

  private static void assertNotNullThenWasNullFalse(ResultSet rs) throws SQLException {
    assertTrue(rs.getTimestamp(2) != null);
    assertFalse(rs.wasNull(), "wasNull must reset after a non-null read");
  }

  @Test
  @DisplayName("Zone-less TIMESTAMP cannot be read as Instant/OffsetDateTime/ZonedDateTime")
  void zonedTypesRejected() throws Exception {
    try (Connection c = Dremio.connectOrSkip();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT TIMESTAMP '2024-03-10 12:34:56.789' AS v")) {
      assertTrue(rs.next());
      assertThrows(SQLException.class, () -> rs.getObject(1, Instant.class));
      assertThrows(SQLException.class, () -> rs.getObject(1, OffsetDateTime.class));
      assertThrows(SQLException.class, () -> rs.getObject(1, ZonedDateTime.class));
    }
  }

  @Test
  @DisplayName("Sub-millisecond precision never leaks into the returned Timestamp")
  void nanosAreMillisecondAligned() throws Exception {
    try (Connection c = Dremio.connectOrSkip();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql())) {
      while (rs.next()) {
        assertEquals(0, rs.getTimestamp(2).getNanos() % 1_000_000, "nanos of row " + rs.getInt(1));
      }
    }
  }
}
