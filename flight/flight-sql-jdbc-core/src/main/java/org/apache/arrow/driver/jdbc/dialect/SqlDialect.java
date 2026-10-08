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
import java.sql.Types;
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
 * A SQL dialect: how the driver turns parameter values into SQL literals when it rewrites a
 * statement instead of binding the parameters on the server.
 *
 * <p>Every method has an ANSI SQL default, so an implementation overrides only what its database
 * does differently. Implementations are discovered with {@link java.util.ServiceLoader}, so they
 * need a public no-argument constructor and an entry in {@code
 * META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect}.
 *
 * <p>The {@code format*} methods throw {@link SQLFeatureNotSupportedException} for values the
 * dialect cannot represent, and never fall back to {@code toString()}.
 */
public interface SqlDialect {
  /** The name used to select this dialect with the {@code dialect} connection property. */
  String name();

  /** Breaks ties between dialects of the same name: the highest priority wins. */
  default int priority() {
    return 0;
  }

  /** The rules for finding parameter markers in this dialect's SQL. */
  default SqlLexicalRules lexicalRules() {
    return SqlLexicalRules.ansi();
  }

  /**
   * Tells whether a statement returns a result set, without asking the server. The default looks at
   * the first keyword; a dialect overrides this for constructs such as data-modifying {@code WITH}.
   */
  default StatementKind classify(String sql) {
    return StatementKind.ofKeyword(SqlParameterScanner.leadingKeyword(sql, lexicalRules()));
  }

  /**
   * Builds the statement sent to the server to learn the result set metadata of a query before it
   * runs. The default replaces every marker with {@code NULL}.
   */
  default String metadataProbeSql(ScannedSql sql) {
    return sql.join(Collections.nCopies(sql.parameterCount(), "NULL"));
  }

  /** Renders a value as a SQL literal or expression. Arrays and rows recurse through this. */
  default String render(SqlValue value) throws SQLException {
    if (value instanceof SqlNull n) {
      return formatNull(n.type());
    } else if (value instanceof SqlScalar s) {
      return renderScalar(s);
    } else if (value instanceof SqlArray a) {
      return formatArray(a);
    } else if (value instanceof SqlRow r) {
      return formatRow(r);
    } else if (value instanceof SqlIntervalValue i) {
      return formatIntervalMonthDayNano(i.months(), i.days(), i.nanos());
    }
    throw new SQLFeatureNotSupportedException("Unsupported value " + value);
  }

  private String renderScalar(SqlScalar scalar) throws SQLException {
    final Object v = scalar.javaValue();
    if (v instanceof Boolean b) {
      return formatBoolean(b);
    } else if (v instanceof Float f) {
      return formatDouble(Double.parseDouble(f.toString()));
    } else if (v instanceof Double d) {
      return formatDouble(d);
    } else if (v instanceof Byte
        || v instanceof Short
        || v instanceof Integer
        || v instanceof Long
        || v instanceof BigInteger
        || v instanceof BigDecimal) {
      return formatNumber((Number) v);
    } else if (v instanceof String s) {
      final int type = scalar.type().jdbcType();
      return formatString(
          s, type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR);
    } else if (v instanceof byte[] b) {
      return formatBinary(b);
    } else if (v instanceof LocalDate d) {
      return formatLocalDate(d);
    } else if (v instanceof LocalTime t) {
      return formatLocalTime(t);
    } else if (v instanceof LocalDateTime t) {
      return formatLocalDateTime(t);
    } else if (v instanceof OffsetTime t) {
      return formatOffsetTime(t);
    } else if (v instanceof OffsetDateTime t) {
      return formatOffsetDateTime(t);
    } else if (v instanceof UUID u) {
      return formatUuid(u);
    }
    throw new SQLFeatureNotSupportedException(
        "Cannot render a parameter of type " + v.getClass().getName());
  }

  /** A null. Uses {@code CAST(NULL AS type)} when the type name is known. */
  default String formatNull(SqlType type) throws SQLException {
    return type.typeName() == null ? "NULL" : "CAST(NULL AS " + type.typeName() + ")";
  }

  /**
   * A string literal. {@code national} selects the {@code N'...'} form.
   *
   * <p>A quote is doubled. A backslash is doubled too when {@link
   * SqlLexicalRules#backslashEscapesInStrings()} says the dialect reads it as an escape, so a value
   * cannot end the literal early.
   */
  default String formatString(String s, boolean national) throws SQLException {
    if (s.indexOf('\0') >= 0) {
      throw new SQLException("A string parameter cannot contain the NUL character", "22021");
    }
    final String escaped = lexicalRules().backslashEscapesInStrings() ? s.replace("\\", "\\\\") : s;
    final String literal = "'" + escaped.replace("'", "''") + "'";
    return national ? "N" + literal : literal;
  }

  default String formatBoolean(boolean b) throws SQLException {
    return b ? "TRUE" : "FALSE";
  }

  /**
   * An exact numeric literal. Negative values are parenthesized so they cannot fuse with a
   * preceding operator: {@code 1-?} with {@code -5} must not become the comment {@code 1--5}.
   */
  default String formatNumber(Number n) throws SQLException {
    final String s = n instanceof BigDecimal d ? d.toPlainString() : n.toString();
    return s.startsWith("-") ? "(" + s + ")" : s;
  }

  /**
   * An approximate numeric literal. NaN and the infinities are rejected, as ANSI has no literal.
   */
  default String formatDouble(double d) throws SQLException {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new SQLFeatureNotSupportedException("Cannot render the floating point value " + d);
    }
    final String s = Double.toString(d);
    return s.startsWith("-") ? "(" + s + ")" : s;
  }

  /** A binary literal: {@code X'0A1B'}. */
  default String formatBinary(byte[] b) throws SQLException {
    return "X'" + HexFormat.of().withUpperCase().formatHex(b) + "'";
  }

  default String formatLocalDate(LocalDate d) throws SQLException {
    return "DATE '" + SqlLiterals.dateText(d) + "'";
  }

  default String formatLocalTime(LocalTime t) throws SQLException {
    return "TIME '" + SqlLiterals.timeText(t) + "'";
  }

  /** A timestamp literal: {@code TIMESTAMP '2020-01-02 03:04:05'}. */
  default String formatLocalDateTime(LocalDateTime t) throws SQLException {
    return "TIMESTAMP '"
        + SqlLiterals.dateText(t.toLocalDate())
        + " "
        + SqlLiterals.timeText(t.toLocalTime())
        + "'";
  }

  /**
   * A time literal with an offset: {@code TIME WITH TIME ZONE '03:04:05+02:00'}. ANSI offsets have
   * no seconds, so a value with such an offset is written in UTC, which is the same instant.
   */
  default String formatOffsetTime(OffsetTime t) throws SQLException {
    final OffsetTime whole = SqlLiterals.withWholeMinuteOffset(t);
    return "TIME WITH TIME ZONE '"
        + SqlLiterals.timeText(whole.toLocalTime())
        + SqlLiterals.offsetText(whole.getOffset())
        + "'";
  }

  /**
   * Also used for {@link java.time.ZonedDateTime} and {@link java.time.Instant} values. An offset
   * with seconds is handled as in {@link #formatOffsetTime}.
   */
  default String formatOffsetDateTime(OffsetDateTime t) throws SQLException {
    final OffsetDateTime whole = SqlLiterals.withWholeMinuteOffset(t);
    return "TIMESTAMP WITH TIME ZONE '"
        + SqlLiterals.dateText(whole.toLocalDate())
        + " "
        + SqlLiterals.timeText(whole.toLocalTime())
        + SqlLiterals.offsetText(whole.getOffset())
        + "'";
  }

  /**
   * An interval of years and months: {@code INTERVAL '1-2' YEAR TO MONTH}. A negative interval
   * carries its sign inside the quotes, {@code INTERVAL '-1-2' YEAR TO MONTH}, which the standard
   * allows and which more databases parse than a sign in front of the quotes.
   */
  default String formatIntervalYearMonth(int totalMonths) throws SQLException {
    final long abs = Math.abs((long) totalMonths);
    return "INTERVAL '"
        + (totalMonths < 0 ? "-" : "")
        + abs / 12
        + "-"
        + abs % 12
        + "' YEAR TO MONTH";
  }

  /**
   * An interval of days and time: {@code INTERVAL '3 04:05:06.789' DAY TO SECOND}. A negative
   * interval carries its sign inside the quotes, as in {@link #formatIntervalYearMonth}, and the
   * sign applies to the whole value.
   */
  default String formatIntervalDayTime(long days, long nanos) throws SQLException {
    if ((days < 0 && nanos > 0) || (days > 0 && nanos < 0)) {
      throw new SQLFeatureNotSupportedException(
          "Cannot render a day-time interval whose days and time have different signs");
    }
    final boolean negative = days < 0 || nanos < 0;
    long absDays = Math.abs(days);
    long absNanos = Math.abs(nanos);
    absDays += absNanos / SqlLiterals.NANOS_PER_DAY;
    absNanos %= SqlLiterals.NANOS_PER_DAY;
    return "INTERVAL '"
        + (negative ? "-" : "")
        + absDays
        + " "
        + SqlLiterals.timeText(LocalTime.ofNanoOfDay(absNanos))
        + "' DAY TO SECOND";
  }

  /**
   * An interval with months, days and nanoseconds. ANSI SQL cannot mix year-month and day-time
   * parts in one literal, so the default accepts a value with only one of the two kinds.
   */
  default String formatIntervalMonthDayNano(int months, int days, long nanos) throws SQLException {
    if (months == 0) {
      return formatIntervalDayTime(days, nanos);
    } else if (days == 0 && nanos == 0) {
      return formatIntervalYearMonth(months);
    }
    throw new SQLFeatureNotSupportedException(
        "Cannot render an interval mixing months with days or time");
  }

  /** A string literal holding the canonical text form, as ANSI SQL has no UUID literal. */
  default String formatUuid(UUID u) throws SQLException {
    return "'" + u + "'";
  }

  /**
   * An array: {@code ARRAY[1, 2]}. An empty array is cast to its element type, which must be known.
   */
  default String formatArray(SqlArray a) throws SQLException {
    if (a.elements().isEmpty()) {
      return "CAST(ARRAY[] AS " + formatTypeName(a.elementType()) + " ARRAY)";
    }
    final StringBuilder sb = new StringBuilder("ARRAY[");
    for (int i = 0; i < a.elements().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(render(a.elements().get(i)));
    }
    return sb.append(']').toString();
  }

  /** A row: {@code ROW(1, 'a')}. Field names are not part of ANSI row values. */
  default String formatRow(SqlRow r) throws SQLException {
    if (r.fields().isEmpty()) {
      throw new SQLFeatureNotSupportedException("Cannot render a row without fields");
    }
    final StringBuilder sb = new StringBuilder("ROW(");
    for (int i = 0; i < r.fields().size(); i++) {
      final SqlRowField field = r.fields().get(i);
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(render(field.value()));
    }
    return sb.append(')').toString();
  }

  /**
   * The SQL name of a type, for casts. Uses the type's own name when it has one. Types that need a
   * length or precision have no default name.
   */
  default String formatTypeName(SqlType type) throws SQLException {
    if (type.typeName() != null) {
      return type.typeName();
    }
    if (type.elementType() != null) {
      return formatTypeName(type.elementType()) + " ARRAY";
    }
    switch (type.jdbcType()) {
      case Types.BOOLEAN:
      case Types.BIT:
        return "BOOLEAN";
      case Types.SMALLINT:
        return "SMALLINT";
      case Types.INTEGER:
        return "INTEGER";
      case Types.BIGINT:
        return "BIGINT";
      case Types.REAL:
        return "REAL";
      case Types.FLOAT:
      case Types.DOUBLE:
        return "DOUBLE PRECISION";
      case Types.DATE:
        return "DATE";
      case Types.TIME:
        return "TIME";
      case Types.TIMESTAMP:
        return "TIMESTAMP";
      case Types.TIME_WITH_TIMEZONE:
        return "TIME WITH TIME ZONE";
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return "TIMESTAMP WITH TIME ZONE";
      default:
        throw new SQLFeatureNotSupportedException(
            "Cannot determine the SQL type name for JDBC type " + type.jdbcType());
    }
  }
}
