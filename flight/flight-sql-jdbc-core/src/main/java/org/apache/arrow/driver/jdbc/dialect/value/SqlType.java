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
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Describes the SQL type of a {@link SqlValue}.
 *
 * @param jdbcType a {@link java.sql.Types} constant, {@link Types#OTHER} when unknown.
 * @param typeName the database-specific type name, or {@code null} when unknown.
 * @param elementType the element type for arrays, otherwise {@code null}.
 * @param fields the fields for row types, otherwise empty.
 */
public record SqlType(
    int jdbcType, @Nullable String typeName, @Nullable SqlType elementType, List<SqlField> fields) {

  /** A type about which nothing is known. */
  public static final SqlType UNKNOWN = new SqlType(Types.OTHER, null, null, List.of());

  public SqlType {
    fields = List.copyOf(Objects.requireNonNull(fields));
  }

  /** A type with only a JDBC type code. */
  public static SqlType of(int jdbcType) {
    return new SqlType(jdbcType, null, null, List.of());
  }

  /** A type with a JDBC type code and a database-specific name. */
  public static SqlType of(int jdbcType, @Nullable String typeName) {
    return new SqlType(jdbcType, typeName, null, List.of());
  }

  /** An array type with the given element type. */
  public static SqlType arrayOf(SqlType elementType) {
    return new SqlType(Types.ARRAY, null, Objects.requireNonNull(elementType), List.of());
  }

  /** A row (struct) type. */
  public static SqlType rowOf(@Nullable String typeName, List<SqlField> fields) {
    return new SqlType(Types.STRUCT, typeName, null, fields);
  }

  /** Whether nothing is known about this type. */
  public boolean isUnknown() {
    return jdbcType == Types.OTHER && typeName == null && elementType == null && fields.isEmpty();
  }
}
