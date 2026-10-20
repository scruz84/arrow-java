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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** DATE and TIME columns (not TIMESTAMP) under different JVM default zones. */
class DateAndTimeColumnTest {

  private static final List<String> DATES =
      List.of(
          "1970-01-01",
          "1969-12-31",
          "2000-02-29",
          "2024-03-10",
          "2024-03-31",
          "2024-10-27",
          "1900-01-01",
          "1582-10-15",
          "0001-01-01",
          "9999-12-31");

  private static final List<String> TIMES =
      List.of("00:00:00", "00:00:00.001", "12:34:56", "12:34:56.789", "23:59:59.999");

  static List<String> zones() {
    return Dremio.JVM_ZONES;
  }

  private static String sql(String type, List<String> literals) {
    StringBuilder sb = new StringBuilder("SELECT id, v FROM (VALUES ");
    for (int i = 0; i < literals.size(); i++) {
      sb.append(i == 0 ? "" : ", ")
          .append("(")
          .append(i + 1)
          .append(", ")
          .append(type)
          .append(" '")
          .append(literals.get(i))
          .append("')");
    }
    return sb.append(") AS t(id, v) ORDER BY id").toString();
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("DATE: getDate / getObject(LocalDate) / getString return the calendar date")
  void dateColumn(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        c -> {
          List<org.junit.jupiter.api.function.Executable> failures = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(sql("DATE", DATES))) {
            for (String lit : DATES) {
              rs.next();
              LocalDate expected = LocalDate.parse(lit);
              failures.add(
                  () -> assertEquals(expected, rs.getDate(2).toLocalDate(), "getDate " + lit));
            }
          }
          // Re-run for the other getters (cursor based, so a fresh result set).
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(sql("DATE", DATES))) {
            for (String lit : DATES) {
              rs.next();
              LocalDate expected = LocalDate.parse(lit);
              LocalDate viaObject = null;
              String viaString = rs.getString(2);
              Timestamp viaTimestamp = rs.getTimestamp(2);
              try {
                viaObject = rs.getObject(2, LocalDate.class);
              } catch (Exception e) {
                final Exception ex = e;
                failures.add(
                    () -> {
                      throw new AssertionError("getObject(LocalDate) " + lit + ": " + ex, ex);
                    });
              }
              final LocalDate vo = viaObject;
              failures.add(() -> assertEquals(expected, vo, "getObject(LocalDate) " + lit));
              failures.add(() -> assertEquals(lit, viaString, "getString " + lit));
              failures.add(
                  () ->
                      assertEquals(
                          Timestamp.valueOf(expected.atStartOfDay()),
                          viaTimestamp,
                          "getTimestamp(DATE) = midnight in JVM zone " + lit));
            }
          }
          assertAll(failures);
        });
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("TIME: getTime / getObject(LocalTime) / getString return the wall-clock time")
  void timeColumn(String zone) throws Exception {
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    Dremio.inJvmZone(
        zone,
        c -> {
          List<org.junit.jupiter.api.function.Executable> failures = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(sql("TIME", TIMES))) {
            for (String lit : TIMES) {
              rs.next();
              LocalTime expected = LocalTime.parse(lit);
              Time t = rs.getTime(2);
              failures.add(
                  () ->
                      assertEquals(
                          expected.withNano(0), t.toLocalTime(), "getTime wall-clock " + lit));
              failures.add(
                  () ->
                      assertEquals(
                          expected.getNano() / 1_000_000,
                          Math.floorMod(t.getTime(), 1000L),
                          "getTime millis " + lit));
              String str = rs.getString(2);
              failures.add(
                  () -> assertEquals(fmt.format(expected), str, "getString " + lit));
              LocalTime viaObject;
              try {
                viaObject = rs.getObject(2, LocalTime.class);
              } catch (Exception e) {
                viaObject = null;
              }
              final LocalTime vo = viaObject;
              failures.add(() -> assertEquals(expected, vo, "getObject(LocalTime) " + lit));
            }
          }
          assertAll(failures);
        });
  }
}
