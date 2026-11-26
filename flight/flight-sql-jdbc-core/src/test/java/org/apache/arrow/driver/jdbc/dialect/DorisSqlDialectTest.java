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
package org.apache.arrow.driver.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.junit.jupiter.api.Test;

public class DorisSqlDialectTest {
  private final SqlDialect dialect = new DorisSqlDialect();

  @Test
  public void testBackslashesInStringsAreEscaped() throws SQLException {
    assertEquals(
        "'it''s \\\\ ok'", dialect.render(new SqlScalar("it's \\ ok", SqlType.of(Types.VARCHAR))));
  }

  @Test
  public void testBacktickIdentifiersHideTheirQuestionMarks() {
    final ScannedSql scanned =
        ScannedSql.scan("select `a?` from t where b = ?", dialect.lexicalRules());
    assertEquals(1, scanned.parameterCount());
  }

  @Test
  public void testStatementsAreClassifiedByTheirKeyword() {
    assertEquals(StatementKind.UPDATE, dialect.classify("CREATE TABLE t (a INT)"));
    assertEquals(StatementKind.QUERY, dialect.classify("select 1"));
  }

  private String render(Object javaValue, int jdbcType) throws SQLException {
    return dialect.render(new SqlScalar(javaValue, SqlType.of(jdbcType)));
  }

  @Test
  public void testValuesDorisDoesNotReadInTheAnsiFormAreRewritten() throws SQLException {
    assertEquals("'ñ'", dialect.formatString("ñ", true));
    assertEquals("'10:15:30.5'", render(LocalTime.of(10, 15, 30, 500_000_000), Types.TIME));
    assertEquals(
        "CAST('2024-01-31 10:15:30.123456789+02:00' AS DATETIME(6))",
        render(
            OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 123_456_789, ZoneOffset.ofHours(2)),
            Types.TIMESTAMP_WITH_TIMEZONE));
    assertEquals(
        "CAST('2024-01-31 10:15:30-03:30' AS DATETIME(6))",
        render(
            OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(-3, -30)),
            Types.TIMESTAMP_WITH_TIMEZONE));
    assertEquals("UNHEX('0A1B')", render(new byte[] {0x0A, 0x1B}, Types.VARBINARY));
    assertEquals("CAST('NaN' AS DOUBLE)", dialect.formatDouble(Double.NaN));
    assertEquals("CAST('-Infinity' AS DOUBLE)", dialect.formatDouble(Double.NEGATIVE_INFINITY));
    assertEquals("INTERVAL 14 MONTH", dialect.formatIntervalYearMonth(14));
    assertEquals(
        "INTERVAL 90061 SECOND", dialect.formatIntervalDayTime(1, 3_661L * 1_000_000_000L));
  }

  @Test
  public void testValuesDorisCannotRepresentAreRejected() {
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> dialect.formatOffsetTime(OffsetTime.of(1, 2, 3, 0, ZoneOffset.UTC)));
    assertThrows(SQLFeatureNotSupportedException.class, () -> dialect.formatIntervalDayTime(0, 1));
  }

  @Test
  public void testArraysAndRows() throws SQLException {
    final SqlType integer = SqlType.of(Types.INTEGER);
    assertEquals(
        "ARRAY(1, (-2), NULL)",
        dialect.render(
            new SqlArray(
                integer,
                List.of(
                    new SqlScalar(1, integer), new SqlScalar(-2, integer), new SqlNull(integer)))));
    assertEquals("ARRAY()", dialect.render(new SqlArray(integer, List.of())));
    assertEquals(
        "STRUCT(1, 'a')",
        dialect.render(
            new SqlRow(
                null,
                List.of(
                    new SqlRowField("a", new SqlScalar(1, integer)),
                    new SqlRowField("b", new SqlScalar("a", SqlType.of(Types.VARCHAR)))))));
  }
}
