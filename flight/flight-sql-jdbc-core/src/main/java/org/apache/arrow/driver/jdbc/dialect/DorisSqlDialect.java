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

/**
 * The dialect of Apache Doris.
 *
 * <p>Doris follows MySQL: backticks delimit identifiers and backslashes escape characters in
 * strings. Its Flight SQL service only runs a statement when the results of a query are fetched. A
 * statement that arrives through the update endpoints is accepted and then ignored, so with this
 * dialect updates and DDL are sent as queries and their stream is read to the end.
 */
public class DorisSqlDialect implements SqlDialect {
  /** The name of this dialect, as given to the {@code dialect} connection property. */
  public static final String NAME = "doris";

  private static final SqlLexicalRules LEXICAL_RULES =
      SqlLexicalRules.builder().backtickIdentifiers(true).backslashEscapesInStrings(true).build();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public SqlLexicalRules lexicalRules() {
    return LEXICAL_RULES;
  }

  @Override
  public boolean updatesAsQueries() {
    return true;
  }
}
