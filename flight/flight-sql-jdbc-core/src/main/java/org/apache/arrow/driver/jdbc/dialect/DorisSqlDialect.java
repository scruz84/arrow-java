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

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.HexFormat;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;

/**
 * The dialect of Apache Doris.
 *
 * <p>Doris follows MySQL: backticks delimit identifiers and backslashes escape characters in
 * strings.
 *
 * <p>Where Doris differs from ANSI SQL the values are written in these forms:
 *
 * <ul>
 *   <li>There is no {@code N'...'} form: all text is Unicode and is written as an ordinary literal.
 *   <li>There is no {@code TIME} type or literal, so a time of day is written as a string, which
 *       the server converts where a time is expected. A time with an offset is rejected.
 *   <li>There is no {@code TIMESTAMP WITH TIME ZONE} literal. A value with an offset is written as
 *       {@code CAST('2024-01-31 10:15:30+02:00' AS DATETIME(6))}, which Doris converts to the time
 *       zone of the session. A {@code DATETIME} has no zone, and microsecond precision at most.
 *   <li>A binary value is written as {@code UNHEX('0A1B')}.
 *   <li>NaN and the infinities are written as {@code CAST('NaN' AS DOUBLE)}.
 *   <li>An interval is written as {@code INTERVAL n MONTH} or {@code INTERVAL n SECOND}, the forms
 *       that date arithmetic takes. Doris has no compound intervals.
 *   <li>An array is {@code ARRAY(1, 2)} and a row is {@code STRUCT(1, 'a')}.
 * </ul>
 */
public class DorisSqlDialect implements SqlDialect {
  /** The name of this dialect, as given to the {@code dialect} connection property. */
  public static final String NAME = "doris";

  private static final SqlLexicalRules LEXICAL_RULES =
      SqlLexicalRules.builder().backtickIdentifiers(true).backslashEscapesInStrings(true).build();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean groupsBatchInserts() {
    return true;
  }

  @Override
  public SqlLexicalRules lexicalRules() {
    return LEXICAL_RULES;
  }

  @Override
  public String formatString(String s, boolean national) throws SQLException {
    return SqlDialect.super.formatString(s, false);
  }

  @Override
  public String formatDouble(double d) throws SQLException {
    if (Double.isNaN(d)) {
      return "CAST('NaN' AS DOUBLE)";
    } else if (Double.isInfinite(d)) {
      return "CAST('" + (d > 0 ? "" : "-") + "Infinity' AS DOUBLE)";
    }
    return SqlDialect.super.formatDouble(d);
  }

  @Override
  public String formatBinary(byte[] b) throws SQLException {
    // X'..' is read as text by Doris, which has no binary type for it to fill.
    return "UNHEX('" + HexFormat.of().withUpperCase().formatHex(b) + "')";
  }

  @Override
  public String formatLocalTime(LocalTime t) throws SQLException {
    return "'" + SqlLiterals.timeText(t) + "'";
  }

  @Override
  public String formatOffsetTime(OffsetTime t) throws SQLException {
    throw new SQLFeatureNotSupportedException("Doris has no time of day with a time zone");
  }

  @Override
  public String formatOffsetDateTime(OffsetDateTime t) throws SQLException {
    final OffsetDateTime whole = SqlLiterals.withWholeMinuteOffset(t);
    return "CAST('"
        + SqlLiterals.dateText(whole.toLocalDate())
        + " "
        + SqlLiterals.timeText(whole.toLocalTime())
        + SqlLiterals.offsetText(whole.getOffset())
        + "' AS DATETIME(6))";
  }

  @Override
  public String formatIntervalYearMonth(int totalMonths) throws SQLException {
    return "INTERVAL " + totalMonths + " MONTH";
  }

  @Override
  public String formatIntervalDayTime(long days, long nanos) throws SQLException {
    if (nanos % 1_000_000_000L != 0) {
      throw new SQLFeatureNotSupportedException("Doris intervals have no fractions of a second");
    }
    final long seconds;
    try {
      seconds = Math.addExact(Math.multiplyExact(days, 86_400L), nanos / 1_000_000_000L);
    } catch (ArithmeticException e) {
      throw new SQLFeatureNotSupportedException("Cannot render an interval out of range", e);
    }
    return "INTERVAL " + seconds + " SECOND";
  }

  @Override
  public String formatArray(SqlArray a) throws SQLException {
    final StringBuilder sb = new StringBuilder("ARRAY(");
    for (int i = 0; i < a.elements().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(render(a.elements().get(i)));
    }
    return sb.append(')').toString();
  }

  @Override
  public String formatRow(SqlRow r) throws SQLException {
    if (r.fields().isEmpty()) {
      throw new SQLFeatureNotSupportedException("Cannot render a row without fields");
    }
    final StringBuilder sb = new StringBuilder("STRUCT(");
    for (int i = 0; i < r.fields().size(); i++) {
      final SqlRowField field = r.fields().get(i);
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(render(field.value()));
    }
    return sb.append(')').toString();
  }
}
