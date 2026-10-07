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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A row (struct) value.
 *
 * <p>JDBC {@link java.sql.Struct} exposes only attribute values, so field names are usually {@code
 * null} and dialects must cope with positional rows.
 *
 * @param typeName the name of the row type, or {@code null} when it is not known.
 * @param fields the fields, in order.
 */
public record SqlRow(@Nullable String typeName, List<SqlRowField> fields) implements SqlValue {
  public SqlRow {
    fields = List.copyOf(Objects.requireNonNull(fields));
  }

  @Override
  public SqlType type() {
    return SqlType.rowOf(
        typeName, fields.stream().map(f -> new SqlField(f.name(), f.value().type())).toList());
  }
}
