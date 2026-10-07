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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLOutput;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;
import org.apache.arrow.driver.jdbc.dialect.SqlLiterals;
import org.apache.arrow.driver.jdbc.dialect.value.SqlArray;
import org.apache.arrow.driver.jdbc.dialect.value.SqlIntervalValue;
import org.apache.arrow.driver.jdbc.dialect.value.SqlNull;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRow;
import org.apache.arrow.driver.jdbc.dialect.value.SqlRowField;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.apache.arrow.vector.PeriodDuration;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.calcite.avatica.util.ByteString;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the {@link SqlValue} for a statement parameter, normalizing what JDBC and Avatica hand
 * over so a {@link org.apache.arrow.driver.jdbc.dialect.SqlDialect} only deals with one model.
 *
 * <p>Avatica keeps dates, times and timestamps set through a {@link TypedValue} as numbers that
 * hold the wall-clock value in the statement's calendar zone, so they convert to {@code java.time}
 * types without any time zone arithmetic. A {@code java.sql} or {@code java.util} date handed over
 * as an object, or through {@link #fromDate}, {@link #fromTime} and {@link #fromTimestamp}, keeps
 * its full precision and is read in the zone of the given {@link Calendar}, or in the default zone
 * of the JVM if there is none, as JDBC requires.
 */
public final class SqlValueFactory {
  /** The largest LOB or stream parameter, in bytes or characters, that is rendered inline. */
  public static final int MAX_LOB_LENGTH = 16 * 1024 * 1024;

  private static final SqlNull UNKNOWN_NULL = new SqlNull(SqlType.UNKNOWN);

  private SqlValueFactory() {}

  /**
   * Builds the value of a parameter set through Avatica.
   *
   * @param typedValue the parameter, or {@code null} for an explicit null.
   * @return the value.
   * @throws SQLException if the value cannot be represented.
   */
  public static SqlValue fromTypedValue(@Nullable TypedValue typedValue) throws SQLException {
    if (typedValue == null || typedValue.value == null || typedValue.type == null) {
      return UNKNOWN_NULL;
    }
    final Object serial = typedValue.value;
    switch (typedValue.type) {
      case OBJECT:
        // The driver stores raw Java objects that Avatica cannot represent as OBJECT values, and
        // toLocal() would reject them.
        return fromJava(serial);
      case JAVA_SQL_DATE:
        return scalar(LocalDate.ofEpochDay(((Number) serial).longValue()), Types.DATE);
      case JAVA_SQL_TIME:
        return scalar(
            LocalTime.ofNanoOfDay(((Number) serial).longValue() * 1_000_000L), Types.TIME);
      case JAVA_SQL_TIMESTAMP:
      case JAVA_UTIL_DATE:
        return fromEpochMillis(((Number) serial).longValue());
      case ARRAY:
        return fromAvaticaArray(typedValue);
      default:
        return fromJava(typedValue.toLocal());
    }
  }

  private static SqlValue fromAvaticaArray(TypedValue typedValue) throws SQLException {
    final Object serial = typedValue.value;
    if (serial instanceof java.sql.Array array) {
      return fromJava(array);
    }
    if (!(serial instanceof List<?> list)) {
      throw new SQLFeatureNotSupportedException(
          "Cannot render an array parameter held as " + serial.getClass().getName());
    }
    final List<SqlValue> elements = new ArrayList<>(list.size());
    for (Object element : list) {
      if (element == null) {
        elements.add(UNKNOWN_NULL);
      } else if (typedValue.componentType == null || element instanceof List<?>) {
        elements.add(fromJava(element));
      } else {
        elements.add(fromTypedValue(TypedValue.ofSerial(typedValue.componentType, element)));
      }
    }
    return arrayOf(elements, SqlType.UNKNOWN);
  }

  /**
   * Builds the value of a date.
   *
   * @param date the date.
   * @param calendar the calendar whose time zone gives the year, month and day, or {@code null} for
   *     the default time zone of the JVM. A Gregorian calendar also contributes its cutover date.
   * @return the value.
   * @throws SQLException if the date cannot be represented.
   */
  public static SqlValue fromDate(java.sql.Date date, @Nullable Calendar calendar)
      throws SQLException {
    try {
      return scalar(
          calendar == null ? date.toLocalDate() : localDate(at(calendar, date.getTime())),
          Types.DATE);
    } catch (DateTimeException e) {
      throw new SQLException("Cannot represent the date " + date, e);
    }
  }

  /**
   * Builds the value of a time of day, with milliseconds.
   *
   * @param time the time.
   * @param calendar the calendar whose time zone gives the hour, or {@code null} for the default
   *     time zone of the JVM.
   * @return the value.
   */
  public static SqlValue fromTime(Time time, @Nullable Calendar calendar) {
    final long millis = time.getTime();
    final LocalTime local =
        calendar == null
            ? time.toLocalTime().withNano(millisOf(millis) * 1_000_000)
            : localTime(at(calendar, millis), millisOf(millis) * 1_000_000);
    return scalar(local, Types.TIME);
  }

  /**
   * Builds the value of a timestamp, with its full precision of nanoseconds.
   *
   * @param timestamp the timestamp.
   * @param calendar the calendar whose time zone gives the wall-clock value, or {@code null} for
   *     the default time zone of the JVM. A Gregorian calendar also contributes its cutover date.
   * @return the value.
   * @throws SQLException if the timestamp cannot be represented.
   */
  public static SqlValue fromTimestamp(Timestamp timestamp, @Nullable Calendar calendar)
      throws SQLException {
    try {
      return scalar(
          calendar == null
              ? timestamp.toLocalDateTime()
              : localDateTime(calendar, timestamp.getTime(), timestamp.getNanos()),
          Types.TIMESTAMP);
    } catch (DateTimeException e) {
      throw new SQLException("Cannot represent the timestamp " + timestamp, e);
    }
  }

  private static SqlValue fromCalendar(Calendar calendar) throws SQLException {
    final long millis = calendar.getTimeInMillis();
    try {
      return scalar(localDateTime(calendar, millis, millisOf(millis) * 1_000_000), Types.TIMESTAMP);
    } catch (DateTimeException e) {
      throw new SQLException("Cannot represent the calendar " + calendar.getTime(), e);
    }
  }

  /**
   * Builds the value for a Java object that is set with a target SQL type, converting it as table
   * B-5 of JDBC 4.2 describes.
   *
   * <p>If the target is a date or time type, the object is converted to it. A date, time or
   * timestamp object is written as ISO text if the target is {@code CHAR}, {@code VARCHAR} or
   * {@code LONGVARCHAR}. Any other target is ignored, and the value is the one {@link
   * #fromJava(Object)} builds.
   *
   * @param value the object.
   * @param targetSqlType the {@link Types} constant.
   * @return the value.
   * @throws SQLException if the object cannot be converted to the date or time type.
   */
  public static SqlValue fromJava(@Nullable Object value, int targetSqlType) throws SQLException {
    if (value == null) {
      return UNKNOWN_NULL;
    }
    if (isDateTimeType(targetSqlType)) {
      return toDateTime(value, targetSqlType);
    }
    final SqlValue natural = fromJava(value);
    if (isCharacterType(targetSqlType)
        && natural instanceof SqlScalar scalar
        && isDateTimeType(scalar.type().jdbcType())) {
      return scalar(isoText(scalar.javaValue()), targetSqlType);
    }
    return natural;
  }

  /**
   * Tells whether a {@link Types} constant is {@code DATE}, {@code TIME}, {@code TIMESTAMP} or a
   * variant with time zone.
   */
  public static boolean isDateTimeType(int jdbcType) {
    return jdbcType == Types.DATE
        || jdbcType == Types.TIME
        || jdbcType == Types.TIMESTAMP
        || jdbcType == Types.TIME_WITH_TIMEZONE
        || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE;
  }

  private static boolean isCharacterType(int jdbcType) {
    return jdbcType == Types.CHAR || jdbcType == Types.VARCHAR || jdbcType == Types.LONGVARCHAR;
  }

  private static SqlValue toDateTime(Object value, int target) throws SQLException {
    Object source;
    if (value instanceof String text) {
      source = text;
    } else {
      final SqlValue natural = fromJava(value);
      if (!(natural instanceof SqlScalar scalar) || !isDateTimeType(scalar.type().jdbcType())) {
        throw unsupportedConversion(value, target);
      }
      source = scalar.javaValue();
      // A legacy date or time stands for the full timestamp, whatever the target.
      if (value instanceof java.util.Date || value instanceof Calendar) {
        if (source instanceof LocalDate date) {
          source = date.atStartOfDay();
        } else if (source instanceof LocalTime time) {
          source = time.atDate(LocalDate.EPOCH);
        }
      }
    }
    try {
      final Object converted = convertDateTime(source, target);
      if (converted != null) {
        return scalar(converted, target);
      }
    } catch (DateTimeException | IllegalArgumentException e) {
      throw new SQLException("Cannot convert the value " + value + " to JDBC type " + target, e);
    }
    throw unsupportedConversion(value, target);
  }

  /** Returns the converted value, or {@code null} if the conversion is not in table B-5. */
  private static @Nullable Object convertDateTime(Object source, int target) {
    switch (target) {
      case Types.DATE:
        if (source instanceof LocalDate) {
          return source;
        } else if (source instanceof LocalDateTime dateTime) {
          return dateTime.toLocalDate();
        } else if (source instanceof String text) {
          return java.sql.Date.valueOf(text).toLocalDate();
        }
        return null;
      case Types.TIME:
        if (source instanceof LocalTime) {
          return source;
        } else if (source instanceof LocalDateTime dateTime) {
          return dateTime.toLocalTime();
        } else if (source instanceof String text) {
          return Time.valueOf(text).toLocalTime();
        }
        return null;
      case Types.TIMESTAMP:
        if (source instanceof LocalDateTime) {
          return source;
        } else if (source instanceof String text) {
          return Timestamp.valueOf(text).toLocalDateTime();
        }
        return null;
      case Types.TIME_WITH_TIMEZONE:
        if (source instanceof OffsetTime) {
          return source;
        } else if (source instanceof OffsetDateTime dateTime) {
          return dateTime.toOffsetTime();
        } else if (source instanceof String text) {
          return OffsetTime.parse(text);
        }
        return null;
      case Types.TIMESTAMP_WITH_TIMEZONE:
        if (source instanceof OffsetDateTime) {
          return source;
        } else if (source instanceof String text) {
          return OffsetDateTime.parse(text);
        }
        return null;
      default:
        return null;
    }
  }

  private static SQLFeatureNotSupportedException unsupportedConversion(Object value, int target) {
    return new SQLFeatureNotSupportedException(
        "Cannot convert a parameter of type "
            + value.getClass().getName()
            + " to JDBC type "
            + target);
  }

  /**
   * The ISO text of a date or time value, with the offset of a value that has one in whole minutes.
   */
  private static String isoText(Object value) throws SQLException {
    if (value instanceof LocalDate date) {
      return SqlLiterals.dateText(date);
    } else if (value instanceof LocalTime time) {
      return SqlLiterals.timeText(time);
    } else if (value instanceof LocalDateTime dateTime) {
      return SqlLiterals.dateText(dateTime.toLocalDate())
          + " "
          + SqlLiterals.timeText(dateTime.toLocalTime());
    } else if (value instanceof OffsetTime time) {
      final OffsetTime whole = SqlLiterals.withWholeMinuteOffset(time);
      return SqlLiterals.timeText(whole.toLocalTime()) + SqlLiterals.offsetText(whole.getOffset());
    }
    final OffsetDateTime whole = SqlLiterals.withWholeMinuteOffset((OffsetDateTime) value);
    return SqlLiterals.dateText(whole.toLocalDate())
        + " "
        + SqlLiterals.timeText(whole.toLocalTime())
        + SqlLiterals.offsetText(whole.getOffset());
  }

  /**
   * A copy of the calendar set to an instant. The copy of a Gregorian calendar keeps its
   * Julian-Gregorian cutover; a calendar of another system, such as the Buddhist one, only
   * contributes its time zone, as its year and era are not those of a SQL date.
   */
  private static Calendar at(Calendar calendar, long millis) {
    final Calendar copy =
        "gregory".equals(calendar.getCalendarType())
            ? (Calendar) calendar.clone()
            : new GregorianCalendar(calendar.getTimeZone());
    copy.setTimeInMillis(millis);
    return copy;
  }

  private static int millisOf(long epochMillis) {
    return (int) Math.floorMod(epochMillis, 1000L);
  }

  private static LocalDate localDate(Calendar calendar) {
    final int year = calendar.get(Calendar.YEAR);
    return LocalDate.of(
        calendar.get(Calendar.ERA) == GregorianCalendar.BC ? 1 - year : year,
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH));
  }

  private static LocalTime localTime(Calendar calendar, int nanos) {
    return LocalTime.of(
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        calendar.get(Calendar.SECOND),
        nanos);
  }

  /** The wall-clock value of an instant in the zone of the calendar, with the given nanoseconds. */
  private static LocalDateTime localDateTime(Calendar calendar, long epochMillis, int nanos) {
    final Calendar copy = at(calendar, epochMillis);
    return localDate(copy).atTime(localTime(copy, nanos));
  }

  /**
   * Builds the value for a Java object.
   *
   * @param value the object: a JDBC value, a {@code java.time} value, a {@link UUID}, a Java array
   *     or collection, a {@link java.sql.Array}, {@link Struct} or {@link SQLData}, a LOB or a
   *     stream, or an interval ({@link Duration}, {@link Period} or {@link PeriodDuration}).
   * @return the value.
   * @throws SQLException if the value cannot be represented.
   */
  public static SqlValue fromJava(@Nullable Object value) throws SQLException {
    if (value == null) {
      return UNKNOWN_NULL;
    } else if (value instanceof SqlValue sqlValue) {
      return sqlValue;
    } else if (value instanceof Boolean) {
      return scalar(value, Types.BOOLEAN);
    } else if (value instanceof Byte) {
      return scalar(value, Types.TINYINT);
    } else if (value instanceof Short) {
      return scalar(value, Types.SMALLINT);
    } else if (value instanceof Integer) {
      return scalar(value, Types.INTEGER);
    } else if (value instanceof Long) {
      return scalar(value, Types.BIGINT);
    } else if (value instanceof Float) {
      return scalar(value, Types.REAL);
    } else if (value instanceof Double) {
      return scalar(value, Types.DOUBLE);
    } else if (value instanceof BigInteger || value instanceof BigDecimal) {
      return scalar(value, Types.DECIMAL);
    } else if (value instanceof Character || value instanceof CharSequence) {
      return scalar(value.toString(), Types.VARCHAR);
    } else if (value instanceof byte[]) {
      return scalar(value, Types.VARBINARY);
    } else if (value instanceof ByteString bytes) {
      return scalar(bytes.getBytes(), Types.VARBINARY);
    } else if (value instanceof UUID) {
      return new SqlScalar(value, SqlType.of(Types.OTHER, "UUID"));
    } else if (value instanceof java.sql.Date date) {
      return fromDate(date, null);
    } else if (value instanceof Time time) {
      return fromTime(time, null);
    } else if (value instanceof Timestamp timestamp) {
      return fromTimestamp(timestamp, null);
    } else if (value instanceof java.util.Date date) {
      return fromTimestamp(new Timestamp(date.getTime()), null);
    } else if (value instanceof Calendar calendar) {
      return fromCalendar(calendar);
    } else if (value instanceof LocalDate) {
      return scalar(value, Types.DATE);
    } else if (value instanceof LocalTime) {
      return scalar(value, Types.TIME);
    } else if (value instanceof LocalDateTime) {
      return scalar(value, Types.TIMESTAMP);
    } else if (value instanceof OffsetTime) {
      return scalar(value, Types.TIME_WITH_TIMEZONE);
    } else if (value instanceof OffsetDateTime) {
      return scalar(value, Types.TIMESTAMP_WITH_TIMEZONE);
    } else if (value instanceof ZonedDateTime zoned) {
      return scalar(zoned.toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
    } else if (value instanceof Instant instant) {
      return scalar(instant.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    } else if (value instanceof Duration duration) {
      return interval(Period.ZERO, duration);
    } else if (value instanceof Period period) {
      return interval(period, Duration.ZERO);
    } else if (value instanceof PeriodDuration periodDuration) {
      return interval(periodDuration.getPeriod(), periodDuration.getDuration());
    } else if (value instanceof java.sql.Array array) {
      return fromSqlArray(array);
    } else if (value instanceof Struct struct) {
      return fromStruct(struct);
    } else if (value instanceof SQLData data) {
      return fromSqlData(data);
    } else if (value instanceof Clob clob) {
      return scalar(readClob(clob), Types.CLOB);
    } else if (value instanceof Blob blob) {
      return scalar(readBlob(blob), Types.BLOB);
    } else if (value instanceof SQLXML xml) {
      return scalar(xml.getString(), Types.SQLXML);
    } else if (value instanceof Reader reader) {
      return scalar(readReader(reader), Types.CLOB);
    } else if (value instanceof InputStream stream) {
      return scalar(readStream(stream), Types.BLOB);
    } else if (value instanceof Collection<?> collection) {
      return fromElements(collection.toArray());
    } else if (value.getClass().isArray()) {
      final int length = java.lang.reflect.Array.getLength(value);
      final Object[] elements = new Object[length];
      for (int i = 0; i < length; i++) {
        elements[i] = java.lang.reflect.Array.get(value, i);
      }
      return fromElements(elements);
    }
    throw new SQLFeatureNotSupportedException(
        "Cannot render a parameter of type " + value.getClass().getName());
  }

  private static SqlScalar scalar(Object value, int jdbcType) {
    return new SqlScalar(value, SqlType.of(jdbcType));
  }

  private static SqlValue fromEpochMillis(long millis) {
    return scalar(
        LocalDateTime.ofEpochSecond(
            Math.floorDiv(millis, 1000L),
            (int) Math.floorMod(millis, 1000L) * 1_000_000,
            ZoneOffset.UTC),
        Types.TIMESTAMP);
  }

  private static SqlIntervalValue interval(Period period, Duration duration) throws SQLException {
    try {
      final int months = Math.toIntExact(period.toTotalMonths());
      final Duration abs = duration.abs();
      long durationDays = abs.toDays();
      long nanos = abs.minusDays(durationDays).toNanos();
      if (duration.isNegative()) {
        durationDays = -durationDays;
        nanos = -nanos;
      }
      return new SqlIntervalValue(months, Math.toIntExact(period.getDays() + durationDays), nanos);
    } catch (ArithmeticException e) {
      throw new SQLException("Interval parameter is out of range", e);
    }
  }

  private static SqlValue fromSqlArray(java.sql.Array array) throws SQLException {
    final Object elements = array.getArray();
    SqlType declared = SqlType.UNKNOWN;
    try {
      final int baseType = array.getBaseType();
      final String baseTypeName = array.getBaseTypeName();
      if (baseTypeName != null || (baseType != Types.NULL && baseType != Types.OTHER)) {
        declared = SqlType.of(baseType, baseTypeName);
      }
    } catch (SQLException | RuntimeException e) {
      // The element type is optional information.
    }
    final SqlValue value = fromJava(elements);
    if (value instanceof SqlArray result) {
      return declared.isUnknown() ? result : arrayOf(result.elements(), declared);
    }
    throw new SQLFeatureNotSupportedException("Unsupported java.sql.Array contents");
  }

  private static SqlArray fromElements(Object[] elements) throws SQLException {
    final List<SqlValue> values = new ArrayList<>(elements.length);
    for (Object element : elements) {
      values.add(fromJava(element));
    }
    return arrayOf(values, SqlType.UNKNOWN);
  }

  /** The element type is the declared one if known, otherwise that of the first non-null value. */
  private static SqlArray arrayOf(List<SqlValue> values, SqlType declared) {
    SqlType elementType = declared;
    if (elementType.isUnknown()) {
      for (SqlValue value : values) {
        if (!(value instanceof SqlNull)) {
          elementType = value.type();
          break;
        }
      }
    }
    final List<SqlValue> typed = new ArrayList<>(values.size());
    for (SqlValue value : values) {
      typed.add(value instanceof SqlNull ? new SqlNull(elementType) : value);
    }
    return new SqlArray(elementType, typed);
  }

  private static SqlRow fromStruct(Struct struct) throws SQLException {
    final List<SqlRowField> fields = new ArrayList<>();
    for (Object attribute : struct.getAttributes()) {
      fields.add(new SqlRowField(null, fromJava(attribute)));
    }
    return new SqlRow(struct.getSQLTypeName(), fields);
  }

  private static SqlRow fromSqlData(SQLData data) throws SQLException {
    final List<Object> attributes = new ArrayList<>();
    final SQLOutput output =
        (SQLOutput)
            Proxy.newProxyInstance(
                SqlValueFactory.class.getClassLoader(),
                new Class<?>[] {SQLOutput.class},
                (proxy, method, args) -> {
                  if (method.getName().startsWith("write") && args != null && args.length > 0) {
                    attributes.add(args[0]);
                  }
                  return null;
                });
    data.writeSQL(output);
    final List<SqlRowField> fields = new ArrayList<>(attributes.size());
    for (Object attribute : attributes) {
      fields.add(new SqlRowField(null, fromJava(attribute)));
    }
    return new SqlRow(data.getSQLTypeName(), fields);
  }

  private static String readClob(Clob clob) throws SQLException {
    final long length = clob.length();
    checkLobLength(length);
    return clob.getSubString(1, (int) length);
  }

  private static byte[] readBlob(Blob blob) throws SQLException {
    final long length = blob.length();
    checkLobLength(length);
    return blob.getBytes(1, (int) length);
  }

  private static String readReader(Reader reader) throws SQLException {
    final StringBuilder sb = new StringBuilder();
    final char[] buffer = new char[8192];
    try {
      int read;
      while ((read = reader.read(buffer)) >= 0) {
        sb.append(buffer, 0, read);
        checkLobLength(sb.length());
      }
    } catch (IOException e) {
      throw new SQLException("Failed to read a character stream parameter", e);
    }
    return sb.toString();
  }

  private static byte[] readStream(InputStream stream) throws SQLException {
    try {
      final byte[] bytes = stream.readNBytes(MAX_LOB_LENGTH + 1);
      checkLobLength(bytes.length);
      return bytes;
    } catch (IOException e) {
      throw new SQLException("Failed to read a binary stream parameter", e);
    }
  }

  private static void checkLobLength(long length) throws SQLException {
    if (length > MAX_LOB_LENGTH) {
      throw new SQLException(
          String.format(
              "Parameter of %s is larger than the %s allowed when rendering it into the statement",
              length, MAX_LOB_LENGTH));
    }
  }
}
