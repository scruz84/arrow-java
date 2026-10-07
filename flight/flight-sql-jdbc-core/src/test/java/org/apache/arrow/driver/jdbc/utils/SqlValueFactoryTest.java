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
package org.apache.arrow.driver.jdbc.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlIntervalValue;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.apache.arrow.vector.PeriodDuration;
import org.apache.calcite.avatica.AvaticaParameter;
import org.apache.calcite.avatica.AvaticaSite;
import org.apache.calcite.avatica.ColumnMetaData.Rep;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.calcite.avatica.util.ByteString;
import org.junit.jupiter.api.Test;

public class SqlValueFactoryTest {
  /** Sets a parameter through Avatica's own setters, as a JDBC call would. */
  private interface Setter {
    void set(AvaticaSite site) throws SQLException;
  }

  private static TypedValue typed(Setter setter) throws SQLException {
    final TypedValue[] values = new TypedValue[1];
    final AvaticaSite site =
        new AvaticaSite(
            new AvaticaParameter(false, 0, 0, Types.OTHER, "", "", ""),
            Calendar.getInstance(),
            0,
            values);
    setter.set(site);
    return values[0];
  }

  private static SqlValue fromSetter(Setter setter) throws SQLException {
    return SqlValueFactory.fromTypedValue(typed(setter));
  }

  private static void assertScalar(Object expected, int jdbcType, SqlValue actual) {
    final SqlScalar scalar = assertInstanceOf(SqlScalar.class, actual);
    assertEquals(expected, scalar.javaValue());
    assertEquals(jdbcType, scalar.type().jdbcType());
  }

  private static java.sql.Array sqlArray(Object[] elements, int baseType, String baseTypeName) {
    return (java.sql.Array)
        Proxy.newProxyInstance(
            SqlValueFactoryTest.class.getClassLoader(),
            new Class<?>[] {java.sql.Array.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getArray":
                  return elements;
                case "getBaseType":
                  return baseType;
                case "getBaseTypeName":
                  return baseTypeName;
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  // Values set through the JDBC setters, as Avatica hands them over.

  @Test
  public void testNulls() throws SQLException {
    assertEquals(new SqlNull(SqlType.UNKNOWN), SqlValueFactory.fromTypedValue(null));
    assertEquals(new SqlNull(SqlType.UNKNOWN), fromSetter(site -> site.setNull(Types.INTEGER)));
    assertEquals(new SqlNull(SqlType.UNKNOWN), fromSetter(site -> site.setObject(null)));
  }

  @Test
  public void testPrimitivesAndStrings() throws SQLException {
    assertScalar(true, Types.BOOLEAN, fromSetter(site -> site.setBoolean(true)));
    assertScalar((byte) 1, Types.TINYINT, fromSetter(site -> site.setByte((byte) 1)));
    assertScalar((short) 2, Types.SMALLINT, fromSetter(site -> site.setShort((short) 2)));
    assertScalar(3, Types.INTEGER, fromSetter(site -> site.setInt(3)));
    assertScalar(4L, Types.BIGINT, fromSetter(site -> site.setLong(4L)));
    assertScalar(1.5f, Types.REAL, fromSetter(site -> site.setFloat(1.5f)));
    assertScalar(2.5d, Types.DOUBLE, fromSetter(site -> site.setDouble(2.5d)));
    assertScalar(
        new BigDecimal("1.50"),
        Types.DECIMAL,
        fromSetter(site -> site.setBigDecimal(new BigDecimal("1.50"))));
    assertScalar("abc", Types.VARCHAR, fromSetter(site -> site.setString("abc")));
  }

  @Test
  public void testBytes() throws SQLException {
    final SqlScalar scalar =
        assertInstanceOf(SqlScalar.class, fromSetter(site -> site.setBytes(new byte[] {1, 2})));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) scalar.javaValue());
    assertEquals(Types.VARBINARY, scalar.type().jdbcType());
  }

  @Test
  public void testDatesAreTheWallClockValueOfTheCalendarZone() throws SQLException {
    final Calendar calendar = Calendar.getInstance();
    assertScalar(
        LocalDate.of(2020, 1, 2),
        Types.DATE,
        fromSetter(site -> site.setDate(java.sql.Date.valueOf("2020-01-02"), calendar)));
    assertScalar(
        LocalTime.of(3, 4, 5),
        Types.TIME,
        fromSetter(site -> site.setTime(Time.valueOf("03:04:05"), calendar)));
    assertScalar(
        LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000),
        Types.TIMESTAMP,
        fromSetter(
            site -> site.setTimestamp(Timestamp.valueOf("2020-01-02 03:04:05.123"), calendar)));
  }

  @Test
  public void testDatesBeforeTheEpoch() throws SQLException {
    final Calendar calendar = Calendar.getInstance();
    assertScalar(
        LocalDate.of(1950, 6, 7),
        Types.DATE,
        fromSetter(site -> site.setDate(java.sql.Date.valueOf("1950-06-07"), calendar)));
    assertScalar(
        LocalDateTime.of(1950, 6, 7, 8, 9, 10, 5_000_000),
        Types.TIMESTAMP,
        fromSetter(
            site -> site.setTimestamp(Timestamp.valueOf("1950-06-07 08:09:10.005"), calendar)));
  }

  @Test
  public void testArraySetThroughJdbc() throws SQLException {
    final SqlArray array =
        assertInstanceOf(
            SqlArray.class,
            fromSetter(
                site ->
                    site.setArray(sqlArray(new Object[] {1, null, 3}, Types.INTEGER, "INTEGER"))));
    assertEquals(3, array.elements().size());
    assertScalar(1, Types.INTEGER, array.elements().get(0));
    assertInstanceOf(SqlNull.class, array.elements().get(1));
    assertScalar(3, Types.INTEGER, array.elements().get(2));
    assertEquals(Types.INTEGER, array.elementType().jdbcType());
  }

  @Test
  public void testRawJavaObjectHeldAsObject() throws SQLException {
    final TypedValue value = TypedValue.ofLocal(Rep.OBJECT, LocalDate.of(2020, 1, 2));
    assertScalar(LocalDate.of(2020, 1, 2), Types.DATE, SqlValueFactory.fromTypedValue(value));
  }

  // Raw Java objects.

  @Test
  public void testJavaNull() throws SQLException {
    assertEquals(new SqlNull(SqlType.UNKNOWN), SqlValueFactory.fromJava(null));
  }

  @Test
  public void testJavaNumbersAndText() throws SQLException {
    assertScalar(new BigInteger("5"), Types.DECIMAL, SqlValueFactory.fromJava(new BigInteger("5")));
    assertScalar("c", Types.VARCHAR, SqlValueFactory.fromJava('c'));
    assertScalar("sb", Types.VARCHAR, SqlValueFactory.fromJava(new StringBuilder("sb")));
    final UUID uuid = UUID.randomUUID();
    final SqlScalar scalar = assertInstanceOf(SqlScalar.class, SqlValueFactory.fromJava(uuid));
    assertEquals(uuid, scalar.javaValue());
    assertEquals("UUID", scalar.type().typeName());
  }

  @Test
  public void testLegacyDateTimeTypesBecomeJavaTime() throws SQLException {
    assertScalar(
        LocalDate.of(2020, 1, 2),
        Types.DATE,
        SqlValueFactory.fromJava(java.sql.Date.valueOf("2020-01-02")));
    assertScalar(
        LocalTime.of(3, 4, 5), Types.TIME, SqlValueFactory.fromJava(Time.valueOf("03:04:05")));
    assertScalar(
        LocalDateTime.of(2020, 1, 2, 3, 4, 5, 6),
        Types.TIMESTAMP,
        SqlValueFactory.fromJava(Timestamp.valueOf(LocalDateTime.of(2020, 1, 2, 3, 4, 5, 6))));
    assertScalar(
        Timestamp.valueOf("2020-01-02 03:04:05").toLocalDateTime(),
        Types.TIMESTAMP,
        SqlValueFactory.fromJava(
            new java.util.Date(Timestamp.valueOf("2020-01-02 03:04:05").getTime())));
  }

  @Test
  public void testJavaTimeTypes() throws SQLException {
    assertScalar(
        LocalDate.of(2020, 1, 2), Types.DATE, SqlValueFactory.fromJava(LocalDate.of(2020, 1, 2)));
    assertScalar(
        LocalDateTime.of(2020, 1, 2, 3, 4),
        Types.TIMESTAMP,
        SqlValueFactory.fromJava(LocalDateTime.of(2020, 1, 2, 3, 4)));
    final OffsetDateTime offset = OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2));
    assertScalar(offset, Types.TIMESTAMP_WITH_TIMEZONE, SqlValueFactory.fromJava(offset));
    assertScalar(
        offset,
        Types.TIMESTAMP_WITH_TIMEZONE,
        SqlValueFactory.fromJava(
            ZonedDateTime.of(offset.toLocalDateTime(), ZoneOffset.ofHours(2))));
    assertScalar(
        OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC),
        Types.TIMESTAMP_WITH_TIMEZONE,
        SqlValueFactory.fromJava(
            ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneId.of("UTC")).toInstant()));
    assertScalar(
        OffsetDateTime.of(1970, 1, 1, 0, 0, 1, 0, ZoneOffset.UTC),
        Types.TIMESTAMP_WITH_TIMEZONE,
        SqlValueFactory.fromJava(Instant.ofEpochSecond(1)));
  }

  @Test
  public void testIntervals() throws SQLException {
    assertEquals(
        new SqlIntervalValue(0, 1, Duration.ofMinutes(90).toNanos()),
        SqlValueFactory.fromJava(Duration.ofHours(25).plusMinutes(30)));
    assertEquals(
        new SqlIntervalValue(0, 0, -Duration.ofMinutes(90).toNanos()),
        SqlValueFactory.fromJava(Duration.ofMinutes(-90)));
    assertEquals(
        new SqlIntervalValue(0, -1, -Duration.ofHours(1).toNanos()),
        SqlValueFactory.fromJava(Duration.ofHours(-25)));
    assertEquals(new SqlIntervalValue(14, 3, 0), SqlValueFactory.fromJava(Period.of(1, 2, 3)));
    assertEquals(new SqlIntervalValue(-14, 0, 0), SqlValueFactory.fromJava(Period.of(-1, -2, 0)));
    assertEquals(
        new SqlIntervalValue(14, 4, Duration.ofHours(1).toNanos()),
        SqlValueFactory.fromJava(
            new PeriodDuration(Period.of(1, 2, 3), Duration.ofDays(1).plusHours(1))));
  }

  @Test
  public void testIntervalOutOfRange() {
    assertThrows(
        SQLException.class,
        () -> SqlValueFactory.fromJava(Duration.ofDays(Long.MAX_VALUE / 100000)));
    assertThrows(
        SQLException.class, () -> SqlValueFactory.fromJava(Period.ofYears(Integer.MAX_VALUE)));
  }

  @Test
  public void testJavaArrays() throws SQLException {
    final SqlArray ints =
        assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(new int[] {1, 2}));
    assertEquals(Types.INTEGER, ints.elementType().jdbcType());
    assertEquals(2, ints.elements().size());

    final SqlArray strings =
        assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(new String[] {"a", null}));
    assertEquals(Types.VARCHAR, strings.elementType().jdbcType());
    // A null element takes the type of its array.
    assertEquals(new SqlNull(SqlType.of(Types.VARCHAR)), strings.elements().get(1));

    final SqlArray list =
        assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(List.of(1L, 2L)));
    assertEquals(Types.BIGINT, list.elementType().jdbcType());
  }

  @Test
  public void testByteArrayIsBinaryNotAnArray() throws SQLException {
    assertInstanceOf(SqlScalar.class, SqlValueFactory.fromJava(new byte[] {1}));
  }

  @Test
  public void testEmptyAndAllNullArraysHaveUnknownElementType() throws SQLException {
    final SqlArray empty = assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(new int[0]));
    assertTrue(empty.elementType().isUnknown());
    final SqlArray nulls =
        assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(new Object[] {null, null}));
    assertTrue(nulls.elementType().isUnknown());
  }

  @Test
  public void testSqlArrayUsesItsDeclaredElementType() throws SQLException {
    final SqlArray empty =
        assertInstanceOf(
            SqlArray.class,
            SqlValueFactory.fromJava(sqlArray(new Object[0], Types.INTEGER, "int4")));
    assertEquals(SqlType.of(Types.INTEGER, "int4"), empty.elementType());

    final SqlArray withNull =
        assertInstanceOf(
            SqlArray.class,
            SqlValueFactory.fromJava(sqlArray(new Object[] {1, null}, Types.INTEGER, "int4")));
    assertEquals(new SqlNull(SqlType.of(Types.INTEGER, "int4")), withNull.elements().get(1));
  }

  @Test
  public void testNestedArrays() throws SQLException {
    final SqlArray outer =
        assertInstanceOf(
            SqlArray.class, SqlValueFactory.fromJava(new Object[] {new int[] {1}, new int[] {2}}));
    assertEquals(Types.ARRAY, outer.elementType().jdbcType());
    assertEquals(Types.INTEGER, outer.elementType().elementType().jdbcType());
  }

  private static Struct struct(String typeName, Object... attributes) {
    return (Struct)
        Proxy.newProxyInstance(
            SqlValueFactoryTest.class.getClassLoader(),
            new Class<?>[] {Struct.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getSQLTypeName":
                  return typeName;
                case "getAttributes":
                  return attributes;
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  @Test
  public void testStruct() throws SQLException {
    final SqlRow row =
        assertInstanceOf(SqlRow.class, SqlValueFactory.fromJava(struct("pair", 1, "a", null)));
    assertEquals("pair", row.typeName());
    assertEquals(3, row.fields().size());
    // A JDBC Struct carries no field names.
    assertEquals(null, row.fields().get(0).name());
    assertScalar(1, Types.INTEGER, row.fields().get(0).value());
    assertScalar("a", Types.VARCHAR, row.fields().get(1).value());
    assertInstanceOf(SqlNull.class, row.fields().get(2).value());
  }

  @Test
  public void testStructsInsideArrays() throws SQLException {
    final SqlArray array =
        assertInstanceOf(
            SqlArray.class,
            SqlValueFactory.fromJava(new Object[] {struct("p", 1), struct("p", 2)}));
    assertEquals(Types.STRUCT, array.elementType().jdbcType());
    assertEquals("p", array.elementType().typeName());
  }

  /** A user-defined type. */
  private static final class Point implements SQLData {
    @Override
    public String getSQLTypeName() {
      return "point";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(3);
      stream.writeString("x");
    }
  }

  @Test
  public void testSqlData() throws SQLException {
    final SqlRow row = assertInstanceOf(SqlRow.class, SqlValueFactory.fromJava(new Point()));
    assertEquals("point", row.typeName());
    assertEquals(2, row.fields().size());
    assertScalar(3, Types.INTEGER, row.fields().get(0).value());
    assertScalar("x", Types.VARCHAR, row.fields().get(1).value());
  }

  // Large objects and streams.

  private static <T> T lob(
      Class<T> type, String lengthMethod, long length, String readMethod, Object content) {
    return type.cast(
        Proxy.newProxyInstance(
            SqlValueFactoryTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().equals(lengthMethod)) {
                return length;
              } else if (method.getName().equals(readMethod)) {
                return content;
              }
              throw new UnsupportedOperationException(method.getName());
            }));
  }

  @Test
  public void testLobsAndStreams() throws SQLException {
    assertScalar(
        "text",
        Types.CLOB,
        SqlValueFactory.fromJava(lob(Clob.class, "length", 4, "getSubString", "text")));
    final SqlScalar blob =
        assertInstanceOf(
            SqlScalar.class,
            SqlValueFactory.fromJava(lob(Blob.class, "length", 2, "getBytes", new byte[] {1, 2})));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) blob.javaValue());
    assertScalar("chars", Types.CLOB, SqlValueFactory.fromJava(new StringReader("chars")));
    final SqlScalar stream =
        assertInstanceOf(
            SqlScalar.class,
            SqlValueFactory.fromJava(new ByteArrayInputStream(new byte[] {7, 8, 9})));
    assertArrayEquals(new byte[] {7, 8, 9}, (byte[]) stream.javaValue());
  }

  @Test
  public void testOversizedLobIsRejected() {
    final long tooLarge = SqlValueFactory.MAX_LOB_LENGTH + 1L;
    assertThrows(
        SQLException.class,
        () -> SqlValueFactory.fromJava(lob(Clob.class, "length", tooLarge, "getSubString", "")));
    assertThrows(
        SQLException.class,
        () ->
            SqlValueFactory.fromJava(lob(Blob.class, "length", tooLarge, "getBytes", new byte[0])));
    assertThrows(
        SQLException.class,
        () ->
            SqlValueFactory.fromJava(
                new ByteArrayInputStream(new byte[SqlValueFactory.MAX_LOB_LENGTH + 1])));
  }

  @Test
  public void testUnsupportedTypesAreRejected() {
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> SqlValueFactory.fromJava(new java.util.HashMap<>()));
    assertThrows(
        SQLFeatureNotSupportedException.class, () -> SqlValueFactory.fromJava(new Object()));
  }

  @Test
  public void testSqlValuesPassThrough() throws SQLException {
    final SqlValue value = new SqlNull(SqlType.of(Types.INTEGER));
    assertEquals(value, SqlValueFactory.fromJava(value));
    assertEquals(List.of(1, 2), Arrays.asList(1, 2)); // keeps the Arrays import honest
  }

  // Values that depend on a time zone.

  @Test
  public void testDatesAreNotShiftedByAnotherCalendar() throws SQLException {
    final long millis = Instant.parse("2020-01-02T03:04:05.123Z").toEpochMilli();
    final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    assertScalar(
        LocalDate.of(2020, 1, 2),
        Types.DATE,
        fromSetter(site -> site.setDate(new java.sql.Date(millis), utc)));
    assertScalar(
        LocalTime.of(3, 4, 5, 123_000_000),
        Types.TIME,
        fromSetter(site -> site.setTime(new Time(millis), utc)));
    assertScalar(
        LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000),
        Types.TIMESTAMP,
        fromSetter(site -> site.setTimestamp(new Timestamp(millis), utc)));
  }

  @Test
  public void testJavaUtilDateIsTheWallClockValueOfTheDefaultZone() throws SQLException {
    final java.util.Date date =
        new java.util.Date(Instant.parse("2020-01-02T03:04:05.123Z").toEpochMilli());
    final TimeZone defaultTz = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      assertScalar(
          LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000),
          Types.TIMESTAMP,
          SqlValueFactory.fromJava(date));

      TimeZone.setDefault(TimeZone.getTimeZone("America/Vancouver"));
      assertScalar(
          LocalDateTime.of(2020, 1, 1, 19, 4, 5, 123_000_000),
          Types.TIMESTAMP,
          SqlValueFactory.fromJava(date));
    } finally {
      TimeZone.setDefault(defaultTz);
    }
  }

  @Test
  public void testSerialDateTimeValues() throws SQLException {
    final long millis = Instant.parse("2020-01-02T03:04:05.123Z").toEpochMilli();
    assertScalar(
        LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000),
        Types.TIMESTAMP,
        SqlValueFactory.fromTypedValue(TypedValue.ofSerial(Rep.JAVA_UTIL_DATE, millis)));
    assertScalar(
        LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_000_000),
        Types.TIMESTAMP,
        SqlValueFactory.fromTypedValue(TypedValue.ofSerial(Rep.JAVA_SQL_TIMESTAMP, -1L)));
    assertScalar(
        LocalTime.of(23, 59, 59, 999_000_000),
        Types.TIME,
        SqlValueFactory.fromTypedValue(TypedValue.ofSerial(Rep.JAVA_SQL_TIME, 86_399_999)));
    assertScalar(
        LocalDate.of(1969, 12, 31),
        Types.DATE,
        SqlValueFactory.fromTypedValue(TypedValue.ofSerial(Rep.JAVA_SQL_DATE, -1)));
  }

  @Test
  public void testJavaTimesOfDay() throws SQLException {
    assertScalar(
        LocalTime.of(3, 4, 5, 6), Types.TIME, SqlValueFactory.fromJava(LocalTime.of(3, 4, 5, 6)));
    final OffsetTime offset = OffsetTime.of(3, 4, 5, 0, ZoneOffset.ofHours(-2));
    assertScalar(offset, Types.TIME_WITH_TIMEZONE, SqlValueFactory.fromJava(offset));
  }

  // More values.

  @Test
  public void testAvaticaByteString() throws SQLException {
    final SqlScalar scalar =
        assertInstanceOf(
            SqlScalar.class, SqlValueFactory.fromJava(new ByteString(new byte[] {1, 2})));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) scalar.javaValue());
    assertEquals(Types.VARBINARY, scalar.type().jdbcType());
  }

  @Test
  public void testIntervalsWithMixedSigns() throws SQLException {
    // The parts keep their own signs; whether they can be written together is up to the dialect.
    assertEquals(
        new SqlIntervalValue(0, 1, -Duration.ofHours(1).toNanos()),
        SqlValueFactory.fromJava(new PeriodDuration(Period.ofDays(1), Duration.ofHours(-1))));
    assertEquals(
        new SqlIntervalValue(0, 0, 0),
        SqlValueFactory.fromJava(new PeriodDuration(Period.ofDays(1), Duration.ofDays(-1))));
    assertEquals(new SqlIntervalValue(10, 0, 0), SqlValueFactory.fromJava(Period.of(1, -2, 0)));
    assertEquals(new SqlIntervalValue(0, 0, 0), SqlValueFactory.fromJava(Duration.ZERO));
    assertEquals(new SqlIntervalValue(0, 0, 1), SqlValueFactory.fromJava(Duration.ofNanos(1)));
  }

  @Test
  public void testSqlArrayThatDoesNotTellItsElementType() throws SQLException {
    final java.sql.Array failing = mock(java.sql.Array.class);
    when(failing.getArray()).thenReturn(new Object[] {1, null});
    when(failing.getBaseType()).thenThrow(new SQLFeatureNotSupportedException());
    final SqlArray inferred = assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(failing));
    assertEquals(SqlType.of(Types.INTEGER), inferred.elementType());
    assertEquals(new SqlNull(SqlType.of(Types.INTEGER)), inferred.elements().get(1));

    final SqlArray other =
        assertInstanceOf(
            SqlArray.class,
            SqlValueFactory.fromJava(sqlArray(new Object[] {"a"}, Types.OTHER, null)));
    assertEquals(SqlType.of(Types.VARCHAR), other.elementType());

    final SqlArray empty =
        assertInstanceOf(
            SqlArray.class, SqlValueFactory.fromJava(sqlArray(new Object[0], Types.NULL, null)));
    assertTrue(empty.elementType().isUnknown());
  }

  @Test
  public void testSqlArrayOfPrimitives() throws SQLException {
    final java.sql.Array array = mock(java.sql.Array.class);
    when(array.getArray()).thenReturn(new long[] {1L, 2L});
    when(array.getBaseType()).thenReturn(Types.BIGINT);
    when(array.getBaseTypeName()).thenReturn("BIGINT");
    final SqlArray longs = assertInstanceOf(SqlArray.class, SqlValueFactory.fromJava(array));
    assertEquals(SqlType.of(Types.BIGINT, "BIGINT"), longs.elementType());
    assertScalar(2L, Types.BIGINT, longs.elements().get(1));
  }

  @Test
  public void testSqlArrayThatDoesNotHoldAnArrayIsRejected() throws SQLException {
    final java.sql.Array array = mock(java.sql.Array.class);
    when(array.getArray()).thenReturn("not an array");
    assertThrows(SQLFeatureNotSupportedException.class, () -> SqlValueFactory.fromJava(array));
  }

  @Test
  public void testUnsupportedElementIsRejected() {
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> SqlValueFactory.fromJava(new Object[] {1, new Object()}));
    assertThrows(
        SQLFeatureNotSupportedException.class,
        () -> SqlValueFactory.fromJava(struct("pair", 1, new Object())));
  }

  @Test
  public void testAvaticaArrayWithoutAComponentType() throws SQLException {
    final SqlArray array =
        assertInstanceOf(
            SqlArray.class,
            SqlValueFactory.fromTypedValue(
                TypedValue.ofSerial(Rep.ARRAY, Arrays.asList(1, null, 3))));
    assertEquals(Types.INTEGER, array.elementType().jdbcType());
    assertScalar(1, Types.INTEGER, array.elements().get(0));
    assertEquals(new SqlNull(SqlType.of(Types.INTEGER)), array.elements().get(1));
  }

  @Test
  public void testStructsInsideStructs() throws SQLException {
    final SqlRow row =
        assertInstanceOf(
            SqlRow.class, SqlValueFactory.fromJava(struct("outer", struct("inner", 1), 2)));
    final SqlRow inner = assertInstanceOf(SqlRow.class, row.fields().get(0).value());
    assertEquals("inner", inner.typeName());
    assertScalar(1, Types.INTEGER, inner.fields().get(0).value());
    assertScalar(2, Types.INTEGER, row.fields().get(1).value());
  }

  @Test
  public void testStructWithoutAttributes() throws SQLException {
    final SqlRow row = assertInstanceOf(SqlRow.class, SqlValueFactory.fromJava(struct("empty")));
    assertTrue(row.fields().isEmpty());
  }

  /** A user-defined type made of other user-defined types. */
  private static final class Line implements SQLData {
    @Override
    public String getSQLTypeName() {
      return "line";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeObject(new Point());
      stream.writeObject(new Point());
      stream.writeBoolean(true);
    }
  }

  @Test
  public void testSqlDataInsideSqlData() throws SQLException {
    final SqlRow row = assertInstanceOf(SqlRow.class, SqlValueFactory.fromJava(new Line()));
    assertEquals("line", row.typeName());
    assertEquals(3, row.fields().size());
    final SqlRow point = assertInstanceOf(SqlRow.class, row.fields().get(0).value());
    assertEquals("point", point.typeName());
    assertScalar(3, Types.INTEGER, point.fields().get(0).value());
    assertScalar(true, Types.BOOLEAN, row.fields().get(2).value());
  }

  @Test
  public void testLobsAndStreamsAtTheLimit() throws SQLException {
    final int limit = SqlValueFactory.MAX_LOB_LENGTH;
    assertScalar(
        "text",
        Types.CLOB,
        SqlValueFactory.fromJava(lob(Clob.class, "length", limit, "getSubString", "text")));
    final SqlScalar stream =
        assertInstanceOf(
            SqlScalar.class, SqlValueFactory.fromJava(new ByteArrayInputStream(new byte[limit])));
    assertEquals(limit, ((byte[]) stream.javaValue()).length);
    final SqlScalar reader =
        assertInstanceOf(
            SqlScalar.class, SqlValueFactory.fromJava(new StringReader("x".repeat(limit))));
    assertEquals(limit, ((String) reader.javaValue()).length());
  }

  @Test
  public void testOversizedReaderIsRejected() {
    assertThrows(
        SQLException.class,
        () ->
            SqlValueFactory.fromJava(
                new StringReader("x".repeat(SqlValueFactory.MAX_LOB_LENGTH + 1))));
  }

  @Test
  public void testEmptyLobsAndStreams() throws SQLException {
    assertScalar("", Types.CLOB, SqlValueFactory.fromJava(new StringReader("")));
    final SqlScalar stream =
        assertInstanceOf(
            SqlScalar.class, SqlValueFactory.fromJava(new ByteArrayInputStream(new byte[0])));
    assertEquals(0, ((byte[]) stream.javaValue()).length);
  }
}
