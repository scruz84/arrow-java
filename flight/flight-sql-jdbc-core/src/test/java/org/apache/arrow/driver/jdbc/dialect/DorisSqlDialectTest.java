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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Types;
import org.apache.arrow.driver.jdbc.dialect.value.SqlScalar;
import org.apache.arrow.driver.jdbc.dialect.value.SqlType;
import org.junit.jupiter.api.Test;

public class DorisSqlDialectTest {
  private final SqlDialect dialect = new DorisSqlDialect();

  @Test
  public void testUpdatesAreSentAsQueries() {
    assertTrue(dialect.updatesAsQueries());
    assertFalse(new AnsiSqlDialect().updatesAsQueries());
  }

  @Test
  public void testBackslashesInStringsAreEscaped() throws SQLException {
    assertEquals(
        "'it''s \\\\ ok'", dialect.render(new SqlScalar("it's \\ ok", SqlType.of(Types.VARCHAR))));
  }

  @Test
  public void testBacktickIdentifiersHideTheirQuestionMarks() {
    final ScannedSql scanned =
        ScannedSql.scan("select `a?` from t where b = ?", dialect.lexicalRules());
    assertEquals(1, scanned.parameterCount());
  }

  @Test
  public void testStatementsAreClassifiedByTheirKeyword() {
    assertEquals(StatementKind.UPDATE, dialect.classify("CREATE TABLE t (a INT)"));
    assertEquals(StatementKind.QUERY, dialect.classify("select 1"));
  }
}
