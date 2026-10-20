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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared plumbing for the Dremio integration tests: connection handling, JVM time-zone switching and
 * a catalogue of "interesting" wall-clock timestamps.
 *
 * <p>Connection settings can be overridden with {@code -Ddremio.url=...}, {@code -Ddremio.user=...}
 * and {@code -Ddremio.password=...}.
 */
final class Dremio {

  static final String URL =
      System.getProperty(
          "dremio.url", "jdbc:arrow-flight-sql://localhost:32010/?useEncryption=false");
  static final String USER = System.getProperty("dremio.user", "dremio");
  static final String PASSWORD = System.getProperty("dremio.password", "dremio123");

  /** JVM default time zones the read/write tests are repeated under. */
  static final List<String> JVM_ZONES =
      List.of(
          "UTC",
          "Europe/Madrid",
          "America/New_York",
          "Asia/Kolkata",
          "Pacific/Auckland",
          "America/Sao_Paulo");

  private static final DateTimeFormatter SQL_TS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

  private Dremio() {}

  /** A wall-clock timestamp together with a human readable reason why it is interesting. */
  record Sample(int id, String label, LocalDateTime ldt) {
    String sqlLiteral() {
      return "TIMESTAMP '" + SQL_TS.format(ldt) + "'";
    }
  }

  /** Wall-clock values every test re-uses. Dremio TIMESTAMP is a zone-less, millisecond value. */
  static final List<Sample> SAMPLES = buildSamples();

  private static List<Sample> buildSamples() {
    List<Sample> l = new ArrayList<>();
    add(l, "epoch", "1970-01-01T00:00:00");
    add(l, "epoch - 1 ms", "1969-12-31T23:59:59.999");
    add(l, "epoch + 1 ms", "1970-01-01T00:00:00.001");
    add(l, "year 2000", "2000-01-01T00:00:00");
    add(l, "now-ish with millis", "2024-03-10T12:34:56.789");
    add(l, "ms=001", "2024-03-10T12:34:56.001");
    add(l, "ms=010", "2024-03-10T12:34:56.010");
    add(l, "ms=100", "2024-03-10T12:34:56.100");
    add(l, "whole second", "2024-03-10T12:34:56");
    add(l, "midnight", "2024-06-15T00:00:00");
    add(l, "last ms of day", "2024-06-15T23:59:59.999");
    add(l, "leap day", "2024-02-29T23:59:59.999");
    add(l, "year 2038 (int32 limit)", "2038-01-19T03:14:07");
    add(l, "year 2038 + 1s", "2038-01-19T03:14:08");
    add(l, "pre-1970", "1969-07-20T20:17:40.123");
    add(l, "1900-01-01", "1900-01-01T00:00:00");
    add(l, "1900 mid-year", "1900-06-15T12:34:56.789");
    add(l, "Gregorian cutover - 1", "1582-10-04T12:00:00");
    add(l, "Gregorian cutover + 1", "1582-10-15T12:00:00");
    add(l, "year 1000", "1000-01-01T00:00:00");
    add(l, "year 0001", "0001-01-01T00:00:00");
    add(l, "year 9999", "9999-12-31T23:59:59.999");
    // Wall-clock values that do not exist / are ambiguous in some of the JVM zones above.
    add(l, "Madrid spring-forward gap", "2024-03-31T02:30:00");
    add(l, "Madrid fall-back overlap", "2024-10-27T02:30:00");
    add(l, "New York spring-forward gap", "2024-03-10T02:30:00");
    add(l, "New York fall-back overlap", "2024-11-03T01:30:00");
    add(l, "Auckland spring-forward gap", "2024-09-29T02:30:00");
    add(l, "Sao Paulo midnight gap (2018)", "2018-11-04T00:30:00");
    int id = 1;
    List<Sample> out = new ArrayList<>();
    for (Sample s : l) {
      out.add(new Sample(id++, s.label(), s.ldt()));
    }
    return List.copyOf(out);
  }

  private static void add(List<Sample> l, String label, String iso) {
    l.add(new Sample(0, label, LocalDateTime.parse(iso)));
  }

  /** {@code SELECT id, ts FROM (VALUES ...)} over the given samples (unordered). */
  static String valuesSql(List<Sample> samples) {
    return "SELECT id, ts FROM (VALUES "
        + samples.stream()
            .map(s -> "(" + s.id() + ", " + s.sqlLiteral() + ")")
            .collect(Collectors.joining(", "))
        + ") AS t(id, ts)";
  }

  static Connection connect() throws SQLException {
    return connect(new Properties());
  }

  static Connection connect(Properties extra) throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", USER);
    p.setProperty("password", PASSWORD);
    p.putAll(extra);
    return DriverManager.getConnection(URL, p);
  }

  /** Skips the calling test class when the Dremio server cannot be reached. */
  static Connection connectOrSkip() {
    try {
      return connect();
    } catch (SQLException e) {
      assumeTrue(false, "Dremio not reachable at " + URL + ": " + e.getMessage());
      return null;
    }
  }

  @FunctionalInterface
  interface ConnBody {
    void run(Connection c) throws Exception;
  }

  /**
   * Runs {@code body} with the JVM default time zone switched to {@code zone} and a connection that
   * was opened <em>after</em> the switch (Avatica may capture the zone at connection time).
   */
  static void inJvmZone(String zone, ConnBody body) throws Exception {
    inJvmZone(zone, new Properties(), body);
  }

  static void inJvmZone(String zone, Properties connectionProps, ConnBody body) throws Exception {
    TimeZone previous = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(zone));
    try (Connection c = connect(connectionProps)) {
      body.run(c);
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  /** Runs a statement and drains its result: Flight SQL only completes DDL once the stream is read. */
  static void exec(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      if (s.execute(sql)) {
        try (ResultSet rs = s.getResultSet()) {
          while (rs.next()) {
            // drain
          }
        }
      }
    }
  }

  /** Creates a unique Parquet table under {@code $scratch} and returns its quoted full name. */
  static String createScratchTable(Connection c, String prefix, String selectSql)
      throws SQLException {
    String name =
        "\"$scratch\".\"" + prefix + "_" + UUID.randomUUID().toString().replace("-", "") + "\"";
    exec(c, "CREATE TABLE " + name + " AS " + selectSql);
    return name;
  }

  static void dropQuietly(Connection c, String table) {
    try {
      exec(c, "DROP TABLE IF EXISTS " + table);
    } catch (SQLException ignored) {
      // best effort cleanup
    }
  }

  /**
   * The JDBC-spec meaning of "this wall-clock value in that zone", expressed as epoch millis. Uses
   * {@link GregorianCalendar} (hybrid Julian/Gregorian, like {@link java.sql.Timestamp}) so that it
   * agrees with what {@code Timestamp.valueOf(LocalDateTime)} produces for the default zone.
   */
  static long wallClockMillis(LocalDateTime ldt, TimeZone zone) {
    Calendar c = new GregorianCalendar(zone);
    c.clear();
    c.set(
        ldt.getYear(),
        ldt.getMonthValue() - 1,
        ldt.getDayOfMonth(),
        ldt.getHour(),
        ldt.getMinute(),
        ldt.getSecond());
    c.set(Calendar.MILLISECOND, ldt.getNano() / 1_000_000);
    return c.getTimeInMillis();
  }

  /**
   * Every epoch-millis value that is a defensible reading of "this wall-clock value in that zone".
   * Normally there is exactly one; around DST transitions the wall-clock may be ambiguous (two
   * offsets) or non-existent (neither offset is valid, JDBC implementations resolve it either way),
   * and before standardised offsets (local mean time) {@link TimeZone} and {@code java.time}
   * disagree, so both are accepted.
   */
  static Set<Long> acceptableMillis(LocalDateTime ldt, TimeZone zone) {
    Set<Long> out = new LinkedHashSet<>();
    out.add(wallClockMillis(ldt, zone));
    long asUtc = wallClockMillis(ldt, TimeZone.getTimeZone("UTC"));
    ZoneRules rules = zone.toZoneId().getRules();
    List<ZoneOffset> offsets = new ArrayList<>(rules.getValidOffsets(ldt));
    if (offsets.isEmpty()) {
      ZoneOffsetTransition t = rules.getTransition(ldt);
      offsets.add(t.getOffsetBefore());
      offsets.add(t.getOffsetAfter());
    }
    for (ZoneOffset o : offsets) {
      out.add(asUtc - o.getTotalSeconds() * 1000L);
    }
    return out;
  }

  static List<LocalDateTime> readLocalDateTimes(Connection c, String sql, int column)
      throws SQLException {
    List<LocalDateTime> out = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getObject(column, LocalDateTime.class));
      }
    }
    return out;
  }

  /** Single-value convenience: first column of first row as {@link LocalDateTime}. */
  static LocalDateTime scalarLdt(Connection c, String sql) throws SQLException {
    List<LocalDateTime> r = readLocalDateTimes(c, sql, 1);
    if (r.size() != 1) {
      throw new AssertionError("expected 1 row, got " + r.size() + " for: " + sql);
    }
    return r.get(0);
  }

  static Object scalar(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      if (!rs.next()) {
        throw new AssertionError("no rows for: " + sql);
      }
      return rs.getObject(1);
    }
  }
}
