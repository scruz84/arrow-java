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

import static org.apache.calcite.avatica.util.DateTimeUtils.MILLIS_PER_DAY;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/** Datetime utility functions. */
public class DateTimeUtils {
  private DateTimeUtils() {
    // Prevent instantiation.
  }

  /**
   * Subtracts given Calendar's TimeZone offset from epoch milliseconds.
   *
   * <p>The milliseconds are a point in time whose wall-clock, in the default TimeZone, is moved to
   * the Calendar's TimeZone. This is the case of values parsed with the default TimeZone, such as
   * {@link java.sql.Timestamp#valueOf(String)}. For wall-clock values expressed as if they were
   * UTC, see {@link #utcWallClockToEpochMilli(long, Calendar)}.
   */
  public static long applyCalendarOffset(long milliseconds, Calendar calendar) {
    if (calendar == null) {
      calendar = Calendar.getInstance();
    }

    final TimeZone tz = calendar.getTimeZone();
    final TimeZone defaultTz = TimeZone.getDefault();

    if (tz != defaultTz) {
      milliseconds -= tz.getOffset(milliseconds) - defaultTz.getOffset(milliseconds);
    }

    return milliseconds;
  }

  /**
   * Gets the epoch milliseconds of a wall-clock value in the TimeZone of the given Calendar.
   *
   * <p>The wall-clock value is a date and time with no TimeZone, expressed as the milliseconds
   * since the epoch it would have if it were UTC. This is how Arrow stores times and dates and the
   * values of timestamps without TimeZone. When there is no Calendar, the default TimeZone is used.
   *
   * @param utcWallClockMillis the wall-clock value, as if it were UTC
   * @param calendar the Calendar that provides the TimeZone, or null for the default TimeZone
   * @return the epoch milliseconds of that wall-clock value in the Calendar's TimeZone
   */
  public static long utcWallClockToEpochMilli(long utcWallClockMillis, Calendar calendar) {
    final TimeZone timeZone = calendar == null ? TimeZone.getDefault() : calendar.getTimeZone();
    final LocalDateTime wallClock =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(utcWallClockMillis), ZoneOffset.UTC);
    return wallClock.atZone(timeZone.toZoneId()).toInstant().toEpochMilli();
  }

  /**
   * Converts Epoch millis to a {@link Timestamp} object.
   *
   * @param millisWithCalendar the Timestamp in Epoch millis
   * @return a {@link Timestamp} object representing the given Epoch millis
   */
  public static Timestamp getTimestampValue(long millisWithCalendar) {
    // Millis are negative before 1970, where only floor semantics keep the epoch day
    // and the time-of-day remainder on the same day (and the remainder non-negative).
    return Timestamp.valueOf(
        LocalDateTime.of(
            LocalDate.ofEpochDay(Math.floorDiv(millisWithCalendar, MILLIS_PER_DAY)),
            LocalTime.ofNanoOfDay(
                TimeUnit.MILLISECONDS.toNanos(Math.floorMod(millisWithCalendar, MILLIS_PER_DAY)))));
  }
}
