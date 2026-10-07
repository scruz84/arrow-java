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

import java.util.List;
import java.util.Objects;

/**
 * An array value.
 *
 * @param elementType the element type, which is {@link SqlType#UNKNOWN} when it is not known.
 * @param elements the elements, which may be {@link SqlNull}.
 */
public record SqlArray(SqlType elementType, List<SqlValue> elements) implements SqlValue {
  public SqlArray {
    Objects.requireNonNull(elementType);
    elements = List.copyOf(Objects.requireNonNull(elements));
  }

  @Override
  public SqlType type() {
    return SqlType.arrayOf(elementType);
  }
}
