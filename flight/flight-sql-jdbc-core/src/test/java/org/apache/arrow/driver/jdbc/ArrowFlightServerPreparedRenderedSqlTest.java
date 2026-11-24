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
package org.apache.arrow.driver.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.arrow.driver.jdbc.utils.MockFlightSqlProducer;
import org.apache.arrow.flight.sql.FlightSqlUtils;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests {@code serverPrepareRenderedSql}: client-side statements are sent through the prepared
 * statement endpoints. The mock server rejects the plain statement endpoints, like a server that
 * does not implement them.
 */
public class ArrowFlightServerPreparedRenderedSqlTest {
  private static final String CREATE_PREPARED =
      FlightSqlUtils.FLIGHT_SQL_CREATE_PREPARED_STATEMENT.getType();
  private static final String CLOSE_PREPARED =
      FlightSqlUtils.FLIGHT_SQL_CLOSE_PREPARED_STATEMENT.getType();
  private static final Schema ID_SCHEMA =
      new Schema(Collections.singletonList(Field.nullable("id", new ArrowType.Int(32, true))));

  public static final MockFlightSqlProducer PRODUCER = new MockFlightSqlProducer();

  @RegisterExtension
  public static final FlightServerTestExtension FLIGHT_SERVER_TEST_EXTENSION =
      FlightServerTestExtension.createStandardTestExtension(PRODUCER);

  private static final Map<String, Object> PREPARED_ENDPOINTS =
      Map.of("disableServerPreparedStatements", true, "serverPrepareRenderedSql", true);
  private static final Map<String, Object> PLAIN_ENDPOINTS =
      Map.of("disableServerPreparedStatements", true);
  private static final Map<String, Object> IGNORED =
      Map.of("disableServerPreparedQueries", true, "serverPrepareRenderedSql", true);

  @BeforeEach
  public void before() {
    PRODUCER.clearActionTypeCounter();
    PRODUCER.clearReceivedStatements();
  }

  @AfterEach
  public void after() {
    PRODUCER.setRejectPlainStatements(false);
  }

  private static void addQuery(final String sql, final int id) {
    PRODUCER.addSelectQuery(
        sql,
        ID_SCHEMA,
        Collections.singletonList(
            listener -> {
              try (final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                  final VectorSchemaRoot root = VectorSchemaRoot.create(ID_SCHEMA, allocator)) {
                root.allocateNew();
                ((IntVector) root.getVector(0)).setSafe(0, id);
                root.setRowCount(1);
                listener.start(root);
                listener.putNext();
              } catch (final Throwable t) {
                listener.error(t);
              } finally {
                listener.completed();
              }
            }));
  }

  private static int count(final String action) {
    return PRODUCER.getActionTypeCounter().getOrDefault(action, 0);
  }

  private static void assertSingleRow(final ResultSet rs, final int id) throws SQLException {
    assertTrue(rs.next());
    assertEquals(id, rs.getInt(1));
    assertFalse(rs.next());
  }

  @Test
  public void testQueryUsesPreparedEndpointsAndClosesTheHandle() throws Exception {
    PRODUCER.setRejectPlainStatements(true);
    addQuery("SELECT id FROM p1 WHERE a = 5", 1);
    try (final Connection connection =
        FLIGHT_SERVER_TEST_EXTENSION.getConnection(PREPARED_ENDPOINTS)) {
      try (final PreparedStatement ps =
          connection.prepareStatement("SELECT id FROM p1 WHERE a = ?")) {
        ps.setInt(1, 5);
        try (final ResultSet rs = ps.executeQuery()) {
          assertSingleRow(rs, 1);
        }
      }
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
    assertTrue(PRODUCER.getPreparedStatementQueries().contains("SELECT id FROM p1 WHERE a = 5"));
    assertEquals(count(CREATE_PREPARED), count(CLOSE_PREPARED));
  }

  @Test
  public void testRerunPreparesEachRenderedStatement() throws Exception {
    PRODUCER.setRejectPlainStatements(true);
    addQuery("SELECT id FROM p2 WHERE a = 1", 11);
    addQuery("SELECT id FROM p2 WHERE a = 2", 22);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(PREPARED_ENDPOINTS);
        final PreparedStatement ps = connection.prepareStatement("SELECT id FROM p2 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 11);
      }
      ps.setInt(1, 2);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 22);
      }
    }
    assertEquals(
        List.of("SELECT id FROM p2 WHERE a = 1", "SELECT id FROM p2 WHERE a = 2"),
        PRODUCER.getPreparedStatementQueries());
    assertEquals(2, count(CLOSE_PREPARED));
  }

  @Test
  public void testUpdateAndBatchUsePreparedEndpoints() throws Exception {
    PRODUCER.setRejectPlainStatements(true);
    PRODUCER.addUpdateQuery("UPDATE p3 SET a = 1 WHERE b = 'x'", 7);
    PRODUCER.addUpdateQuery("INSERT INTO p3 VALUES (1)", 1);
    PRODUCER.addUpdateQuery("INSERT INTO p3 VALUES (2)", 5);
    try (final Connection connection =
        FLIGHT_SERVER_TEST_EXTENSION.getConnection(PREPARED_ENDPOINTS)) {
      try (final PreparedStatement ps =
          connection.prepareStatement("UPDATE p3 SET a = ? WHERE b = ?")) {
        ps.setInt(1, 1);
        ps.setString(2, "x");
        assertEquals(7, ps.executeUpdate());
      }
      try (final PreparedStatement ps = connection.prepareStatement("INSERT INTO p3 VALUES (?)")) {
        ps.setInt(1, 1);
        ps.addBatch();
        ps.setInt(1, 2);
        ps.addBatch();
        assertArrayEquals(new int[] {1, 5}, ps.executeBatch());
      }
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
    assertEquals(3, count(CREATE_PREPARED));
    assertEquals(3, count(CLOSE_PREPARED));
  }

  @Test
  public void testPlainStatementUsesPreparedEndpoints() throws Exception {
    PRODUCER.setRejectPlainStatements(true);
    addQuery("SELECT id FROM p4", 4);
    PRODUCER.addUpdateQuery("DELETE FROM p4", 2);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(PREPARED_ENDPOINTS);
        final Statement statement = connection.createStatement()) {
      try (final ResultSet rs = statement.executeQuery("SELECT id FROM p4")) {
        assertSingleRow(rs, 4);
      }
      assertEquals(2, statement.executeUpdate("DELETE FROM p4"));
    }
    assertTrue(PRODUCER.getReceivedStatements().isEmpty());
    assertEquals(2, count(CREATE_PREPARED));
    assertEquals(2, count(CLOSE_PREPARED));
  }

  @Test
  public void testFailureOnTheServerIsReported() throws Exception {
    PRODUCER.setRejectPlainStatements(true);
    PRODUCER.addUpdateQuery("UPDATE p5 SET a = 1", 1);
    try (final Connection connection =
            FLIGHT_SERVER_TEST_EXTENSION.getConnection(PREPARED_ENDPOINTS);
        final PreparedStatement ps = connection.prepareStatement("UPDATE p5 SET a = ?")) {
      ps.setInt(1, 2); // "UPDATE p5 SET a = 2" is not registered: prepare fails on the server
      assertThrows(SQLException.class, ps::executeUpdate);
    }
  }

  @Test
  public void testOffByDefaultUsesPlainEndpoints() throws Exception {
    addQuery("SELECT id FROM p6 WHERE a = 1", 6);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(PLAIN_ENDPOINTS);
        final PreparedStatement ps = connection.prepareStatement("SELECT id FROM p6 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 6);
      }
    }
    assertEquals(List.of("SELECT id FROM p6 WHERE a = 1"), PRODUCER.getReceivedStatements());
    assertEquals(0, count(CREATE_PREPARED));
  }

  @Test
  public void testIgnoredWithoutDisableServerPreparedStatements() throws Exception {
    addQuery("SELECT id FROM p7 WHERE a = 1", 7);
    try (final Connection connection = FLIGHT_SERVER_TEST_EXTENSION.getConnection(IGNORED);
        final PreparedStatement ps = connection.prepareStatement("SELECT id FROM p7 WHERE a = ?")) {
      ps.setInt(1, 1);
      try (final ResultSet rs = ps.executeQuery()) {
        assertSingleRow(rs, 7);
      }
    }
    // Queries are client-side but still use the plain endpoint.
    assertEquals(List.of("SELECT id FROM p7 WHERE a = 1"), PRODUCER.getReceivedStatements());
  }
}
