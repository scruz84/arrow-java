/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.driver.jdbc.dremio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Type metadata the driver reports for TIMESTAMP / DATE / TIME, both per result set and catalog. */
class TimestampMetadataTest {

  private static Connection conn;
  private static String table;
  private static String tableName; // unquoted last path element

  @BeforeAll
  static void setUp() throws Exception {
    conn = Dremio.connectOrSkip();
    table =
        Dremio.createScratchTable(
            conn,
            "ts_meta",
            "SELECT 1 AS id, TIMESTAMP '2024-03-10 12:34:56.789' AS ts,"
                + " DATE '2024-03-10' AS d, TIME '12:34:56.789' AS t");
    tableName = table.substring(table.lastIndexOf('.') + 2, table.length() - 1);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (conn != null) {
      Dremio.dropQuietly(conn, table);
      conn.close();
    }
  }

  private static ResultSetMetaData literalMetaData(Statement s) throws Exception {
    ResultSet rs =
        s.executeQuery(
            "SELECT TIMESTAMP '2024-03-10 12:34:56.789' AS ts, DATE '2024-03-10' AS d,"
                + " TIME '12:34:56.789' AS t, CAST(NULL AS TIMESTAMP) AS n");
    return rs.getMetaData();
  }

  @Test
  @DisplayName("ResultSetMetaData: JDBC type codes and type names")
  void resultSetTypes() throws Exception {
    try (Statement s = conn.createStatement()) {
      ResultSetMetaData md = literalMetaData(s);
      assertEquals(Types.TIMESTAMP, md.getColumnType(1));
      assertEquals("TIMESTAMP", md.getColumnTypeName(1));
      assertEquals(Types.DATE, md.getColumnType(2));
      assertEquals("DATE", md.getColumnTypeName(2));
      assertEquals(Types.TIME, md.getColumnType(3));
      assertEquals("TIME", md.getColumnTypeName(3));
      assertEquals(Types.TIMESTAMP, md.getColumnType(4));
    }
  }

  @Test
  @DisplayName("ResultSetMetaData.getColumnClassName names the java.sql class")
  void resultSetClassNames() throws Exception {
    try (Statement s = conn.createStatement()) {
      ResultSetMetaData md = literalMetaData(s);
      assertEquals("java.sql.Timestamp", md.getColumnClassName(1));
      assertEquals("java.sql.Date", md.getColumnClassName(2));
      assertEquals("java.sql.Time", md.getColumnClassName(3));
    }
  }

  @Test
  @DisplayName("ResultSetMetaData exposes the millisecond precision (scale 3 or precision >= 23)")
  void resultSetPrecision() throws Exception {
    try (Statement s = conn.createStatement()) {
      ResultSetMetaData md = literalMetaData(s);
      assertTrue(
          md.getScale(1) == 3 || md.getPrecision(1) >= 23,
          "precision=" + md.getPrecision(1) + " scale=" + md.getScale(1));
      assertTrue(
          md.getColumnDisplaySize(1) >= 23,
          "display size must fit yyyy-MM-dd HH:mm:ss.SSS but is " + md.getColumnDisplaySize(1));
    }
  }

  @Test
  @DisplayName("ResultSetMetaData: nullability of a NULL timestamp is not 'columnNoNulls'")
  void resultSetNullability() throws Exception {
    try (Statement s = conn.createStatement()) {
      ResultSetMetaData md = literalMetaData(s);
      assertFalse(md.isNullable(4) == ResultSetMetaData.columnNoNulls);
    }
  }

  private ResultSet columns() throws Exception {
    DatabaseMetaData md = conn.getMetaData();
    return md.getColumns(null, "$scratch", tableName, "%");
  }

  @Test
  @DisplayName("DatabaseMetaData.getColumns: DATA_TYPE / TYPE_NAME of TIMESTAMP, DATE, TIME")
  void catalogTypes() throws Exception {
    try (ResultSet rs = columns()) {
      int seen = 0;
      while (rs.next()) {
        String name = rs.getString("COLUMN_NAME");
        switch (name) {
          case "ts":
            assertEquals(Types.TIMESTAMP, rs.getInt("DATA_TYPE"), "ts DATA_TYPE");
            assertEquals("TIMESTAMP", rs.getString("TYPE_NAME"), "ts TYPE_NAME");
            seen++;
            break;
          case "d":
            assertEquals(Types.DATE, rs.getInt("DATA_TYPE"), "d DATA_TYPE");
            assertEquals("DATE", rs.getString("TYPE_NAME"), "d TYPE_NAME");
            seen++;
            break;
          case "t":
            assertEquals(Types.TIME, rs.getInt("DATA_TYPE"), "t DATA_TYPE");
            assertEquals("TIME", rs.getString("TYPE_NAME"), "t TYPE_NAME");
            seen++;
            break;
          default:
            break;
        }
      }
      assertEquals(3, seen, "columns found in catalog");
    }
  }

  @Test
  @DisplayName("DatabaseMetaData.getColumns: COLUMN_SIZE=23 and DECIMAL_DIGITS=3 for TIMESTAMP")
  void catalogSizeAndDigits() throws Exception {
    try (ResultSet rs = columns()) {
      while (rs.next()) {
        if ("ts".equals(rs.getString("COLUMN_NAME"))) {
          assertEquals(3, rs.getInt("DECIMAL_DIGITS"), "DECIMAL_DIGITS");
          assertEquals(23, rs.getInt("COLUMN_SIZE"), "COLUMN_SIZE (chars of the string form)");
          return;
        }
      }
    }
    throw new AssertionError("column ts not found");
  }

  @Test
  @DisplayName("Catalog DATA_TYPE agrees with ResultSetMetaData.getColumnType for the same column")
  void catalogAgreesWithResultSet() throws Exception {
    int fromCatalog = -1;
    try (ResultSet rs = columns()) {
      while (rs.next()) {
        if ("ts".equals(rs.getString("COLUMN_NAME"))) {
          fromCatalog = rs.getInt("DATA_TYPE");
        }
      }
    }
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT ts FROM " + table)) {
      assertEquals(rs.getMetaData().getColumnType(1), fromCatalog);
    }
  }

  @Test
  @DisplayName("DatabaseMetaData.getTypeInfo lists TIMESTAMP as java.sql.Types.TIMESTAMP")
  void typeInfo() throws Exception {
    try (ResultSet rs = conn.getMetaData().getTypeInfo()) {
      boolean found = false;
      while (rs.next()) {
        if ("TIMESTAMP".equalsIgnoreCase(rs.getString("TYPE_NAME"))) {
          assertEquals(Types.TIMESTAMP, rs.getInt("DATA_TYPE"));
          found = true;
        }
      }
      assertTrue(found, "TIMESTAMP missing from getTypeInfo()");
    }
  }
}
