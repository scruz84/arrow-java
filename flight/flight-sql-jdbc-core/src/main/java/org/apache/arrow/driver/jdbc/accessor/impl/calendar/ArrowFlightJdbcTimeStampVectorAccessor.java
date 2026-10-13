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
package org.apache.arrow.driver.jdbc.accessor.impl.calendar;

import static org.apache.arrow.driver.jdbc.accessor.impl.calendar.ArrowFlightJdbcTimeStampVectorGetter.Getter;
import static org.apache.arrow.driver.jdbc.accessor.impl.calendar.ArrowFlightJdbcTimeStampVectorGetter.Holder;
import static org.apache.arrow.driver.jdbc.accessor.impl.calendar.ArrowFlightJdbcTimeStampVectorGetter.createGetter;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.apache.arrow.driver.jdbc.accessor.ArrowFlightJdbcAccessor;
import org.apache.arrow.driver.jdbc.accessor.ArrowFlightJdbcAccessorFactory;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.util.DateUtility;

/** Accessor for the Arrow types extending from {@link TimeStampVector}. */
public class ArrowFlightJdbcTimeStampVectorAccessor extends ArrowFlightJdbcAccessor {

  private final ZoneId zoneId;
  private final TimeZone timeZone;
  private final Getter getter;
  private final TimeUnit timeUnit;
  private final LongToLocalDateTime longToLocalDateTime;
  private final Holder holder;
  private final boolean isZoned;

  /** Functional interface used to convert a number (in any time resolution) to LocalDateTime. */
  interface LongToLocalDateTime {
    LocalDateTime fromLong(long value);
  }

  /** Instantiate a ArrowFlightJdbcTimeStampVectorAccessor for given vector. */
  public ArrowFlightJdbcTimeStampVectorAccessor(
      TimeStampVector vector,
      IntSupplier currentRowSupplier,
      ArrowFlightJdbcAccessorFactory.WasNullConsumer setCursorWasNull) {
    super(currentRowSupplier, setCursorWasNull);
    this.holder = new Holder();
    this.getter = createGetter(vector);

    // whether the vector included TZ info
    this.isZoned = getVectorIsZoned(vector);
    // non-null, either the vector TZ or default to UTC
    this.zoneId = getZoneIdForVector(vector);
    this.timeZone = TimeZone.getTimeZone(this.zoneId);
    this.timeUnit = getTimeUnitForVector(vector);
    this.longToLocalDateTime = getLongToLocalDateTimeForVector(vector, this.timeZone);
  }

  @Override
  public Class<?> getObjectClass() {
    return Timestamp.class;
  }

  @Override
  public <T> T getObject(final Class<T> type) throws SQLException {
    final Object value;
    if (!this.isZoned
        & Set.of(OffsetDateTime.class, ZonedDateTime.class, Instant.class).contains(type)) {
      throw new SQLException(
          "Vectors without timezones can't be converted to objects with offset/tz info.");
    } else if (type == OffsetDateTime.class) {
      value = getOffsetDateTime();
    } else if (type == LocalDateTime.class) {
      value = getLocalDateTime();
    } else if (type == ZonedDateTime.class) {
      value = getZonedDateTime();
    } else if (type == Instant.class) {
      value = getInstant();
    } else if (type == Timestamp.class) {
      value = getObject();
    } else {
      throw new SQLException("Object type not supported for TimeStamp Vector");
    }

    return !type.isPrimitive() && wasNull ? null : type.cast(value);
  }

  @Override
  public Object getObject() {
    return this.getTimestamp(null);
  }

  private ZonedDateTime getZonedDateTime() {
    final Instant instant = getInstant();
    if (instant == null) {
      return null;
    }

    return instant.atZone(this.zoneId);
  }

  private OffsetDateTime getOffsetDateTime() {
    final ZonedDateTime zonedDateTime = getZonedDateTime();
    if (zonedDateTime == null) {
      return null;
    }

    return zonedDateTime.toOffsetDateTime();
  }

  /**
   * Get the point in time of the value. It is calculated from the stored epoch value, as the wall
   * clock of a TZ is ambiguous in the DST overlaps.
   */
  private Instant getInstant() {
    if (!fillHolder()) {
      return null;
    }

    final long value = holder.value;
    switch (this.timeUnit) {
      case SECONDS:
        return Instant.ofEpochSecond(value);
      case MILLISECONDS:
        return Instant.ofEpochMilli(value);
      case MICROSECONDS:
        return Instant.ofEpochSecond(
            Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1_000L);
      case NANOSECONDS:
        return Instant.ofEpochSecond(
            Math.floorDiv(value, 1_000_000_000L), Math.floorMod(value, 1_000_000_000L));
      default:
        throw new UnsupportedOperationException("Invalid Arrow time unit");
    }
  }

  /**
   * Get the wall-clock date and time of the value: the one at the vector TZ when it has TZ info,
   * the stored value otherwise.
   */
  private LocalDateTime getLocalDateTime() {
    if (!fillHolder()) {
      return null;
    }

    return this.longToLocalDateTime.fromLong(holder.value);
  }

  private boolean fillHolder() {
    getter.get(getCurrentRow(), holder);
    this.wasNull = holder.isSet == 0;
    this.wasNullConsumer.setWasNull(this.wasNull);
    return !this.wasNull;
  }

  @Override
  public Date getDate(Calendar calendar) {
    final Timestamp timestamp = getTimestamp(calendar);
    if (timestamp == null) {
      return null;
    }

    return new Date(timestamp.getTime());
  }

  @Override
  public Time getTime(Calendar calendar) {
    final Timestamp timestamp = getTimestamp(calendar);
    if (timestamp == null) {
      return null;
    }

    return new Time(timestamp.getTime());
  }

  /**
   * Get the value as a Timestamp.
   *
   * <ul>
   *   <li>Vectors with TZ info hold a point in time, which is returned as is. The Calendar has no
   *       effect.
   *   <li>Vectors without TZ info hold a wall-clock date and time with no zone attached, so a
   *       Calendar (if provided) defines the zone in which that wall-clock is interpreted, as
   *       stated by the JDBC contract of getTimestamp(Calendar), getDate(Calendar) and
   *       getTime(Calendar). Without Calendar the default zone is used.
   * </ul>
   */
  @Override
  public Timestamp getTimestamp(Calendar calendar) {
    if (this.isZoned) {
      final Instant instant = getInstant();
      return instant == null ? null : Timestamp.from(instant);
    }

    final LocalDateTime localDateTime = getLocalDateTime();
    if (localDateTime == null) {
      return null;
    }

    if (calendar != null) {
      return Timestamp.from(localDateTime.atZone(calendar.getTimeZone().toZoneId()).toInstant());
    }
    return Timestamp.valueOf(localDateTime);
  }

  protected static TimeUnit getTimeUnitForVector(TimeStampVector vector) {
    ArrowType.Timestamp arrowType =
        (ArrowType.Timestamp) vector.getField().getFieldType().getType();

    switch (arrowType.getUnit()) {
      case NANOSECOND:
        return TimeUnit.NANOSECONDS;
      case MICROSECOND:
        return TimeUnit.MICROSECONDS;
      case MILLISECOND:
        return TimeUnit.MILLISECONDS;
      case SECOND:
        return TimeUnit.SECONDS;
      default:
        throw new UnsupportedOperationException("Invalid Arrow time unit");
    }
  }

  protected static LongToLocalDateTime getLongToLocalDateTimeForVector(
      TimeStampVector vector, TimeZone timeZone) {
    String timeZoneID = timeZone.getID();

    ArrowType.Timestamp arrowType =
        (ArrowType.Timestamp) vector.getField().getFieldType().getType();

    switch (arrowType.getUnit()) {
      case NANOSECOND:
        return nanoseconds -> DateUtility.getLocalDateTimeFromEpochNano(nanoseconds, timeZoneID);
      case MICROSECOND:
        return microseconds -> DateUtility.getLocalDateTimeFromEpochMicro(microseconds, timeZoneID);
      case MILLISECOND:
        return milliseconds -> DateUtility.getLocalDateTimeFromEpochMilli(milliseconds, timeZoneID);
      case SECOND:
        return seconds ->
            DateUtility.getLocalDateTimeFromEpochMilli(
                TimeUnit.SECONDS.toMillis(seconds), timeZoneID);
      default:
        throw new UnsupportedOperationException("Invalid Arrow time unit");
    }
  }

  protected static TimeZone getTimeZoneForVector(TimeStampVector vector) {
    return TimeZone.getTimeZone(getZoneIdForVector(vector));
  }

  /**
   * Get the time zone of the vector, which is UTC when it does not have one.
   *
   * <p>The time zone of an Arrow timestamp is either an IANA name or an offset like "+05:30". Any
   * other value is rejected, as {@link TimeZone#getTimeZone(String)} would silently turn it into
   * GMT.
   */
  protected static ZoneId getZoneIdForVector(TimeStampVector vector) {
    ArrowType.Timestamp arrowType =
        (ArrowType.Timestamp) vector.getField().getFieldType().getType();

    String timezoneName = arrowType.getTimezone();
    if (timezoneName == null || timezoneName.isEmpty()) {
      return ZoneOffset.UTC;
    }

    try {
      return ZoneId.of(timezoneName, ZoneId.SHORT_IDS);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException(
          "Unsupported time zone in the timestamp type: '" + timezoneName + "'", e);
    }
  }

  protected static boolean getVectorIsZoned(TimeStampVector vector) {
    ArrowType.Timestamp arrowType =
        (ArrowType.Timestamp) vector.getField().getFieldType().getType();

    // An empty time zone means that there is no time zone info, as a missing one
    final String timezoneName = arrowType.getTimezone();
    return timezoneName != null && !timezoneName.isEmpty();
  }
}
