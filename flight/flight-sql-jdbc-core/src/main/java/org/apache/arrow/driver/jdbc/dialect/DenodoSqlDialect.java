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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlIntervalValue;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;

/**
 * The dialect of Denodo Virtual DataPort (VQL).
 *
 * <p>Where VQL follows ANSI SQL this class inherits the ANSI literals: strings, booleans, numbers,
 * {@code DATE}, {@code TIME}, {@code TIMESTAMP}, {@code TIMESTAMP WITH TIME ZONE} and {@code
 * INTERVAL} values. It differs in these ways:
 *
 * <ul>
 *   <li>Backticks delimit identifiers and {@code //} starts a line comment, besides {@code --} and
 *       {@code /* *}{@code /}. Backslashes are not escapes.
 *   <li>There is no {@code N'...'} form: all text is Unicode and is written as an ordinary literal.
 *   <li>There is no {@code X'...'} form. A binary value is written as a string of hexadecimal
 *       digits, which the server turns into bytes when the target is a blob.
 *   <li>NaN and the infinities are written as {@code CAST('NaN' AS DOUBLE PRECISION)}.
 *   <li>A null is always a plain {@code NULL}: a type name that came from another database could
 *       not be trusted in a {@code CAST}.
 *   <li>A row is {@code ROW(1, 'a')}. An array is an array of rows, {@code {ROW(1), ROW(2)}}: a
 *       scalar element is wrapped in a one-field row, and a row element is the row itself. An empty
 *       array is {@code {}}.
 *   <li>Inside {@code ROW(...)} the grammar only takes plain literals, so dates, times, intervals
 *       and non-finite numbers are written as text that the type of the field converts, and a
 *       negative number has no parentheses.
 * </ul>
 */
public class DenodoSqlDialect implements SqlDialect {
  /** The name of this dialect, as given to the {@code dialect} connection property. */
  public static final String NAME = "denodo";

  private static final SqlLexicalRules LEXICAL_RULES =
      SqlLexicalRules.builder().backtickIdentifiers(true).doubleSlashComments(true).build();

  private static final HexFormat HEX = HexFormat.of();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public SqlLexicalRules lexicalRules() {
    return LEXICAL_RULES;
  }

  @Override
  public String formatNull(SqlType type) throws SQLException {
    return "NULL";
  }

  @Override
  public String formatString(String s, boolean national) throws SQLException {
    return SqlDialect.super.formatString(s, false);
  }

  /** A string of hexadecimal digits, which the server converts when the target is a blob. */
  @Override
  public String formatBinary(byte[] b) throws SQLException {
    return "'" + HEX.formatHex(b) + "'";
  }

  @Override
  public String formatDouble(double d) throws SQLException {
    if (Double.isNaN(d)) {
      return "CAST('NaN' AS DOUBLE PRECISION)";
    } else if (Double.isInfinite(d)) {
      return "CAST('" + (d > 0 ? "Infinity" : "-Infinity") + "' AS DOUBLE PRECISION)";
    }
    return SqlDialect.super.formatDouble(d);
  }

  /**
   * An array of rows: {@code {ROW(1), ROW(2)}}. An element that is a row is written as it is, so
   * that an array of rows is {@code {ROW(1, 'a'), ROW(2, 'b')}}.
   *
   * <p>A null element is a row of nulls, as the server rejects a bare {@code NULL} there. It has as
   * many fields as the rows around it. An array of arrays cannot hold null elements.
   */
  @Override
  public String formatArray(SqlArray a) throws SQLException {
    final StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < a.elements().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(arrayElement(a, a.elements().get(i)));
    }
    return sb.append('}').toString();
  }

  /** A row: {@code ROW(1, 'a')}. Field names are not part of row values. */
  @Override
  public String formatRow(SqlRow r) throws SQLException {
    if (r.fields().isEmpty()) {
      throw new SQLFeatureNotSupportedException("Cannot render a row without fields");
    }
    final StringBuilder sb = new StringBuilder("ROW(");
    for (int i = 0; i < r.fields().size(); i++) {
      final SqlRowField field = r.fields().get(i);
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(compoundField(field.value()));
    }
    return sb.append(')').toString();
  }

  private String arrayElement(SqlArray array, SqlValue element) throws SQLException {
    if (element instanceof SqlRow row) {
      return formatRow(row);
    } else if (element instanceof SqlNull) {
      if (array.elementType().elementType() != null) {
        throw new SQLFeatureNotSupportedException(
            "Cannot render a null element of an array of arrays");
      }
      return "ROW(" + String.join(", ", Collections.nCopies(rowArity(array), "NULL")) + ")";
    }
    return "ROW(" + compoundField(element) + ")";
  }

  /** The number of fields of the rows of an array, which a null element must have as well. */
  private static int rowArity(SqlArray array) {
    for (SqlValue element : array.elements()) {
      if (element instanceof SqlRow row) {
        return row.fields().size();
      }
    }
    return Math.max(1, array.elementType().fields().size());
  }

  /** A value in a position of a row, where only plain literals are accepted. */
  private String compoundField(SqlValue value) throws SQLException {
    if (value instanceof SqlNull) {
      return "NULL";
    } else if (value instanceof SqlArray a) {
      return formatArray(a);
    } else if (value instanceof SqlRow r) {
      return formatRow(r);
    } else if (value instanceof SqlIntervalValue i) {
      // The text of the interval literal, without INTERVAL and the field qualifier.
      final String literal = formatIntervalMonthDayNano(i.months(), i.days(), i.nanos());
      return literal.substring(literal.indexOf('\''), literal.lastIndexOf('\'') + 1);
    } else if (value instanceof SqlScalar s) {
      return compoundScalar(s.javaValue());
    }
    throw new SQLFeatureNotSupportedException("Unsupported value " + value);
  }

  private String compoundScalar(Object v) throws SQLException {
    if (v instanceof Boolean b) {
      return formatBoolean(b);
    } else if (v instanceof Float f) {
      return compoundDouble(Double.parseDouble(f.toString()));
    } else if (v instanceof Double d) {
      return compoundDouble(d);
    } else if (v instanceof BigDecimal d) {
      return d.toPlainString();
    } else if (v instanceof Byte
        || v instanceof Short
        || v instanceof Integer
        || v instanceof Long
        || v instanceof BigInteger) {
      return v.toString();
    } else if (v instanceof String s) {
      return formatString(s, false);
    } else if (v instanceof byte[] b) {
      return formatBinary(b);
    } else if (v instanceof LocalDate d) {
      return quoted(SqlLiterals.dateText(d));
    } else if (v instanceof LocalTime t) {
      return quoted(SqlLiterals.timeText(t));
    } else if (v instanceof LocalDateTime t) {
      return quoted(dateTimeText(t.toLocalDate(), t.toLocalTime()));
    } else if (v instanceof OffsetDateTime t) {
      final OffsetDateTime whole = SqlLiterals.withWholeMinuteOffset(t);
      return quoted(
          dateTimeText(whole.toLocalDate(), whole.toLocalTime())
              + SqlLiterals.offsetText(whole.getOffset()));
    } else if (v instanceof OffsetTime) {
      // A field of type time has no zone, and the server would drop the offset silently.
      throw new SQLFeatureNotSupportedException(
          "Cannot render a time with a time zone inside an array or a row");
    } else if (v instanceof UUID u) {
      return quoted(u.toString());
    }
    throw new SQLFeatureNotSupportedException(
        "Cannot render a parameter of type " + v.getClass().getName());
  }

  /** A double without parentheses around a negative value, which a row does not accept. */
  private static String compoundDouble(double d) {
    if (Double.isNaN(d)) {
      return "'NaN'";
    } else if (Double.isInfinite(d)) {
      return d > 0 ? "'Infinity'" : "'-Infinity'";
    }
    return Double.toString(d);
  }

  private static String dateTimeText(LocalDate date, LocalTime time) throws SQLException {
    return SqlLiterals.dateText(date) + " " + SqlLiterals.timeText(time);
  }

  private static String quoted(String text) {
    return "'" + text + "'";
  }
}
