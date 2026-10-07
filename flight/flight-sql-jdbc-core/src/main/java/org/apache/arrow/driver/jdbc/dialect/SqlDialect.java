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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;

/**
 * A SQL dialect: how the driver turns parameter values into SQL literals when it rewrites a
 * statement instead of binding the parameters on the server.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so they need a public
 * no-argument constructor and an entry in {@code
 * META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect}.
 *
 * <p>The {@code format*} methods throw {@link SQLFeatureNotSupportedException} for values the
 * dialect cannot represent, and never fall back to {@code toString()}.
 */
public interface SqlDialect {
  /** The name used to select this dialect with the {@code dialect} connection property. */
  String name();

  /** Breaks ties between dialects of the same name: the highest priority wins. */
  int priority();

  /** The rules for finding parameter markers in this dialect's SQL. */
  SqlLexicalRules lexicalRules();

  /** Tells whether a statement returns a result set, without asking the server. */
  StatementKind classify(String sql);

  /**
   * Builds the statement sent to the server to learn the result set metadata of a query before it
   * runs, with a value in place of every parameter marker.
   */
  String metadataProbeSql(ScannedSql sql);

  /** Renders a value as a SQL literal or expression. Arrays and rows recurse through this. */
  String render(SqlValue value) throws SQLException;

  /** A null of the given type, which can be unknown. */
  String formatNull(SqlType type) throws SQLException;

  /**
   * A string literal. {@code national} selects the national character form. The literal must not
   * let the value end it early.
   */
  String formatString(String s, boolean national) throws SQLException;

  /** A boolean literal. */
  String formatBoolean(boolean b) throws SQLException;

  /** An exact numeric literal. A negative value must not fuse with a preceding operator. */
  String formatNumber(Number n) throws SQLException;

  /** An approximate numeric literal. */
  String formatDouble(double d) throws SQLException;

  /** A binary literal. */
  String formatBinary(byte[] b) throws SQLException;

  /** A date literal. */
  String formatLocalDate(LocalDate d) throws SQLException;

  /** A time of day literal. */
  String formatLocalTime(LocalTime t) throws SQLException;

  /** A timestamp literal, without a time zone. */
  String formatLocalDateTime(LocalDateTime t) throws SQLException;

  /** A time of day literal with an offset. */
  String formatOffsetTime(OffsetTime t) throws SQLException;

  /**
   * A timestamp literal with an offset. Also used for {@link java.time.ZonedDateTime} and {@link
   * java.time.Instant} values.
   */
  String formatOffsetDateTime(OffsetDateTime t) throws SQLException;

  /** An interval of years and months, given as a number of months. */
  String formatIntervalYearMonth(int totalMonths) throws SQLException;

  /** An interval of days and time, where {@code nanos} is the time part. */
  String formatIntervalDayTime(long days, long nanos) throws SQLException;

  /** An interval with months, days and nanoseconds. */
  String formatIntervalMonthDayNano(int months, int days, long nanos) throws SQLException;

  /** A UUID. */
  String formatUuid(UUID u) throws SQLException;

  /** An array. */
  String formatArray(SqlArray a) throws SQLException;

  /** A row. */
  String formatRow(SqlRow r) throws SQLException;

  /** The SQL name of a type, for casts. */
  String formatTypeName(SqlType type) throws SQLException;
}
