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

import static org.apache.arrow.driver.jdbc.accessor.impl.calendar.ArrowFlightJdbcTimeStampVectorAccessor.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.arrow.driver.jdbc.accessor.impl.text.ArrowFlightJdbcVarCharVectorAccessor;
import org.apache.arrow.driver.jdbc.utils.AccessorTestUtils;
import org.apache.arrow.driver.jdbc.utils.RootAllocatorTestExtension;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.util.Text;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ArrowFlightJdbcTimeStampVectorAccessorTest {

  public static final String AMERICA_VANCOUVER = "America/Vancouver";
  public static final String ASIA_BANGKOK = "Asia/Bangkok";
  public static final String AMERICA_SAO_PAULO = "America/Sao_Paulo";

  @RegisterExtension
  public static RootAllocatorTestExtension rootAllocatorTestExtension =
      new RootAllocatorTestExtension();

  /**
   * The test JVM runs on UTC. Use another default zone, with DST, so that the tests are able to
   * tell the JVM default zone apart from the Calendar zone and from UTC.
   */
  private static final String DEFAULT_TIME_ZONE = "Europe/Madrid";

  private TimeZone originalDefaultTimeZone;

  private TimeStampVector vector;

  private final AccessorTestUtils.AccessorSupplier<ArrowFlightJdbcTimeStampVectorAccessor>
      accessorSupplier =
          (vector, getCurrentRow) ->
              new ArrowFlightJdbcTimeStampVectorAccessor(
                  (TimeStampVector) vector, getCurrentRow, (boolean wasNull) -> {});

  private final AccessorTestUtils.AccessorIterator<ArrowFlightJdbcTimeStampVectorAccessor>
      accessorIterator = new AccessorTestUtils.AccessorIterator<>(accessorSupplier);

  public static Stream<Arguments> data() {
    return Stream.of(
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampNanoVector(),
            "TimeStampNanoVector",
            null),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampNanoTZVector("UTC"),
            "TimeStampNanoTZVector",
            "UTC"),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampNanoTZVector(AMERICA_VANCOUVER),
            "TimeStampNanoTZVector",
            AMERICA_VANCOUVER),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampNanoTZVector(ASIA_BANGKOK),
            "TimeStampNanoTZVector",
            ASIA_BANGKOK),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMicroVector(),
            "TimeStampMicroVector",
            null),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMicroTZVector("UTC"),
            "TimeStampMicroTZVector",
            "UTC"),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMicroTZVector(AMERICA_VANCOUVER),
            "TimeStampMicroTZVector",
            AMERICA_VANCOUVER),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMicroTZVector(ASIA_BANGKOK),
            "TimeStampMicroTZVector",
            ASIA_BANGKOK),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMilliVector(),
            "TimeStampMilliVector",
            null),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMilliTZVector("UTC"),
            "TimeStampMilliTZVector",
            "UTC"),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMilliTZVector(AMERICA_VANCOUVER),
            "TimeStampMilliTZVector",
            AMERICA_VANCOUVER),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampMilliTZVector(ASIA_BANGKOK),
            "TimeStampMilliTZVector",
            ASIA_BANGKOK),
        Arguments.of(
            (Supplier<TimeStampVector>) () -> rootAllocatorTestExtension.createTimeStampSecVector(),
            "TimeStampSecVector",
            null),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampSecTZVector("UTC"),
            "TimeStampSecTZVector",
            "UTC"),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampSecTZVector(AMERICA_VANCOUVER),
            "TimeStampSecTZVector",
            AMERICA_VANCOUVER),
        Arguments.of(
            (Supplier<TimeStampVector>)
                () -> rootAllocatorTestExtension.createTimeStampSecTZVector(ASIA_BANGKOK),
            "TimeStampSecTZVector",
            ASIA_BANGKOK));
  }

  public void setup(Supplier<TimeStampVector> vectorSupplier) {
    this.vector = vectorSupplier.get();
  }

  @BeforeEach
  public void setUpDefaultTimeZone() {
    originalDefaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(DEFAULT_TIME_ZONE));
  }

  @AfterEach
  public void tearDown() {
    TimeZone.setDefault(originalDefaultTimeZone);
    this.vector.close();
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimestampReturnValidTimestampWithoutCalendar(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    accessorIterator.assertAccessorGetter(
        vector,
        accessor -> accessor.getTimestamp(null),
        (accessor, currentRow) -> is(getTimestampForVector(currentRow)));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimestampReturnValidTimestampWithCalendar(
      Supplier<TimeStampVector> vectorSupplier) throws Exception {
    setup(vectorSupplier);
    TimeZone timeZone = TimeZone.getTimeZone(AMERICA_SAO_PAULO);
    Calendar calendar = Calendar.getInstance(timeZone);

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final Timestamp resultWithoutCalendar = accessor.getTimestamp(null);
          final Timestamp result = accessor.getTimestamp(calendar);

          long offset =
              getExpectedCalendarOffset(
                  timeZone, resultWithoutCalendar.getTime(), result.getTime());

          assertThat(resultWithoutCalendar.getTime() - result.getTime(), is(offset));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldInterpretVectorWithoutTzAtCalendarTimeZone(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    Assumptions.assumeTrue(timeZone == null);
    final TimeZone calendarTimeZone = TimeZone.getTimeZone(AMERICA_SAO_PAULO);
    final Calendar calendar = Calendar.getInstance(calendarTimeZone);

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final LocalDateTime wallClock = (LocalDateTime) vector.getObject(currentRow);
          final Instant expected = wallClock.atZone(calendarTimeZone.toZoneId()).toInstant();

          assertThat(accessor.getTimestamp(calendar).toInstant(), equalTo(expected));
          assertThat(accessor.getDate(calendar).getTime(), is(expected.toEpochMilli()));
          assertThat(accessor.getTime(calendar).getTime(), is(expected.toEpochMilli()));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimestampOfVectorWithTzBeTheSamePointInTimeAsInstant(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    Assumptions.assumeTrue(timeZone != null);
    final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AMERICA_SAO_PAULO));

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final Instant expected = accessor.getObject(Instant.class);

          assertThat(accessor.getTimestamp(null).toInstant(), equalTo(expected));
          assertThat(accessor.getTimestamp(calendar).toInstant(), equalTo(expected));
          assertThat(accessor.getDate(calendar).getTime(), is(expected.toEpochMilli()));
          assertThat(accessor.getTime(calendar).getTime(), is(expected.toEpochMilli()));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @Test
  public void testShouldGetPointInTimeBeUnambiguousInDstOverlap() throws Exception {
    // 2021-10-31 in Madrid, the wall-clock 02:30 happens twice: first at +02:00, then at +01:00
    final Instant summerTime = Instant.parse("2021-10-31T00:30:00Z");
    final Instant winterTime = Instant.parse("2021-10-31T01:30:00Z");
    final TimeStampMilliTZVector tzVector =
        new TimeStampMilliTZVector(
            "", rootAllocatorTestExtension.getRootAllocator(), "Europe/Madrid");
    // closed by tearDown
    setup(() -> tzVector);
    tzVector.allocateNew(2);
    tzVector.set(0, summerTime.toEpochMilli());
    tzVector.set(1, winterTime.toEpochMilli());
    tzVector.setValueCount(2);

    final Instant[] expected = {summerTime, winterTime};
    final int[] expectedOffsetSeconds = {2 * 3600, 3600};
    for (int row = 0; row < 2; row++) {
      final int currentRow = row;
      final ArrowFlightJdbcTimeStampVectorAccessor rowAccessor =
          new ArrowFlightJdbcTimeStampVectorAccessor(tzVector, () -> currentRow, (b) -> {});

      assertThat(rowAccessor.getObject(Instant.class), equalTo(expected[row]));
      assertThat(rowAccessor.getTimestamp(null).toInstant(), equalTo(expected[row]));
      assertThat(
          rowAccessor.getObject(OffsetDateTime.class).getOffset().getTotalSeconds(),
          is(expectedOffsetSeconds[row]));
      assertThat(rowAccessor.getObject(ZonedDateTime.class).toInstant(), equalTo(expected[row]));
      assertThat(
          rowAccessor.getObject(LocalDateTime.class),
          equalTo(LocalDateTime.parse("2021-10-31T02:30")));
    }
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetObjectReturnValidLocalDateTime(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    final String expectedTimeZone = Objects.requireNonNullElse(timeZone, "UTC");

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final LocalDateTime value = accessor.getObject(LocalDateTime.class);
          final LocalDateTime expectedValue =
              getZonedDateTime(currentRow, expectedTimeZone).toLocalDateTime();

          assertThat(value, equalTo(expectedValue));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetObjectReturnValidInstant(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    final String expectedTimeZone = Objects.requireNonNullElse(timeZone, "UTC");
    final boolean vectorHasTz = timeZone != null;
    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          if (vectorHasTz) {
            final Instant value = accessor.getObject(Instant.class);
            final Instant expectedValue =
                getZonedDateTime(currentRow, expectedTimeZone).toInstant();

            assertThat(value, equalTo(expectedValue));
            assertThat(accessor.wasNull(), is(false));
          } else {
            assertThrows(SQLException.class, () -> accessor.getObject(Instant.class));
          }
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetObjectReturnValidOffsetDateTime(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    final String expectedTimeZone = Objects.requireNonNullElse(timeZone, "UTC");
    final boolean vectorHasTz = timeZone != null;
    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          if (vectorHasTz) {
            final OffsetDateTime value = accessor.getObject(OffsetDateTime.class);
            final OffsetDateTime expectedValue =
                getZonedDateTime(currentRow, expectedTimeZone).toOffsetDateTime();

            assertThat(value, equalTo(expectedValue));
            assertThat(value.getOffset(), equalTo(expectedValue.getOffset()));
            assertThat(accessor.wasNull(), is(false));
          } else {
            assertThrows(SQLException.class, () -> accessor.getObject(OffsetDateTime.class));
          }
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetObjectReturnValidZonedDateTime(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    final String expectedTimeZone = Objects.requireNonNullElse(timeZone, "UTC");
    final boolean vectorHasTz = timeZone != null;
    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          if (vectorHasTz) {
            final ZonedDateTime value = accessor.getObject(ZonedDateTime.class);
            final ZonedDateTime expectedValue = getZonedDateTime(currentRow, expectedTimeZone);

            assertThat(value, equalTo(expectedValue));
            assertThat(value.getZone(), equalTo(ZoneId.of(expectedTimeZone)));
            assertThat(accessor.wasNull(), is(false));
          } else {
            assertThrows(SQLException.class, () -> accessor.getObject(ZonedDateTime.class));
          }
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimestampReturnNull(Supplier<TimeStampVector> vectorSupplier) {
    setup(vectorSupplier);
    vector.setNull(0);
    ArrowFlightJdbcTimeStampVectorAccessor accessor = accessorSupplier.supply(vector, () -> 0);
    assertThat(accessor.getTimestamp(null), CoreMatchers.equalTo(null));
    assertThat(accessor.wasNull(), is(true));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetDateReturnValidDateWithoutCalendar(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    accessorIterator.assertAccessorGetter(
        vector,
        accessor -> accessor.getDate(null),
        (accessor, currentRow) -> is(new Date(getTimestampForVector(currentRow).getTime())));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetDateReturnValidDateWithCalendar(Supplier<TimeStampVector> vectorSupplier)
      throws Exception {
    setup(vectorSupplier);
    TimeZone timeZone = TimeZone.getTimeZone(AMERICA_SAO_PAULO);
    Calendar calendar = Calendar.getInstance(timeZone);

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final Date resultWithoutCalendar = accessor.getDate(null);
          final Date result = accessor.getDate(calendar);

          long offset =
              getExpectedCalendarOffset(
                  timeZone, resultWithoutCalendar.getTime(), result.getTime());

          assertThat(resultWithoutCalendar.getTime() - result.getTime(), is(offset));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetDateReturnNull(Supplier<TimeStampVector> vectorSupplier) {
    setup(vectorSupplier);
    vector.setNull(0);
    ArrowFlightJdbcTimeStampVectorAccessor accessor = accessorSupplier.supply(vector, () -> 0);
    assertThat(accessor.getDate(null), CoreMatchers.equalTo(null));
    assertThat(accessor.wasNull(), is(true));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimeReturnValidTimeWithoutCalendar(
      Supplier<TimeStampVector> vectorSupplier, String vectorType, String timeZone)
      throws Exception {
    setup(vectorSupplier);
    accessorIterator.assertAccessorGetter(
        vector,
        accessor -> accessor.getTime(null),
        (accessor, currentRow) -> is(new Time(getTimestampForVector(currentRow).getTime())));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimeReturnValidTimeWithCalendar(Supplier<TimeStampVector> vectorSupplier)
      throws Exception {
    setup(vectorSupplier);
    TimeZone timeZone = TimeZone.getTimeZone(AMERICA_SAO_PAULO);
    Calendar calendar = Calendar.getInstance(timeZone);

    accessorIterator.iterate(
        vector,
        (accessor, currentRow) -> {
          final Time resultWithoutCalendar = accessor.getTime(null);
          final Time result = accessor.getTime(calendar);

          long offset =
              getExpectedCalendarOffset(
                  timeZone, resultWithoutCalendar.getTime(), result.getTime());

          assertThat(resultWithoutCalendar.getTime() - result.getTime(), is(offset));
          assertThat(accessor.wasNull(), is(false));
        });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetTimeReturnNull(Supplier<TimeStampVector> vectorSupplier) {
    setup(vectorSupplier);
    vector.setNull(0);
    ArrowFlightJdbcTimeStampVectorAccessor accessor = accessorSupplier.supply(vector, () -> 0);
    assertThat(accessor.getTime(null), CoreMatchers.equalTo(null));
    assertThat(accessor.wasNull(), is(true));
  }

  /**
   * Expected difference between the result without Calendar and the result with Calendar.
   *
   * <ul>
   *   <li>Vectors with TZ info hold a point in time, so the Calendar has no effect.
   *   <li>Vectors without TZ info hold a wall-clock value, which is interpreted in the default TZ
   *       when there is no Calendar and in the Calendar TZ otherwise.
   * </ul>
   */
  private long getExpectedCalendarOffset(
      TimeZone calendarTimeZone, long resultWithoutCalendar, long result) {
    if (getVectorIsZoned(vector)) {
      return 0L;
    }
    return (long) calendarTimeZone.getOffset(result)
        - TimeZone.getDefault().getOffset(resultWithoutCalendar);
  }

  private Timestamp getTimestampForVector(int currentRow) {
    Object object = vector.getObject(currentRow);

    Timestamp expectedTimestamp = null;
    if (object instanceof LocalDateTime) {
      expectedTimestamp = Timestamp.valueOf((LocalDateTime) object);
    } else if (object instanceof Long) {
      TimeUnit timeUnit = getTimeUnitForVector(vector);
      long millis = timeUnit.toMillis((Long) object);
      expectedTimestamp = Timestamp.from(Instant.ofEpochMilli(millis));
    }
    return expectedTimestamp;
  }

  /** ZonedDateTime contains all necessary information to generate any java.time object. */
  private ZonedDateTime getZonedDateTime(int currentRow, String timeZone) {
    Object object = vector.getObject(currentRow);
    TimeZone tz = TimeZone.getTimeZone(timeZone);
    ZonedDateTime expectedTimestamp = null;
    if (object instanceof LocalDateTime) {
      expectedTimestamp = ((LocalDateTime) object).atZone(tz.toZoneId());
    } else if (object instanceof Long) {
      TimeUnit timeUnit = getTimeUnitForVector(vector);
      Instant instant = Instant.ofEpochMilli(timeUnit.toMillis((Long) object));
      expectedTimestamp = ZonedDateTime.ofInstant(instant, tz.toZoneId());
    }
    return expectedTimestamp;
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetObjectClass(Supplier<TimeStampVector> vectorSupplier) throws Exception {
    setup(vectorSupplier);
    accessorIterator.assertAccessorGetter(
        vector, ArrowFlightJdbcTimeStampVectorAccessor::getObjectClass, equalTo(Timestamp.class));
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetStringBeConsistentWithVarCharAccessorWithoutCalendar(
      Supplier<TimeStampVector> vectorSupplier) throws Exception {
    setup(vectorSupplier);
    assertGetStringIsConsistentWithVarCharAccessor(null);
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testShouldGetStringBeConsistentWithVarCharAccessorWithCalendar(
      Supplier<TimeStampVector> vectorSupplier) throws Exception {
    setup(vectorSupplier);
    // Ignore for TimeStamp vectors with TZ, as VarChar accessor won't consider their TZ
    Assumptions.assumeTrue(
        vector instanceof TimeStampNanoVector
            || vector instanceof TimeStampMicroVector
            || vector instanceof TimeStampMilliVector
            || vector instanceof TimeStampSecVector);
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(AMERICA_VANCOUVER));
    assertGetStringIsConsistentWithVarCharAccessor(calendar);
  }

  private void assertGetStringIsConsistentWithVarCharAccessor(Calendar calendar) throws Exception {
    try (VarCharVector varCharVector =
        new VarCharVector("", rootAllocatorTestExtension.getRootAllocator())) {
      varCharVector.allocateNew(1);
      ArrowFlightJdbcVarCharVectorAccessor varCharVectorAccessor =
          new ArrowFlightJdbcVarCharVectorAccessor(varCharVector, () -> 0, (boolean wasNull) -> {});

      accessorIterator.iterate(
          vector,
          (accessor, currentRow) -> {
            final String string = accessor.getString();
            varCharVector.set(0, new Text(string));
            varCharVector.setValueCount(1);

            Timestamp timestampFromVarChar = varCharVectorAccessor.getTimestamp(calendar);
            Timestamp timestamp = accessor.getTimestamp(calendar);

            assertThat(timestamp, is(timestampFromVarChar));
            assertThat(accessor.wasNull(), is(false));
          });
    }
  }
}
