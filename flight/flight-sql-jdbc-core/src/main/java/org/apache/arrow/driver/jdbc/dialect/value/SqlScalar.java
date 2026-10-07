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

import java.util.Objects;

/**
 * A non-null scalar value.
 *
 * <p>The Java value is one of: {@link Boolean}, {@link Byte}, {@link Short}, {@link Integer},
 * {@link Long}, {@link Float}, {@link Double}, {@link java.math.BigInteger}, {@link
 * java.math.BigDecimal}, {@link String}, {@code byte[]}, {@link java.time.LocalDate}, {@link
 * java.time.LocalTime}, {@link java.time.LocalDateTime}, {@link java.time.OffsetTime}, {@link
 * java.time.OffsetDateTime} or {@link java.util.UUID}.
 *
 * @param javaValue the normalized value.
 * @param type its SQL type.
 */
public record SqlScalar(Object javaValue, SqlType type) implements SqlValue {
  public SqlScalar {
    Objects.requireNonNull(javaValue);
    Objects.requireNonNull(type);
  }
}
