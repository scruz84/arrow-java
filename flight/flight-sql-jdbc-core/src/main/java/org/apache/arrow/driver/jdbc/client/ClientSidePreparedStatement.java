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
package org.apache.arrow.driver.jdbc.client;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.driver.jdbc.dialect.ScannedSql;
import org.apache.arrow.driver.jdbc.dialect.SqlDialect;
import org.apache.arrow.driver.jdbc.dialect.SqlParameterScanner;
import org.apache.arrow.driver.jdbc.dialect.StatementKind;
import org.apache.arrow.driver.jdbc.dialect.value.SqlValue;
import org.apache.arrow.driver.jdbc.utils.SqlValueFactory;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.avatica.Meta.StatementType;
import org.apache.calcite.avatica.remote.TypedValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A prepared statement that is never prepared on the server. The parameter values are written into
 * the SQL text on the client, and the resulting statement is sent as an ordinary Flight SQL
 * statement.
 *
 * <p>This lets the driver offer prepared statements on backends that cannot bind parameters. The
 * values are rendered by the connection's {@link SqlDialect}.
 */
public final class ClientSidePreparedStatement
    implements ArrowFlightSqlClientHandler.PreparedStatement {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientSidePreparedStatement.class);
  private static final Schema NO_COLUMNS = new Schema(List.of());

  private final ArrowFlightSqlClientHandler handler;
  private final SqlDialect dialect;
  private final String sql;
  private final ScannedSql scanned;
  private final StatementKind kind;
  private final boolean metadataProbe;

  private String renderedSql;
  private boolean probed;
  private Schema probedSchema = NO_COLUMNS;

  private ClientSidePreparedStatement(
      ArrowFlightSqlClientHandler handler,
      SqlDialect dialect,
      String sql,
      ScannedSql scanned,
      boolean metadataProbe) {
    this.handler = handler;
    this.dialect = dialect;
    this.sql = sql;
    this.scanned = scanned;
    this.kind = dialect.classify(sql);
    this.metadataProbe = metadataProbe;
    // A statement without parameters needs no binding before it runs.
    this.renderedSql = scanned.parameterCount() == 0 ? sql : "";
  }

  /**
   * Creates a statement whose {@code ?} markers are parameters.
   *
   * @param handler the client used to send the statement.
   * @param dialect the dialect that finds the markers and renders the values.
   * @param sql the statement text.
   * @param metadataProbe whether {@link #probeDataSetSchema()} may query the server.
   */
  public static ClientSidePreparedStatement create(
      ArrowFlightSqlClientHandler handler, SqlDialect dialect, String sql, boolean metadataProbe) {
    return new ClientSidePreparedStatement(
        handler,
        dialect,
        sql,
        SqlParameterScanner.scan(sql, dialect.lexicalRules()),
        metadataProbe);
  }

  /**
   * Creates a statement that is sent exactly as written, as for a plain JDBC {@code Statement},
   * where a {@code ?} is just a character.
   */
  public static ClientSidePreparedStatement verbatim(
      ArrowFlightSqlClientHandler handler, SqlDialect dialect, String sql, boolean metadataProbe) {
    return new ClientSidePreparedStatement(
        handler, dialect, sql, new ScannedSql(List.of(sql)), metadataProbe);
  }

  /** The number of parameters of the statement. */
  public int getParameterCount() {
    return scanned.parameterCount();
  }

  /**
   * Sets the parameter values and renders the statement with them.
   *
   * @param values one value per parameter. A {@code null} entry is a SQL null.
   * @throws IllegalStateException if the number of values is not the number of parameters.
   * @throws UnsupportedOperationException if a value cannot be represented in the dialect.
   * @throws IllegalArgumentException if a value is not valid.
   */
  public void bind(List<TypedValue> values) {
    if (values.size() != scanned.parameterCount()) {
      throw new IllegalStateException(
          String.format(
              "Prepared statement has %s parameters, but only received %s",
              scanned.parameterCount(), values.size()));
    }
    final List<String> literals = new ArrayList<>(values.size());
    try {
      for (TypedValue value : values) {
        final SqlValue sqlValue = SqlValueFactory.fromTypedValue(value);
        literals.add(dialect.render(sqlValue));
      }
    } catch (SQLFeatureNotSupportedException e) {
      throw new UnsupportedOperationException(e.getMessage(), e);
    } catch (SQLException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    renderedSql = scanned.join(literals);
  }

  /**
   * Asks the server for the result set schema without running the query, by sending the statement
   * with every parameter replaced as the dialect chooses (by default with {@code NULL}).
   *
   * <p>This only happens once, only for queries, and only if the {@code clientSideMetadataProbe}
   * property allows it. Any failure leaves the schema empty.
   *
   * @return the schema, which has no fields when it is not known.
   */
  public Schema probeDataSetSchema() {
    if (probed || !metadataProbe || kind != StatementKind.QUERY) {
      return probedSchema;
    }
    probed = true;
    try {
      final FlightInfo info = handler.getInfo(dialect.metadataProbeSql(scanned));
      probedSchema = info.getSchemaOptional().orElse(NO_COLUMNS);
    } catch (RuntimeException e) {
      LOGGER.debug("Could not probe the result set metadata of a client-side statement", e);
    }
    return probedSchema;
  }

  @Override
  public FlightInfo executeQuery() throws SQLException {
    return handler.getInfo(renderedSql);
  }

  @Override
  public long executeUpdate() {
    return handler.executeUpdate(renderedSql);
  }

  @Override
  public StatementType getType() {
    return kind == StatementKind.QUERY ? StatementType.SELECT : StatementType.UPDATE;
  }

  /** The result set schema if {@link #probeDataSetSchema()} found one, otherwise no fields. */
  @Override
  public Schema getDataSetSchema() {
    return probedSchema;
  }

  /** There is no server-side parameter schema: the parameters are only known by their count. */
  @Override
  public Schema getParameterSchema() {
    return NO_COLUMNS;
  }

  @Override
  public @Nullable Boolean isUpdate() {
    return kind == StatementKind.UPDATE;
  }

  @Override
  public void setParameters(VectorSchemaRoot parameters) {
    throw new UnsupportedOperationException(
        "Client-side prepared statements take their parameters through bind()");
  }

  @Override
  public void close() {
    // Nothing is held on the server.
  }

  @Override
  public String toString() {
    // Never include the rendered statement: it holds parameter values.
    return "ClientSidePreparedStatement{" + sql + "}";
  }
}
