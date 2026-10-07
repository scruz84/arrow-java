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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Text forms of date and time values, shared by {@link SqlDialect} implementations. */
public final class SqlLiterals {
  /** The number of nanoseconds in a day. */
  public static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

  private SqlLiterals() {}

  /** The ISO form {@code yyyy-MM-dd}. ANSI literals only allow four-digit years. */
  public static String dateText(LocalDate d) throws SQLException {
    if (d.getYear() < 1 || d.getYear() > 9999) {
      throw new SQLFeatureNotSupportedException("Cannot render the date " + d);
    }
    return d.toString();
  }

  /** The form {@code HH:mm:ss[.fffffffff]}, always with seconds and without trailing zeros. */
  public static String timeText(LocalTime t) {
    final String base =
        String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    if (t.getNano() == 0) {
      return base;
    }
    return base + "." + String.format(Locale.ROOT, "%09d", t.getNano()).replaceAll("0+$", "");
  }

  /**
   * The same instant with an offset that {@link #offsetText} can write: an offset with seconds,
   * such as the {@code -00:14:44} of Europe/Madrid before 1901, is replaced by UTC.
   */
  public static OffsetDateTime withWholeMinuteOffset(OffsetDateTime t) {
    return t.getOffset().getTotalSeconds() % 60 == 0 ? t : t.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Like {@link #withWholeMinuteOffset(OffsetDateTime)}, for a time of day. */
  public static OffsetTime withWholeMinuteOffset(OffsetTime t) {
    return t.getOffset().getTotalSeconds() % 60 == 0 ? t : t.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** The form {@code +HH:mm}. ANSI literals have no seconds in an offset. */
  public static String offsetText(ZoneOffset offset) throws SQLException {
    final int total = offset.getTotalSeconds();
    if (total % 60 != 0) {
      throw new SQLFeatureNotSupportedException("Cannot render the time zone offset " + offset);
    }
    final int abs = Math.abs(total);
    return String.format(
        Locale.ROOT, "%s%02d:%02d", total < 0 ? "-" : "+", abs / 3600, abs % 3600 / 60);
  }
}
