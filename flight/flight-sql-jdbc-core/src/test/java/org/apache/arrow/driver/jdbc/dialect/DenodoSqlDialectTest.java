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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlIntervalValue;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.junit.jupiter.api.Test;

/**
 * The expected text of each value is what a Denodo 9.4 server accepted when the form was tried
 * against a table of that type.
 */
public class DenodoSqlDialectTest {
  private final SqlDialect dialect = new DenodoSqlDialect();

  private String render(Object javaValue, int jdbcType) throws SQLException {
    return dialect.render(scalar(javaValue, jdbcType));
  }

  private static SqlScalar scalar(Object javaValue, int jdbcType) {
    return new SqlScalar(javaValue, SqlType.of(jdbcType));
  }

  private static SqlScalar integer(int i) {
    return scalar(i, Types.INTEGER);
  }

  private static SqlScalar text(String s) {
    return scalar(s, Types.VARCHAR);
  }

  private static SqlRow row(SqlValue... values) {
    return new SqlRow(null, Arrays.stream(values).map(v -> new SqlRowField(null, v)).toList());
  }

  private static SqlArray arrayOf(SqlValue... values) {
    final SqlType elementType =
        Arrays.stream(values)
            .filter(v -> !(v instanceof SqlNull))
            .map(SqlValue::type)
            .findFirst()
            .orElse(SqlType.UNKNOWN);
    return new SqlArray(
        elementType,
        Arrays.stream(values)
            .map(v -> v instanceof SqlNull ? new SqlNull(elementType) : v)
            .toList());
  }

  @Test
  public void testName() {
    assertEquals("denodo", dialect.name());
    assertEquals(0, dialect.priority());
  }

  @Test
  public void testStrings() throws SQLException {
    assertEquals("'abc'", render("abc", Types.VARCHAR));
    assertEquals("''", render("", Types.VARCHAR));
    assertEquals("'it''s'", render("it's", Types.VARCHAR));
    assertEquals("'a?b'", render("a?b", Types.VARCHAR));
    assertEquals("'a\nb'", render("a\nb", Types.VARCHAR));
    // Characters outside the BMP and non-Latin scripts are written as they are.
    assertEquals("'ñ€😀 中文'", render("ñ€😀 中文", Types.VARCHAR));
  }

  @Test
  public void testBackslashIsNotAnEscape() throws SQLException {
    assertEquals("'a\\b'", render("a\\b", Types.VARCHAR));
    // Only the quote is doubled, so the backslash stays in front of it.
    assertEquals("'a\\''; --'", render("a\\'; --", Types.VARCHAR));
    assertEquals(false, dialect.lexicalRules().backslashEscapesInStrings());
  }

  @Test
  public void testQuotesCannotBreakOutOfTheLiteral() throws SQLException {
    assertEquals("'x''; DROP TABLE t; --'", render("x'; DROP TABLE t; --", Types.VARCHAR));
  }

  @Test
  public void testNationalStringsHaveNoPrefix() throws SQLException {
    // Denodo has no N'...' form: text is Unicode already.
    assertEquals("'abc'", render("abc", Types.NVARCHAR));
    assertEquals("'abc'", render("abc", Types.NCHAR));
    assertEquals("'abc'", render("abc", Types.LONGNVARCHAR));
    assertEquals("'ñ'", render("ñ", Types.NVARCHAR));
  }

  @Test
  public void testNulCharacterIsRejected() {
    assertThrows(SQLException.class, () -> render("a\0b", Types.VARCHAR));
  }

  @Test
  public void testBooleans() throws SQLException {
    assertEquals("TRUE", render(true, Types.BOOLEAN));
    assertEquals("FALSE", render(false, Types.BOOLEAN));
  }

  @Test
  public void testExactNumbers() throws SQLException {
    assertEquals("42", render(42, Types.INTEGER));
    assertEquals("9223372036854775807", render(Long.MAX_VALUE, Types.BIGINT));
    assertEquals("(-9223372036854775808)", render(Long.MIN_VALUE, Types.BIGINT));
    assertEquals("1.50", render(new BigDecimal("1.50"), Types.DECIMAL));
    assertEquals("1000", render(new BigDecimal("1E+3"), Types.DECIMAL));
    assertEquals(
        "123456789012345678901234567890",
        render(new BigInteger("123456789012345678901234567890"), Types.DECIMAL));
  }

  @Test
  public void testNegativeNumbersAreParenthesized() throws SQLException {
    // "1-?" with -5 must not become the comment "1--5".
    assertEquals("(-5)", render(-5, Types.INTEGER));
    assertEquals("(-1.50)", render(new BigDecimal("-1.50"), Types.DECIMAL));
    assertEquals("(-1.5)", render(-1.5d, Types.DOUBLE));
    assertEquals("(-0.0)", render(-0.0d, Types.DOUBLE));
  }

  @Test
  public void testApproximateNumbers() throws SQLException {
    assertEquals("1.5", render(1.5d, Types.DOUBLE));
    assertEquals("1.0E10", render(1e10d, Types.DOUBLE));
    assertEquals("4.9E-324", render(Double.MIN_VALUE, Types.DOUBLE));
    assertEquals("1.7976931348623157E308", render(Double.MAX_VALUE, Types.DOUBLE));
    assertEquals("0.1", render(0.1f, Types.REAL));
  }

  @Test
  public void testNonFiniteNumbers() throws SQLException {
    assertEquals("CAST('NaN' AS DOUBLE PRECISION)", render(Double.NaN, Types.DOUBLE));
    assertEquals(
        "CAST('Infinity' AS DOUBLE PRECISION)", render(Double.POSITIVE_INFINITY, Types.DOUBLE));
    assertEquals(
        "CAST('-Infinity' AS DOUBLE PRECISION)", render(Double.NEGATIVE_INFINITY, Types.DOUBLE));
    assertEquals("CAST('NaN' AS DOUBLE PRECISION)", render(Float.NaN, Types.REAL));
    assertEquals(
        "CAST('-Infinity' AS DOUBLE PRECISION)", render(Float.NEGATIVE_INFINITY, Types.REAL));
  }

  @Test
  public void testBinaryIsAHexadecimalString() throws SQLException {
    assertEquals("'0a1b'", render(new byte[] {0x0a, 0x1b}, Types.VARBINARY));
    assertEquals("''", render(new byte[0], Types.VARBINARY));
    final byte[] all = new byte[256];
    final StringBuilder expected = new StringBuilder("'");
    for (int i = 0; i < 256; i++) {
      all[i] = (byte) i;
      expected.append(String.format("%02x", i));
    }
    assertEquals(expected.append('\'').toString(), render(all, Types.VARBINARY));
  }

  @Test
  public void testDates() throws SQLException {
    assertEquals("DATE '2024-01-31'", render(LocalDate.of(2024, 1, 31), Types.DATE));
    assertEquals("DATE '0001-01-01'", render(LocalDate.of(1, 1, 1), Types.DATE));
    assertEquals("DATE '9999-12-31'", render(LocalDate.of(9999, 12, 31), Types.DATE));
    // Denodo has no dates before year 1 or after year 9999.
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(LocalDate.of(10000, 1, 1), Types.DATE));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> render(LocalDate.of(0, 1, 1), Types.DATE));
  }

  @Test
  public void testTimes() throws SQLException {
    assertEquals("TIME '10:15:30'", render(LocalTime.of(10, 15, 30), Types.TIME));
    assertEquals(
        "TIME '10:15:30.123456789'", render(LocalTime.of(10, 15, 30, 123_456_789), Types.TIME));
    assertEquals(
        "TIME WITH TIME ZONE '10:15:30+02:00'",
        render(OffsetTime.of(10, 15, 30, 0, ZoneOffset.ofHours(2)), Types.TIME_WITH_TIMEZONE));
  }

  @Test
  public void testTimestamps() throws SQLException {
    assertEquals(
        "TIMESTAMP '2024-01-31 10:15:30.123456789'",
        render(LocalDateTime.of(2024, 1, 31, 10, 15, 30, 123_456_789), Types.TIMESTAMP));
    assertEquals(
        "TIMESTAMP '0001-01-01 00:00:00'",
        render(LocalDateTime.of(1, 1, 1, 0, 0, 0), Types.TIMESTAMP));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '2024-01-31 10:15:30.123456789-05:30'",
        render(
            OffsetDateTime.of(
                2024, 1, 31, 10, 15, 30, 123_456_789, ZoneOffset.ofHoursMinutes(-5, -30)),
            Types.TIMESTAMP_WITH_TIMEZONE));
  }

  @Test
  public void testOffsetWithSecondsIsWrittenInUtc() throws SQLException {
    final OffsetDateTime t =
        OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.ofTotalSeconds(-884));
    assertEquals(
        "TIMESTAMP WITH TIME ZONE '1900-01-01 00:14:44+00:00'",
        render(t, Types.TIMESTAMP_WITH_TIMEZONE));
  }

  @Test
  public void testUuid() throws SQLException {
    final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertEquals("'123e4567-e89b-12d3-a456-426614174000'", render(uuid, Types.OTHER));
  }

  @Test
  public void testUnsupportedJavaTypeIsRejected() {
    assertThrows(SQLFeatureNotSupportedException.class, () -> render(new Object(), Types.OTHER));
  }

  @Test
  public void testNullsAreAlwaysPlain() throws SQLException {
    assertEquals("NULL", dialect.render(new SqlNull(SqlType.UNKNOWN)));
    assertEquals("NULL", dialect.render(new SqlNull(SqlType.of(Types.INTEGER))));
    // A type name that came from another database could not be trusted in a CAST.
    assertEquals("NULL", dialect.render(new SqlNull(SqlType.of(Types.INTEGER, "int4"))));
  }

  @Test
  public void testIntervals() throws SQLException {
    assertEquals("INTERVAL '1-2' YEAR TO MONTH", dialect.render(new SqlIntervalValue(14, 0, 0)));
    assertEquals("INTERVAL '-1-2' YEAR TO MONTH", dialect.render(new SqlIntervalValue(-14, 0, 0)));
    final long time = ((4 * 60L + 5) * 60 + 6) * 1_000_000_000L + 789_123_456L;
    assertEquals(
        "INTERVAL '3 04:05:06.789123456' DAY TO SECOND",
        dialect.render(new SqlIntervalValue(0, 3, time)));
    assertEquals(
        "INTERVAL '-3 04:05:06.789123456' DAY TO SECOND",
        dialect.render(new SqlIntervalValue(0, -3, -time)));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> dialect.render(new SqlIntervalValue(1, 1, 0)));
  }

  @Test
  public void testArraysAreArraysOfRows() throws SQLException {
    assertEquals("{ROW(1), ROW(2)}", dialect.render(arrayOf(integer(1), integer(2))));
    assertEquals("{ROW('a'), ROW('it''s')}", dialect.render(arrayOf(text("a"), text("it's"))));
    assertEquals("{}", dialect.render(new SqlArray(SqlType.of(Types.INTEGER), List.of())));
    assertEquals("{}", dialect.render(new SqlArray(SqlType.UNKNOWN, List.of())));
  }

  @Test
  public void testNullElementIsARowOfNull() throws SQLException {
    assertEquals(
        "{ROW(1), ROW(NULL)}",
        dialect.render(arrayOf(integer(1), new SqlNull(SqlType.of(Types.INTEGER)))));
    assertEquals(
        "{ROW(NULL), ROW(NULL)}",
        dialect.render(
            new SqlArray(
                SqlType.UNKNOWN,
                List.of(new SqlNull(SqlType.UNKNOWN), new SqlNull(SqlType.UNKNOWN)))));
  }

  @Test
  public void testNumbersInsideArraysHaveNoParentheses() throws SQLException {
    // Denodo does not take (-5) inside ROW(...).
    assertEquals(
        "{ROW(-5), ROW(-1.5)}", dialect.render(arrayOf(integer(-5), scalar(-1.5d, Types.DOUBLE))));
  }

  @Test
  public void testArraysOfRowsHaveTheRowsAsElements() throws SQLException {
    final SqlRow first = row(integer(1), text("a"));
    final SqlRow second = row(integer(2), text("b"));
    assertEquals("{ROW(1, 'a'), ROW(2, 'b')}", dialect.render(arrayOf(first, second)));
  }

  @Test
  public void testNullElementOfAnArrayOfRowsHasAsManyFieldsAsTheRows() throws SQLException {
    final SqlRow first = row(integer(1), text("a"), integer(2));
    final SqlArray array = arrayOf(first, new SqlNull(first.type()));
    assertEquals("{ROW(1, 'a', 2), ROW(NULL, NULL, NULL)}", dialect.render(array));
    // Without a row among the elements, the declared element type tells the number of fields.
    assertEquals(
        "{ROW(NULL, NULL, NULL), ROW(NULL, NULL, NULL)}",
        dialect.render(
            new SqlArray(
                first.type(), List.of(new SqlNull(first.type()), new SqlNull(first.type())))));
  }

  @Test
  public void testNestedArraysAreWrappedInRows() throws SQLException {
    final SqlArray ints = arrayOf(integer(1), integer(2));
    final SqlArray more = arrayOf(integer(3));
    assertEquals("{ROW({ROW(1), ROW(2)}), ROW({ROW(3)})}", dialect.render(arrayOf(ints, more)));
    assertEquals(
        "{ROW({})}", dialect.render(arrayOf(new SqlArray(SqlType.of(Types.INTEGER), List.of()))));
  }

  @Test
  public void testNullElementOfAnArrayOfArraysIsRejected() {
    final SqlArray ints = arrayOf(integer(1));
    final SqlArray array = arrayOf(ints, new SqlNull(ints.type()));
    assertThrows(SQLFeatureNotSupportedException.class, () -> dialect.render(array));
  }

  @Test
  public void testRows() throws SQLException {
    assertEquals("ROW(1, 'a')", dialect.render(row(integer(1), text("a"))));
    assertEquals(
        "ROW(1, 'a')",
        dialect.render(
            new SqlRow(
                "pair",
                List.of(new SqlRowField(null, integer(1)), new SqlRowField("b", text("a"))))));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> dialect.render(new SqlRow("t", List.of())));
  }

  @Test
  public void testRowFieldsAreAcceptedLiterals() throws SQLException {
    assertEquals(
        "ROW(NULL, -5, -1.5, TRUE, 'it''s')",
        dialect.render(
            row(
                new SqlNull(SqlType.UNKNOWN),
                integer(-5),
                scalar(-1.5d, Types.DOUBLE),
                scalar(true, Types.BOOLEAN),
                text("it's"))));
    assertEquals(
        "ROW(1.25, -0.5, 123456789012345678901234567890)",
        dialect.render(
            row(
                scalar(new BigDecimal("1.25"), Types.DECIMAL),
                scalar(new BigDecimal("-0.5"), Types.DECIMAL),
                scalar(new BigInteger("123456789012345678901234567890"), Types.DECIMAL))));
  }

  @Test
  public void testTemporalFieldsAreText() throws SQLException {
    // Denodo does not accept DATE '...' or TIMESTAMP '...' inside ROW(...).
    assertEquals(
        "ROW('2024-01-31', '10:15:30.123456789', '2024-01-31 10:15:30.123456789',"
            + " '2024-01-31 10:15:30+02:00', '123e4567-e89b-12d3-a456-426614174000')",
        dialect.render(
            row(
                scalar(LocalDate.of(2024, 1, 31), Types.DATE),
                scalar(LocalTime.of(10, 15, 30, 123_456_789), Types.TIME),
                scalar(LocalDateTime.of(2024, 1, 31, 10, 15, 30, 123_456_789), Types.TIMESTAMP),
                scalar(
                    OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 0, ZoneOffset.ofHours(2)),
                    Types.TIMESTAMP_WITH_TIMEZONE),
                scalar(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), Types.OTHER))));
  }

  @Test
  public void testTimeWithTimeZoneInsideARowIsRejected() {
    // The server drops the offset of a time field silently.
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () ->
            dialect.render(
                row(
                    scalar(
                        OffsetTime.of(10, 15, 30, 0, ZoneOffset.ofHours(2)),
                        Types.TIME_WITH_TIMEZONE))));
  }

  @Test
  public void testNonFiniteNumbersInsideARowAreText() throws SQLException {
    assertEquals(
        "ROW('NaN', 'Infinity', '-Infinity')",
        dialect.render(
            row(
                scalar(Double.NaN, Types.DOUBLE),
                scalar(Double.POSITIVE_INFINITY, Types.DOUBLE),
                scalar(Float.NEGATIVE_INFINITY, Types.REAL))));
  }

  @Test
  public void testBinaryAndIntervalsInsideARowAreText() throws SQLException {
    final long time = ((4 * 60L + 5) * 60 + 6) * 1_000_000_000L + 789_000_000L;
    assertEquals(
        "ROW('0a1b', '1-2', '-1-2', '3 04:05:06.789', '-3 04:05:06.789')",
        dialect.render(
            row(
                scalar(new byte[] {0x0a, 0x1b}, Types.VARBINARY),
                new SqlIntervalValue(14, 0, 0),
                new SqlIntervalValue(-14, 0, 0),
                new SqlIntervalValue(0, 3, time),
                new SqlIntervalValue(0, -3, -time))));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> dialect.render(row(new SqlIntervalValue(1, 1, 0))));
  }

  @Test
  public void testNestedRowsAndArrays() throws SQLException {
    assertEquals(
        "ROW(1, {ROW(10), ROW(20)}, ROW(2, 'x'))",
        dialect.render(
            row(integer(1), arrayOf(integer(10), integer(20)), row(integer(2), text("x")))));
    assertEquals(
        "ROW(1, {}, NULL)",
        dialect.render(
            row(
                integer(1),
                new SqlArray(SqlType.of(Types.INTEGER), List.of()),
                new SqlNull(SqlType.UNKNOWN))));
  }

  @Test
  public void testLexicalRules() {
    final SqlLexicalRules rules = dialect.lexicalRules();
    assertTrue(rules.backtickIdentifiers());
    assertTrue(rules.doubleSlashComments());
    assertEquals(false, rules.backslashEscapesInStrings());
    assertEquals(false, rules.dollarQuoting());
    assertEquals(false, rules.nestedBlockComments());
    assertEquals(false, rules.bracketIdentifiers());
    assertEquals(false, rules.doubleQuestionMarkEscape());
  }

  @Test
  public void testMarkersInsideCommentsAndIdentifiersAreIgnored() {
    final SqlLexicalRules rules = dialect.lexicalRules();
    assertEquals(
        List.of("select `a?b` from t // what?\n where c = ", ""),
        SqlParameterScanner.scan("select `a?b` from t // what?\n where c = ?", rules).segments());
    assertEquals(
        List.of("select 1 /* ? */ -- ?\n, ", ""),
        SqlParameterScanner.scan("select 1 /* ? */ -- ?\n, ?", rules).segments());
    assertEquals(
        List.of("select 'a\\', ", ""),
        SqlParameterScanner.scan("select 'a\\', ?", rules).segments());
  }

  @Test
  public void testClassify() {
    assertEquals(StatementKind.QUERY, dialect.classify("select 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("// note\n  SELECT 1"));
    assertEquals(StatementKind.QUERY, dialect.classify("with x as (select 1) select * from x"));
    assertEquals(StatementKind.UPDATE, dialect.classify("insert into t values (?)"));
    assertEquals(StatementKind.UPDATE, dialect.classify("// select\ndelete from t"));
  }

  @Test
  public void testMetadataProbeReplacesMarkersWithNull() {
    final ScannedSql scanned =
        SqlParameterScanner.scan("select a from t where b = ? // c = ?", dialect.lexicalRules());
    assertEquals("select a from t where b = NULL // c = ?", dialect.metadataProbeSql(scanned));
  }
}
