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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlIntervalValue;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class AnsiSqlDialectTest {
  private final SqlDialect dialect = new AnsiSqlDialect();

  private String render(Object javaValue, int jdbcType) throws SQLException {
    return dialect.render(new SqlScalar(javaValue, SqlType.of(jdbcType)));
  }

  private static SqlScalar integer(int i) {
    return new SqlScalar(i, SqlType.of(Types.INTEGER));
  }

  @Test
  public void testName() {
    assertEquals("ansi", dialect.name());
    assertEquals(0, dialect.priority());
  }

  @Test
  public void testStrings() throws SQLException {
    assertEquals("'abc'", render("abc", Types.VARCHAR));
    assertEquals("''", render("", Types.VARCHAR));
    assertEquals("'it''s'", render("it's", Types.VARCHAR));
    assertEquals("'a\\b'", render("a\\b", Types.VARCHAR));
    assertEquals("'a?b'", render("a?b", Types.VARCHAR));
    assertEquals("N'abc'", render("abc", Types.NVARCHAR));
  }

  @Test
  public void testQuotesCannotBreakOutOfTheLiteral() throws SQLException {
    assertEquals("'x''; DROP TABLE t; --'", render("x'; DROP TABLE t; --", Types.VARCHAR));
  }

  @Test
  public void testNulCharacterIsRejected() {
    final SQLException e = assertThrows(SQLException.class, () -> render("a\0b", Types.VARCHAR));
    assertEquals("22021", e.getSQLState());
  }

  @Test
  public void testBooleans() throws SQLException {
    assertEquals("TRUE", render(true, Types.BOOLEAN));
    assertEquals("FALSE", render(false, Types.BOOLEAN));
  }

  @Test
  public void testExactNumbers() throws SQLException {
    assertEquals("5", render(5, Types.INTEGER));
    assertEquals("5", render((byte) 5, Types.TINYINT));
    assertEquals("5", render((short) 5, Types.SMALLINT));
    assertEquals("9223372036854775807", render(Long.MAX_VALUE, Types.BIGINT));
    assertEquals("1.50", render(new BigDecimal("1.50"), Types.DECIMAL));
    assertEquals("1000", render(new BigDecimal("1E+3"), Types.DECIMAL));
    assertEquals(
        "12345678901234567890", render(new BigInteger("12345678901234567890"), Types.DECIMAL));
  }

  @Test
  public void testNegativeNumbersAreParenthesized() throws SQLException {
    // "1-?" with -5 must not become the comment "1--5".
    assertEquals("(-5)", render(-5, Types.INTEGER));
    assertEquals("(-1.50)", render(new BigDecimal("-1.50"), Types.DECIMAL));
    assertEquals("(-1.5)", render(-1.5d, Types.DOUBLE));
  }

  @Test
  public void testApproximateNumbers() throws SQLException {
    assertEquals("1.5", render(1.5d, Types.DOUBLE));
    assertEquals("1.0E10", render(1e10d, Types.DOUBLE));
    // A float renders the digits it prints with, not the widened double value.
    assertEquals("0.1", render(0.1f, Types.REAL));
  }

  @Test
  public void testNonFiniteNumbersAreRejected() {
    assertThrows(SQLFeatureNotSupportedException.class, () -> render(Double.NaN, Types.DOUBLE));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> render(Double.POSITIVE_INFINITY, Types.DOUBLE));
    assertThrows(SQLFeatureNotSupportedException.class, () -> render(Float.NaN, Types.REAL));
  }

  @Test
  public void testBinary() throws SQLException {
    assertEquals("X'0A1B'", render(new byte[] {0x0a, 0x1b}, Types.VARBINARY));
    assertEquals("X''", render(new byte[0], Types.VARBINARY));
  }

  @Test
  public void testDates() throws SQLException {
    assertEquals("DATE '2020-01-02'", render(LocalDate.of(2020, 1, 2), Types.DATE));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(LocalDate.of(10000, 1, 1), Types.DATE));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(LocalDate.of(-1, 1, 1), Types.DATE));
  }

  @Test
  public void testTimes() throws SQLException {
    assertEquals("TIME '03:04:00'", render(LocalTime.of(3, 4), Types.TIME));
    assertEquals("TIME '03:04:05'", render(LocalTime.of(3, 4, 5), Types.TIME));
    assertEquals("TIME '03:04:05.12'", render(LocalTime.of(3, 4, 5, 120_000_000), Types.TIME));
    assertEquals("TIME '03:04:05.000000001'", render(LocalTime.of(3, 4, 5, 1), Types.TIME));
    assertEquals(
        "TIME WITH TIME ZONE '03:04:05+02:00'",
        render(OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(2)), Types.TIME_WITH_TIMEZONE));
    assertEquals(
        "TIME WITH TIME ZONE '03:04:05+00:00'",
        render(OffsetTime.of(3, 4, 5, 0, ZoneOffset.UTC), Types.TIME_WITH_TIMEZONE));
  }

  @Test
  public void testTimestamps() throws SQLException {
    assertEquals(
        "TIMESTAMP '2020-01-02 03:04:05'",
        render(LocalDateTime.of(2020, 1, 2, 3, 4, 5), Types.TIMESTAMP));
    assertEquals(
        "TIMESTAMP '2020-01-02 03:04:05.123'",
        render(LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000), Types.TIMESTAMP));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '2020-01-02 03:04:05-05:30'",
        render(
            OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHoursMinutes(-5, -30)),
            Types.TIMESTAMP_WITH_TIMEZONE));
  }

  @Test
  public void testUuid() throws SQLException {
    final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertEquals("'123e4567-e89b-12d3-a456-426614174000'", render(uuid, Types.OTHER));
  }

  @Test
  public void testUnsupportedJavaTypeIsRejected() {
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(new StringBuilder("x"), Types.OTHER));
  }

  @Test
  public void testNulls() throws SQLException {
    assertEquals("NULL", dialect.render(new SqlNull(SqlType.UNKNOWN)));
    assertEquals("NULL", dialect.render(new SqlNull(SqlType.of(Types.INTEGER))));
    assertEquals(
        "CAST(NULL AS INT4)", dialect.render(new SqlNull(SqlType.of(Types.INTEGER, "INT4"))));
  }

  @Test
  public void testYearMonthIntervals() throws SQLException {
    assertEquals("INTERVAL '1-2' YEAR TO MONTH", dialect.render(new SqlIntervalValue(14, 0, 0)));
    assertEquals("INTERVAL -'1-2' YEAR TO MONTH", dialect.render(new SqlIntervalValue(-14, 0, 0)));
    assertEquals("INTERVAL '0-5' YEAR TO MONTH", dialect.formatIntervalYearMonth(5));
    assertEquals(
        "INTERVAL -'178956970-8' YEAR TO MONTH",
        dialect.formatIntervalYearMonth(Integer.MIN_VALUE));
  }

  @Test
  public void testDayTimeIntervals() throws SQLException {
    final long time = ((4 * 60L + 5) * 60 + 6) * 1_000_000_000L + 789_000_000L;
    assertEquals(
        "INTERVAL '3 04:05:06.789' DAY TO SECOND",
        dialect.render(new SqlIntervalValue(0, 3, time)));
    assertEquals(
        "INTERVAL -'3 04:05:06.789' DAY TO SECOND",
        dialect.render(new SqlIntervalValue(0, -3, -time)));
    assertEquals(
        "INTERVAL '0 00:00:00' DAY TO SECOND", dialect.render(new SqlIntervalValue(0, 0, 0)));
    assertEquals(
        "INTERVAL -'0 00:00:01.5' DAY TO SECOND",
        dialect.render(new SqlIntervalValue(0, 0, -1_500_000_000L)));
  }

  @Test
  public void testDayTimeIntervalNormalizesExtraDays() throws SQLException {
    final long twentyFiveHours = 25L * 3600 * 1_000_000_000L;
    assertEquals(
        "INTERVAL '1 01:00:00' DAY TO SECOND", dialect.formatIntervalDayTime(0, twentyFiveHours));
  }

  @Test
  public void testIntervalsThatAnsiCannotRender() {
    // Months mixed with days or time have no single ANSI literal.
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> dialect.render(new SqlIntervalValue(1, 1, 0)));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> dialect.render(new SqlIntervalValue(1, 0, 1)));
    // A day-time interval whose parts disagree in sign.
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> dialect.render(new SqlIntervalValue(0, -1, 1)));
  }

  @Test
  public void testArrays() throws SQLException {
    final SqlType integerType = SqlType.of(Types.INTEGER);
    assertEquals(
        "ARRAY[1, 2]", dialect.render(new SqlArray(integerType, List.of(integer(1), integer(2)))));
    assertEquals(
        "ARRAY[1, NULL, (-3)]",
        dialect.render(
            new SqlArray(integerType, List.of(integer(1), new SqlNull(integerType), integer(-3)))));
  }

  @Test
  public void testNestedArrays() throws SQLException {
    final SqlType integerType = SqlType.of(Types.INTEGER);
    final SqlArray inner = new SqlArray(integerType, List.of(integer(1)));
    assertEquals(
        "ARRAY[ARRAY[1], ARRAY[1]]",
        dialect.render(new SqlArray(SqlType.arrayOf(integerType), List.of(inner, inner))));
  }

  @Test
  public void testEmptyArraysAreCastToTheirElementType() throws SQLException {
    assertEquals(
        "CAST(ARRAY[] AS INTEGER ARRAY)",
        dialect.render(new SqlArray(SqlType.of(Types.INTEGER), List.of())));
    assertEquals(
        "CAST(ARRAY[] AS INT4 ARRAY)",
        dialect.render(new SqlArray(SqlType.of(Types.INTEGER, "INT4"), List.of())));
    assertEquals(
        "CAST(ARRAY[] AS DOUBLE PRECISION ARRAY)",
        dialect.render(new SqlArray(SqlType.of(Types.DOUBLE), List.of())));
    assertEquals(
        "CAST(ARRAY[] AS INTEGER ARRAY ARRAY)",
        dialect.render(new SqlArray(SqlType.arrayOf(SqlType.of(Types.INTEGER)), List.of())));
  }

  @Test
  public void testEmptyArrayOfUnknownTypeIsRejected() {
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> dialect.render(new SqlArray(SqlType.UNKNOWN, List.of())));
    // VARCHAR needs a length, so it has no default name.
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> dialect.render(new SqlArray(SqlType.of(Types.VARCHAR), List.of())));
  }

  @Test
  public void testRows() throws SQLException {
    final SqlValue row =
        new SqlRow(
            "pair",
            List.of(
                new SqlRowField(null, integer(1)),
                new SqlRowField("b", new SqlScalar("a", SqlType.of(Types.VARCHAR)))));
    assertEquals("ROW(1, 'a')", dialect.render(row));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> dialect.render(new SqlRow("t", List.of())));
  }

  @Test
  public void testRowsInsideArrays() throws SQLException {
    final SqlRow row = new SqlRow(null, List.of(new SqlRowField(null, integer(1))));
    assertEquals("ARRAY[ROW(1)]", dialect.render(new SqlArray(row.type(), List.of(row))));
  }

  @Test
  public void testClassify() {
    assertEquals(StatementKind.QUERY, dialect.classify("select 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("  /* c */ SELECT 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("(select 1) union (select 2)"));
    assertEquals(StatementKind.QUERY, dialect.classify("with x as (select 1) select * from x"));
    assertEquals(StatementKind.QUERY, dialect.classify("values (1)"));
    assertEquals(StatementKind.QUERY, dialect.classify("show tables"));
    assertEquals(StatementKind.QUERY, dialect.classify("explain select 1"));
    assertEquals(StatementKind.UPDATE, dialect.classify("insert into t values (1)"));
    assertEquals(StatementKind.UPDATE, dialect.classify("update t set a = 1"));
    assertEquals(StatementKind.UPDATE, dialect.classify("delete from t"));
    assertEquals(StatementKind.UPDATE, dialect.classify("create table t (a int)"));
    assertEquals(StatementKind.UPDATE, dialect.classify(""));
  }

  @Test
  public void testMetadataProbeReplacesMarkersWithNull() {
    final ScannedSql scanned =
        SqlParameterScanner.scan("select ?, '?' , ?", dialect.lexicalRules());
    assertEquals("select NULL, '?' , NULL", dialect.metadataProbeSql(scanned));
  }

  @Test
  public void testDialectsOverrideOnlyWhatDiffers() throws SQLException {
    final SqlDialect shouting =
        new AnsiSqlDialect() {
          @Override
          public String name() {
            return "shouting";
          }

          @Override
          public String formatBoolean(boolean b) {
            return b ? "1" : "0";
          }
        };
    assertEquals("1", shouting.render(new SqlScalar(true, SqlType.of(Types.BOOLEAN))));
    // Nested values use the overriding dialect too.
    assertEquals(
        "ARRAY[1, 0]",
        shouting.render(
            new SqlArray(
                SqlType.of(Types.BOOLEAN),
                List.of(
                    new SqlScalar(true, SqlType.of(Types.BOOLEAN)),
                    new SqlScalar(false, SqlType.of(Types.BOOLEAN))))));
    assertEquals("'x'", shouting.render(new SqlScalar("x", SqlType.of(Types.VARCHAR))));
  }

  /** A dialect whose strings take a backslash as an escape, as MySQL does. */
  private static final class BackslashDialect implements SqlDialect {
    @Override
    public String name() {
      return "backslash";
    }

    @Override
    public SqlLexicalRules lexicalRules() {
      return SqlLexicalRules.builder().backslashEscapesInStrings(true).build();
    }
  }

  @Test
  public void testBackslashesAreDoubledWhenTheyEscape() throws SQLException {
    final SqlDialect backslash = new BackslashDialect();
    assertEquals("'a\\\\b'", backslash.formatString("a\\b", false));
    assertEquals("N'a\\\\b'", backslash.formatString("a\\b", true));
    assertEquals("'it''s'", backslash.formatString("it's", false));
    // Without the doubling, the backslash would escape the quote that ends the literal.
    assertEquals("'a\\\\'", backslash.formatString("a\\", false));
    assertEquals("'\\\\'' OR 1=1 --'", backslash.formatString("\\' OR 1=1 --", false));
  }

  private static Stream<Arguments> hostileStrings() {
    return Stream.of(
            "",
            "'",
            "''",
            "x'; DROP TABLE t; --",
            "\\",
            "\\'",
            "\\\\'",
            "a\\' OR 1=1 --",
            "?",
            "'?'",
            "' ? '",
            "--",
            "-- ?",
            "/* ? */",
            "*/ ? /*",
            "\"?\"",
            "`?`",
            "[?]",
            "$$ ? $$",
            "$t$ ? $t$",
            "??",
            "line\n' ?\r\n--",
            "’ ? ʼ")
        .flatMap(
            value ->
                Stream.of(
                    Arguments.of("ansi", SqlLexicalRules.ansi(), value),
                    Arguments.of(
                        "backslash escapes",
                        SqlLexicalRules.builder().backslashEscapesInStrings(true).build(),
                        value),
                    Arguments.of(
                        "every rule",
                        SqlLexicalRules.builder()
                            .backslashEscapesInStrings(true)
                            .dollarQuoting(true)
                            .nestedBlockComments(true)
                            .backtickIdentifiers(true)
                            .bracketIdentifiers(true)
                            .doubleQuestionMarkEscape(true)
                            .build(),
                        value)));
  }

  /**
   * A rendered string must read back as a single literal under the rules it was rendered for: the
   * text after it is still outside the literal, and nothing inside it is taken as a marker.
   */
  @ParameterizedTest(name = "{0}: {2}")
  @MethodSource("hostileStrings")
  public void testRenderedStringStaysInsideItsLiteral(
      final String name, final SqlLexicalRules rules, final String value) throws SQLException {
    final SqlDialect dialectWithRules =
        new AnsiSqlDialect() {
          @Override
          public SqlLexicalRules lexicalRules() {
            return rules;
          }
        };
    final String literal = dialectWithRules.formatString(value, false);
    final ScannedSql scanned = SqlParameterScanner.scan("select " + literal + ", ? from t", rules);
    assertEquals(List.of("select " + literal + ", ", " from t"), scanned.segments());
  }

  @Test
  public void testNationalStrings() throws SQLException {
    assertEquals("N'abc'", render("abc", Types.NCHAR));
    assertEquals("N'abc'", render("abc", Types.LONGNVARCHAR));
    assertEquals("N'it''s'", render("it's", Types.NVARCHAR));
    assertEquals("'abc'", render("abc", Types.CHAR));
    assertEquals("'abc'", render("abc", Types.CLOB));
  }

  @Test
  public void testNumbersAtTheirLimits() throws SQLException {
    assertEquals("0", render(0, Types.INTEGER));
    assertEquals("(-9223372036854775808)", render(Long.MIN_VALUE, Types.BIGINT));
    assertEquals("(-128)", render(Byte.MIN_VALUE, Types.TINYINT));
    assertEquals("0.000", render(new BigDecimal("0.000"), Types.DECIMAL));
    assertEquals("0.0000001", render(new BigDecimal("1E-7"), Types.DECIMAL));
    assertEquals("(-1000)", render(new BigDecimal("-1E+3"), Types.DECIMAL));
    assertEquals(
        "(-12345678901234567890)", render(new BigInteger("-12345678901234567890"), Types.DECIMAL));
  }

  @Test
  public void testApproximateNumbersAtTheirLimits() throws SQLException {
    assertEquals("0.0", render(0d, Types.DOUBLE));
    assertEquals("(-0.0)", render(-0d, Types.DOUBLE));
    assertEquals("1.7976931348623157E308", render(Double.MAX_VALUE, Types.DOUBLE));
    assertEquals("4.9E-324", render(Double.MIN_VALUE, Types.DOUBLE));
    assertEquals("(-1.0E-5)", render(-1e-5d, Types.DOUBLE));
    assertEquals("3.4028235E38", render(Float.MAX_VALUE, Types.REAL));
    assertEquals("(-1.5)", render(-1.5f, Types.REAL));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> render(Double.NEGATIVE_INFINITY, Types.DOUBLE));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(Float.POSITIVE_INFINITY, Types.REAL));
  }

  @Test
  public void testBinaryIsUpperCaseHex() throws SQLException {
    assertEquals(
        "X'00FF7F80'", render(new byte[] {0, (byte) 0xff, 0x7f, (byte) 0x80}, Types.VARBINARY));
  }

  @Test
  public void testDatesAtTheirLimits() throws SQLException {
    assertEquals("DATE '0001-01-01'", render(LocalDate.of(1, 1, 1), Types.DATE));
    assertEquals("DATE '9999-12-31'", render(LocalDate.of(9999, 12, 31), Types.DATE));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(LocalDate.of(0, 12, 31), Types.DATE));
    // The date of a timestamp has the same limits.
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> render(LocalDateTime.of(10000, 1, 1, 0, 0), Types.TIMESTAMP));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () ->
            render(
                OffsetDateTime.of(0, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE));
  }

  @Test
  public void testTimesAtTheirLimits() throws SQLException {
    assertEquals("TIME '00:00:00'", render(LocalTime.MIDNIGHT, Types.TIME));
    assertEquals("TIME '23:59:59.999999999'", render(LocalTime.MAX, Types.TIME));
    assertEquals(
        "TIME WITH TIME ZONE '03:04:05.5-18:00'",
        render(OffsetTime.of(3, 4, 5, 500_000_000, ZoneOffset.MIN), Types.TIME_WITH_TIMEZONE));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '2020-01-02 03:04:05.000001+18:00'",
        render(
            OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 1_000, ZoneOffset.MAX),
            Types.TIMESTAMP_WITH_TIMEZONE));
  }

  @Test
  public void testNullsInsideRows() throws SQLException {
    final SqlRow row =
        new SqlRow(
            null,
            List.of(
                new SqlRowField(null, new SqlNull(SqlType.UNKNOWN)),
                new SqlRowField(null, new SqlNull(SqlType.of(Types.INTEGER, "INT4")))));
    assertEquals("ROW(NULL, CAST(NULL AS INT4))", dialect.render(row));
  }

  @Test
  public void testArraysInsideRows() throws SQLException {
    final SqlArray array = new SqlArray(SqlType.of(Types.INTEGER), List.of(integer(1), integer(2)));
    final SqlRow row =
        new SqlRow(null, List.of(new SqlRowField(null, array), new SqlRowField(null, integer(3))));
    assertEquals("ROW(ARRAY[1, 2], 3)", dialect.render(row));
  }

  @Test
  public void testIntervalsInsideArrays() throws SQLException {
    final SqlIntervalValue interval = new SqlIntervalValue(14, 0, 0);
    assertEquals(
        "ARRAY[INTERVAL '1-2' YEAR TO MONTH]",
        dialect.render(new SqlArray(interval.type(), List.of(interval))));
  }

  @Test
  public void testTypeNames() throws SQLException {
    assertEquals("BOOLEAN", dialect.formatTypeName(SqlType.of(Types.BOOLEAN)));
    assertEquals("BOOLEAN", dialect.formatTypeName(SqlType.of(Types.BIT)));
    assertEquals("SMALLINT", dialect.formatTypeName(SqlType.of(Types.SMALLINT)));
    assertEquals("INTEGER", dialect.formatTypeName(SqlType.of(Types.INTEGER)));
    assertEquals("BIGINT", dialect.formatTypeName(SqlType.of(Types.BIGINT)));
    assertEquals("REAL", dialect.formatTypeName(SqlType.of(Types.REAL)));
    assertEquals("DOUBLE PRECISION", dialect.formatTypeName(SqlType.of(Types.FLOAT)));
    assertEquals("DOUBLE PRECISION", dialect.formatTypeName(SqlType.of(Types.DOUBLE)));
    assertEquals("DATE", dialect.formatTypeName(SqlType.of(Types.DATE)));
    assertEquals("TIME", dialect.formatTypeName(SqlType.of(Types.TIME)));
    assertEquals("TIMESTAMP", dialect.formatTypeName(SqlType.of(Types.TIMESTAMP)));
    assertEquals(
        "TIME WITH TIME ZONE", dialect.formatTypeName(SqlType.of(Types.TIME_WITH_TIMEZONE)));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE",
        dialect.formatTypeName(SqlType.of(Types.TIMESTAMP_WITH_TIMEZONE)));
    // A type's own name wins over the default one.
    assertEquals("INT4", dialect.formatTypeName(SqlType.of(Types.INTEGER, "INT4")));
    assertEquals(
        "INTEGER ARRAY", dialect.formatTypeName(SqlType.arrayOf(SqlType.of(Types.INTEGER))));
  }

  @Test
  public void testTypesWithoutADefaultName() {
    for (final int jdbcType :
        new int[] {
          Types.TINYINT,
          Types.DECIMAL,
          Types.NUMERIC,
          Types.CHAR,
          Types.VARCHAR,
          Types.VARBINARY,
          Types.OTHER,
          Types.STRUCT
        }) {
      assertThrows(
          SQLFeatureNotSupportedException.class,
          () -> dialect.formatTypeName(SqlType.of(jdbcType)));
    }
  }

  @Test
  public void testClassifyIgnoresCaseAndLeadingNoise() {
    assertEquals(StatementKind.QUERY, dialect.classify("SeLeCt 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("-- note\n\tselect 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("describe t"));
    assertEquals(StatementKind.QUERY, dialect.classify("desc t"));
    assertEquals(StatementKind.QUERY, dialect.classify("table t"));
    assertEquals(StatementKind.UPDATE, dialect.classify("call p(1)"));
    assertEquals(StatementKind.UPDATE, dialect.classify("merge into t using s on (1 = 1)"));
    assertEquals(StatementKind.UPDATE, dialect.classify("drop table t"));
    assertEquals(StatementKind.UPDATE, dialect.classify("selection"));
    assertEquals(StatementKind.UPDATE, dialect.classify("-- select 1"));
  }

  @Test
  public void testMetadataProbeOfAStatementWithoutParameters() {
    final ScannedSql scanned = SqlParameterScanner.scan("select 1", dialect.lexicalRules());
    assertEquals("select 1", dialect.metadataProbeSql(scanned));
  }

  @Test
  public void testClassifyFollowsTheLexicalRules() {
    final SqlDialect nesting =
        new AnsiSqlDialect() {
          @Override
          public SqlLexicalRules lexicalRules() {
            return SqlLexicalRules.builder().nestedBlockComments(true).build();
          }
        };
    final String sql = "/* a /* b */ insert */ select 1";
    assertEquals(StatementKind.QUERY, nesting.classify(sql));
    assertEquals(StatementKind.UPDATE, dialect.classify(sql));
  }

  @Test
  public void testOffsetsWithSecondsAreWrittenInUtc() throws SQLException {
    // The Europe/Madrid offset before 1901.
    final ZoneOffset localMeanTime = ZoneOffset.ofHoursMinutesSeconds(0, -14, -44);
    assertEquals(
        "TIME WITH TIME ZONE '00:14:44+00:00'",
        render(OffsetTime.of(0, 0, 0, 0, localMeanTime), Types.TIME_WITH_TIMEZONE));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '1900-01-01 00:14:44.5+00:00'",
        render(
            OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 500_000_000, localMeanTime),
            Types.TIMESTAMP_WITH_TIMEZONE));
    // The instant is the same, so the date can change.
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '2020-01-01 21:33:50+00:00'",
        render(
            OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)),
            Types.TIMESTAMP_WITH_TIMEZONE));
    // Offsets in whole minutes are kept.
    assertEquals(
        "TIME WITH TIME ZONE '03:04:05+05:30'",
        render(
            OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHoursMinutes(5, 30)), Types.TIME_WITH_TIMEZONE));
  }

  @Test
  public void testOffsetTextRejectsSeconds() throws SQLException {
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> SqlLiterals.offsetText(ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> SqlLiterals.offsetText(ZoneOffset.ofTotalSeconds(-1)));
    assertEquals("+05:30", SqlLiterals.offsetText(ZoneOffset.ofHoursMinutes(5, 30)));
    assertEquals("-00:30", SqlLiterals.offsetText(ZoneOffset.ofHoursMinutes(0, -30)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ar", "hi-IN", "fa-IR", "th-TH-u-nu-thai", "ar-SA-u-nu-arab"})
  public void testDatesAndTimesUseAsciiDigitsWhateverTheLocale(final String languageTag)
      throws SQLException {
    final Locale defaultLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag(languageTag));
      assertEquals("DATE '2020-01-02'", render(LocalDate.of(2020, 1, 2), Types.DATE));
      assertEquals(
          "TIME '03:04:05.123456789'", render(LocalTime.of(3, 4, 5, 123_456_789), Types.TIME));
      assertEquals(
          "TIMESTAMP '2020-01-02 03:04:05.5'",
          render(LocalDateTime.of(2020, 1, 2, 3, 4, 5, 500_000_000), Types.TIMESTAMP));
      assertEquals(
          "TIMESTAMP WITH TIME ZONE '2020-01-02 03:04:05-05:30'",
          render(
              OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHoursMinutes(-5, -30)),
              Types.TIMESTAMP_WITH_TIMEZONE));
      assertEquals(
          "TIME WITH TIME ZONE '03:04:05+02:00'",
          render(OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(2)), Types.TIME_WITH_TIMEZONE));
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }
}
