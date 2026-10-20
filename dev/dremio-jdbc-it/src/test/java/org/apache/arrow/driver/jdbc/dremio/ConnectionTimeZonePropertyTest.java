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

import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterisation of the connection-level {@code timeZone} property (an Avatica setting). Forcing
 * it to UTC changes how the driver converts zone-less TIMESTAMP values, so these tests document
 * whether it is a usable workaround.
 */
class ConnectionTimeZonePropertyTest {

  static List<String> zones() {
    return Dremio.JVM_ZONES;
  }

  private static Properties utc() {
    Properties p = new Properties();
    p.setProperty("timeZone", "UTC");
    return p;
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("timeZone=UTC: getTimestamp(i) is the wall-clock value in the JVM zone")
  void getTimestampWithUtcConnectionZone(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        utc(),
        c -> {
          List<org.junit.jupiter.api.function.Executable> failures = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(Dremio.valuesSql(Dremio.SAMPLES) + " ORDER BY id")) {
            for (Dremio.Sample sample : Dremio.SAMPLES) {
              rs.next();
              Timestamp actual = rs.getTimestamp(2);
              failures.add(
                  () ->
                      assertTrue(
                          Dremio.acceptableMillis(sample.ldt(), TimeZone.getDefault())
                              .contains(actual.getTime()),
                          "["
                              + sample.label()
                              + "] "
                              + actual
                              + " != wall-clock "
                              + sample.ldt()));
            }
          }
          assertAll(failures);
        });
  }

  @ParameterizedTest(name = "JVM zone {0}")
  @MethodSource("zones")
  @DisplayName("timeZone=UTC does not change getObject(LocalDateTime) / getString")
  void exactAccessorsUnaffected(String zone) throws Exception {
    Dremio.inJvmZone(
        zone,
        utc(),
        c -> {
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery(Dremio.valuesSql(Dremio.SAMPLES) + " ORDER BY id")) {
            for (Dremio.Sample sample : Dremio.SAMPLES) {
              rs.next();
              assertEquals(sample.ldt(), rs.getObject(2, java.time.LocalDateTime.class));
            }
          }
        });
  }
}
