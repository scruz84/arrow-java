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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.driver.jdbc.client.ArrowFlightSqlClientHandler;
import org.apache.arrow.driver.jdbc.client.CloseableEndpointStreamPair;
import org.apache.arrow.driver.jdbc.dialect.AnsiSqlDialect;
import org.apache.arrow.driver.jdbc.dialect.DorisSqlDialect;
import org.apache.arrow.driver.jdbc.dialect.ScannedSql;
import org.apache.arrow.driver.jdbc.dialect.SqlDialect;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.avatica.ColumnMetaData.Rep;
import org.apache.calcite.avatica.Meta.StatementType;
import org.apache.calcite.avatica.remote.TypedValue;
import org.junit.jupiter.api.Test;

public class ClientSidePreparedStatementTest {
  private static final Schema ID_SCHEMA =
      new Schema(Collections.singletonList(Field.nullable("id", new ArrowType.Int(32, true))));
  private static final Schema NO_COLUMNS = new Schema(Collections.emptyList());
  private static final FlightInfo FLIGHT_INFO =
      new FlightInfo(
          ID_SCHEMA, FlightDescriptor.command(new byte[0]), Collections.emptyList(), -1, -1);

  private static final String QUERY = "select id from t where a = ? and b = ?";
  private static final String UPDATE = "insert into t values (?, ?)";

  private final ArrowFlightSqlClientHandler handler = mock(ArrowFlightSqlClientHandler.class);
  private final SqlDialect dialect = new AnsiSqlDialect();

  private ClientSidePreparedStatement create(final String sql) {
    return ClientSidePreparedStatement.create(handler, dialect, sql, true);
  }

  /** Makes the handler prepare {@code sql} on the server, which gives the returned handle. */
  private ArrowFlightSqlClientHandler.PreparedStatement prepares(final String sql) {
    final ArrowFlightSqlClientHandler.PreparedStatement prepared =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    when(handler.prepare(sql)).thenReturn(prepared);
    return prepared;
  }

  private static List<TypedValue> values(final Object... values) {
    final TypedValue[] typedValues = new TypedValue[values.length];
    for (int i = 0; i < values.length; i++) {
      typedValues[i] =
          values[i] == null ? null : TypedValue.ofLocal(Rep.of(values[i].getClass()), values[i]);
    }
    return Arrays.asList(typedValues);
  }

  @Test
  public void testParameterCount() {
    assertEquals(2, create(QUERY).getParameterCount());
    assertEquals(0, create("select id from t").getParameterCount());
    assertEquals(1, create("select id from t where a = '?' and b = ?").getParameterCount());
  }

  @Test
  public void testQueryIsSentWithItsValues() throws SQLException {
    when(prepares("select id from t where a = 5 and b = 'it''s'").executeQuery())
        .thenReturn(FLIGHT_INFO);
    final ClientSidePreparedStatement statement = create(QUERY);
    statement.bind(values(5, "it's"));
    assertSame(FLIGHT_INFO, statement.executeQuery());
  }

  @Test
  public void testUpdateIsSentWithItsValues() {
    when(prepares("insert into t values (5, NULL)").executeUpdate()).thenReturn(3L);
    final ClientSidePreparedStatement statement = create(UPDATE);
    statement.bind(values(5, null));
    assertEquals(3L, statement.executeUpdate());
  }

  @Test
  public void testBindReplacesThePreviousValues() {
    when(prepares("insert into t values (1, 'a')").executeUpdate()).thenReturn(1L);
    when(prepares("insert into t values (2, 'b')").executeUpdate()).thenReturn(2L);
    final ClientSidePreparedStatement statement = create(UPDATE);
    statement.bind(values(1, "a"));
    assertEquals(1L, statement.executeUpdate());
    statement.bind(values(2, "b"));
    assertEquals(2L, statement.executeUpdate());
  }

  @Test
  public void testStatementWithoutParametersNeedsNoBinding() throws SQLException {
    when(prepares("select id from t").executeQuery()).thenReturn(FLIGHT_INFO);
    assertSame(FLIGHT_INFO, create("select id from t").executeQuery());
  }

  @Test
  public void testBindRejectsTheWrongNumberOfValues() {
    final ClientSidePreparedStatement statement = create(QUERY);
    assertThrows(IllegalStateException.class, () -> statement.bind(values(1)));
    assertThrows(IllegalStateException.class, () -> statement.bind(values(1, 2, 3)));
    assertThrows(IllegalStateException.class, () -> statement.bind(Collections.emptyList()));
    verifyNoInteractions(handler);
  }

  @Test
  public void testValueTheDialectDoesNotSupportIsUnsupported() {
    final ClientSidePreparedStatement statement = create(QUERY);
    assertThrows(UnsupportedOperationException.class, () -> statement.bind(values(1, Double.NaN)));
  }

  @Test
  public void testValueTheDialectRejectsIsIllegal() {
    final ClientSidePreparedStatement statement = create(QUERY);
    assertThrows(IllegalArgumentException.class, () -> statement.bind(values(1, "a\0b")));
  }

  @Test
  public void testVerbatimStatementHasNoParameters() throws SQLException {
    final String sql = "select id from t where a ? 'x'";
    when(prepares(sql).executeQuery()).thenReturn(FLIGHT_INFO);
    final ClientSidePreparedStatement statement =
        ClientSidePreparedStatement.verbatim(handler, dialect, sql, true);
    assertEquals(0, statement.getParameterCount());
    assertSame(FLIGHT_INFO, statement.executeQuery());
  }

  @Test
  public void testTypeOfAQuery() {
    final ClientSidePreparedStatement statement = create(QUERY);
    assertEquals(StatementType.SELECT, statement.getType());
    assertEquals(Boolean.FALSE, statement.isUpdate());
  }

  @Test
  public void testTypeOfAnUpdate() {
    final ClientSidePreparedStatement statement = create(UPDATE);
    assertEquals(StatementType.UPDATE, statement.getType());
    assertEquals(Boolean.TRUE, statement.isUpdate());
  }

  @Test
  public void testThereIsNoParameterSchema() {
    assertEquals(NO_COLUMNS, create(QUERY).getParameterSchema());
  }

  @Test
  public void testSetParametersIsNotSupported() {
    assertThrows(UnsupportedOperationException.class, () -> create(QUERY).setParameters(null));
  }

  @Test
  public void testCloseSendsNothing() {
    create(QUERY).close();
    verifyNoInteractions(handler);
  }

  @Test
  public void testToStringDoesNotShowTheValues() {
    final ClientSidePreparedStatement statement = create(QUERY);
    statement.bind(values(5, "secret"));
    assertTrue(statement.toString().contains(QUERY));
    assertFalse(statement.toString().contains("secret"));
  }

  @Test
  public void testProbeReplacesTheParametersWithNull() {
    when(prepares("select id from t where a = NULL and b = NULL").getDataSetSchema())
        .thenReturn(ID_SCHEMA);
    final ClientSidePreparedStatement statement = create(QUERY);
    assertEquals(NO_COLUMNS, statement.getDataSetSchema());
    assertEquals(ID_SCHEMA, statement.probeDataSetSchema());
    assertEquals(ID_SCHEMA, statement.getDataSetSchema());
  }

  @Test
  public void testProbeIsSentOnlyOnce() {
    final ArrowFlightSqlClientHandler.PreparedStatement prepared =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    when(handler.prepare(anyString())).thenReturn(prepared);
    when(prepared.getDataSetSchema()).thenReturn(ID_SCHEMA);
    final ClientSidePreparedStatement statement = create(QUERY);
    assertEquals(ID_SCHEMA, statement.probeDataSetSchema());
    assertEquals(ID_SCHEMA, statement.probeDataSetSchema());
    verify(handler, times(1)).prepare(anyString());
    verify(prepared).close();
    verify(handler, never()).getInfo(anyString());
  }

  @Test
  public void testProbeUsesTheStatementOfTheDialect() {
    final SqlDialect wrapping =
        new AnsiSqlDialect() {
          @Override
          public String metadataProbeSql(ScannedSql sql) {
            return "select * from (" + super.metadataProbeSql(sql) + ") where 1 = 0";
          }
        };
    when(prepares("select * from (select id from t where a = NULL) where 1 = 0").getDataSetSchema())
        .thenReturn(ID_SCHEMA);
    final ClientSidePreparedStatement statement =
        ClientSidePreparedStatement.create(handler, wrapping, "select id from t where a = ?", true);
    assertEquals(ID_SCHEMA, statement.probeDataSetSchema());
  }

  @Test
  public void testUpdateIsNeverProbed() {
    final ClientSidePreparedStatement statement = create(UPDATE);
    assertEquals(NO_COLUMNS, statement.probeDataSetSchema());
    verifyNoInteractions(handler);
  }

  @Test
  public void testProbeCanBeDisabled() {
    final ClientSidePreparedStatement statement =
        ClientSidePreparedStatement.create(handler, dialect, QUERY, false);
    assertEquals(NO_COLUMNS, statement.probeDataSetSchema());
    verifyNoInteractions(handler);
  }

  @Test
  public void testFailedProbeLeavesTheSchemaEmptyAndIsNotRetried() {
    when(handler.prepare(anyString())).thenThrow(CallStatus.INTERNAL.toRuntimeException());
    final ClientSidePreparedStatement statement = create(QUERY);
    assertEquals(NO_COLUMNS, statement.probeDataSetSchema());
    assertEquals(NO_COLUMNS, statement.probeDataSetSchema());
    verify(handler, times(1)).prepare(anyString());
  }

  @Test
  public void testPreparedQueryUsesAHandlePerExecution() throws Exception {
    final ArrowFlightSqlClientHandler.PreparedStatement first =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    final ArrowFlightSqlClientHandler.PreparedStatement second =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    when(handler.prepare("select id from t where a = 1 and b = 2")).thenReturn(first);
    when(handler.prepare("select id from t where a = 3 and b = 4")).thenReturn(second);
    when(first.executeQuery()).thenReturn(FLIGHT_INFO);
    when(second.executeQuery()).thenReturn(FLIGHT_INFO);
    final ClientSidePreparedStatement statement = create(QUERY);
    statement.bind(values(1, 2));
    assertSame(FLIGHT_INFO, statement.executeQuery());
    verify(first, never()).close();
    statement.bind(values(3, 4));
    assertSame(FLIGHT_INFO, statement.executeQuery());
    verify(first).close();
    verify(second, never()).close();
    statement.close();
    verify(second).close();
    verify(handler, never()).getInfo(anyString());
  }

  @Test
  public void testPreparedUpdateClosesTheHandle() {
    final ArrowFlightSqlClientHandler.PreparedStatement prepared =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    when(handler.prepare("insert into t values (1, 2)")).thenReturn(prepared);
    when(prepared.executeUpdate()).thenThrow(new IllegalStateException("boom"));
    final ClientSidePreparedStatement statement = create(UPDATE);
    statement.bind(values(1, 2));
    assertThrows(IllegalStateException.class, statement::executeUpdate);
    verify(prepared).close();
    verify(handler, never()).executeUpdate(anyString());
  }

  @Test
  public void testPreparedStatementWithoutParametersReusesItsHandle() throws Exception {
    final ArrowFlightSqlClientHandler.PreparedStatement prepared =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    when(handler.prepare("select id from t")).thenReturn(prepared);
    when(prepared.executeQuery()).thenReturn(FLIGHT_INFO);
    final ClientSidePreparedStatement statement = create("select id from t");
    statement.executeQuery();
    statement.executeQuery();
    verify(handler, times(1)).prepare("select id from t");
    verify(prepared, never()).close();
    statement.close();
    verify(prepared).close();
  }

  @Test
  public void testUpdateOfADialectThatNeedsQueriesRunsAsAQuery() throws Exception {
    final ArrowFlightSqlClientHandler.PreparedStatement prepared =
        mock(ArrowFlightSqlClientHandler.PreparedStatement.class);
    final FlightStream stream = mock(FlightStream.class);
    when(stream.next()).thenReturn(true, false);
    when(handler.prepare("insert into t values (1, 2)")).thenReturn(prepared);
    when(prepared.executeQuery()).thenReturn(FLIGHT_INFO);
    when(handler.getStreams(FLIGHT_INFO))
        .thenReturn(List.of(new CloseableEndpointStreamPair(stream, null)));
    final ClientSidePreparedStatement statement =
        ClientSidePreparedStatement.create(handler, new DorisSqlDialect(), UPDATE, true);
    statement.bind(values(1, 2));
    assertEquals(0L, statement.executeUpdate());
    verify(stream, times(2)).next();
    verify(stream).close();
    verify(prepared, never()).executeUpdate();
    verify(prepared).close();
  }
}
