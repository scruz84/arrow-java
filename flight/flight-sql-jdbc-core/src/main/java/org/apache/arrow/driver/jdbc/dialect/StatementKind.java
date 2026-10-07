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

import java.util.Set;

/** The kind of a SQL statement, as far as the driver needs to know without asking the server. */
public enum StatementKind {
  /** A statement that returns a result set. */
  QUERY,
  /** A statement that returns an update count: DML, DDL, and anything not recognized as a query. */
  UPDATE;

  private static final Set<String> QUERY_KEYWORDS =
      Set.of("SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "TABLE");

  /**
   * Classifies a statement by its first keyword.
   *
   * @param keyword the upper-cased first keyword of the statement.
   * @return {@link #QUERY} for the keywords that return rows, otherwise {@link #UPDATE}.
   */
  public static StatementKind ofKeyword(String keyword) {
    return QUERY_KEYWORDS.contains(keyword) ? QUERY : UPDATE;
  }
}
