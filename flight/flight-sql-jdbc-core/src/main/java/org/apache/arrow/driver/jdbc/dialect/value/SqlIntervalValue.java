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
package org.apache.arrow.driver.jdbc.dialect.value;

import java.sql.Types;

/**
 * An interval value, in the three components Arrow's {@code MONTH_DAY_NANO} interval uses.
 *
 * <p>Each component carries its own sign. A year-month interval has only months, and a day-time
 * interval has only days and nanoseconds.
 *
 * @param months the number of months.
 * @param days the number of days.
 * @param nanos the number of nanoseconds.
 */
public record SqlIntervalValue(int months, int days, long nanos) implements SqlValue {
  private static final SqlType TYPE = SqlType.of(Types.OTHER, "INTERVAL");

  @Override
  public SqlType type() {
    return TYPE;
  }
}
