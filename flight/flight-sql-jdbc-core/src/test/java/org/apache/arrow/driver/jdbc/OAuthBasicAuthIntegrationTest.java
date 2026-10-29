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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.StartStop;
import org.apache.arrow.driver.jdbc.authentication.UserPasswordAuthentication;
import org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty;
import org.apache.arrow.driver.jdbc.utils.MockFlightSqlProducer;
import org.apache.arrow.flight.FlightMethod;
import org.apache.arrow.flight.sql.FlightSqlProducer.Schemas;
import org.apache.arrow.flight.sql.impl.FlightSql.CommandGetCatalogs;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.util.AutoCloseables;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for OAuth with {@code oauth.basicAuth.user}: the OAuth token is used as the
 * password of a Basic auth handshake and the session token returned by the server is used
 * afterwards.
 */
public class OAuthBasicAuthIntegrationTest {

  private static final String VALID_ACCESS_TOKEN = "valid-oauth-access-token-12345";
  private static final String BASIC_AUTH_USER = "oauth-basic-user";
  private static final String CLIENT_ID = "test-client-id";
  private static final String CLIENT_SECRET = "test-client-secret";
  private static final String SUBJECT_TOKEN = "original-subject-token";
  private static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

  private static final MockFlightSqlProducer FLIGHT_SQL_PRODUCER = new MockFlightSqlProducer();

  @RegisterExtension public static FlightServerTestExtension FLIGHT_SERVER_TEST_EXTENSION;

  static {
    FLIGHT_SERVER_TEST_EXTENSION =
        new FlightServerTestExtension.Builder()
            .authentication(
                new UserPasswordAuthentication.Builder()
                    .user(BASIC_AUTH_USER, VALID_ACCESS_TOKEN)
                    .build())
            .producer(FLIGHT_SQL_PRODUCER)
            .build();
  }

  @StartStop private final MockWebServer oauthServer = new MockWebServer();
  private URI tokenEndpoint;

  @BeforeAll
  public static void setUpClass() {
    FLIGHT_SQL_PRODUCER.addCatalogQuery(
        CommandGetCatalogs.getDefaultInstance(),
        listener -> {
          try (BufferAllocator allocator = new RootAllocator();
              VectorSchemaRoot root =
                  VectorSchemaRoot.create(Schemas.GET_CATALOGS_SCHEMA, allocator)) {
            root.setRowCount(0);
            listener.start(root);
            listener.putNext();
          } catch (Throwable t) {
            listener.error(t);
          } finally {
            listener.completed();
          }
        });
  }

  @AfterAll
  public static void tearDownClass() {
    AutoCloseables.closeNoChecked(FLIGHT_SQL_PRODUCER);
  }

  @BeforeEach
  public void setUp() {
    tokenEndpoint = oauthServer.url("/oauth/token").uri();
  }

  private void enqueueSuccessfulTokenResponse(String token) {
    String body =
        String.format(
            "{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":3600}", token);
    oauthServer.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build());
  }

  private Properties createClientCredentialsProperties() {
    Properties props = new Properties();
    props.put(ArrowFlightConnectionProperty.HOST.camelName(), "localhost");
    props.put(
        ArrowFlightConnectionProperty.PORT.camelName(), FLIGHT_SERVER_TEST_EXTENSION.getPort());
    props.put(ArrowFlightConnectionProperty.USE_ENCRYPTION.camelName(), false);
    props.put(ArrowFlightConnectionProperty.OAUTH_FLOW.camelName(), "client_credentials");
    props.put(ArrowFlightConnectionProperty.OAUTH_TOKEN_URI.camelName(), tokenEndpoint.toString());
    props.put(ArrowFlightConnectionProperty.OAUTH_CLIENT_ID.camelName(), CLIENT_ID);
    props.put(ArrowFlightConnectionProperty.OAUTH_CLIENT_SECRET.camelName(), CLIENT_SECRET);
    props.put(ArrowFlightConnectionProperty.OAUTH_BASIC_AUTH_USER.camelName(), BASIC_AUTH_USER);
    return props;
  }

  private String getJdbcUrl() {
    return String.format(
        "jdbc:arrow-flight-sql://localhost:%d", FLIGHT_SERVER_TEST_EXTENSION.getPort());
  }

  private static String basicHeader(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testClientCredentialsWithBasicAuth() throws Exception {
    enqueueSuccessfulTokenResponse(VALID_ACCESS_TOKEN);

    try (Connection conn =
        DriverManager.getConnection(getJdbcUrl(), createClientCredentialsProperties())) {
      assertFalse(conn.isClosed());
      conn.getMetaData().getCatalogs().close();
    }

    FlightServerTestExtension.InterceptorMiddleware.Factory interceptor =
        FLIGHT_SERVER_TEST_EXTENSION.getInterceptorFactory();
    // The handshake uses the OAuth token as the Basic auth password
    assertEquals(
        basicHeader(BASIC_AUTH_USER, VALID_ACCESS_TOKEN),
        interceptor.getHeader(FlightMethod.HANDSHAKE, "authorization"));
    // Subsequent calls use the session token returned by the server, not the OAuth token
    String callAuthHeader = interceptor.getHeader(FlightMethod.GET_FLIGHT_INFO, "authorization");
    assertNotNull(callAuthHeader);
    assertTrue(callAuthHeader.startsWith("Bearer "));
    assertNotEquals("Bearer " + VALID_ACCESS_TOKEN, callAuthHeader);
  }

  @Test
  public void testTokenExchangeWithBasicAuth() throws Exception {
    enqueueSuccessfulTokenResponse(VALID_ACCESS_TOKEN);

    Properties props = createClientCredentialsProperties();
    props.remove(ArrowFlightConnectionProperty.OAUTH_CLIENT_ID.camelName());
    props.remove(ArrowFlightConnectionProperty.OAUTH_CLIENT_SECRET.camelName());
    props.put(ArrowFlightConnectionProperty.OAUTH_FLOW.camelName(), "token_exchange");
    props.put(
        ArrowFlightConnectionProperty.OAUTH_EXCHANGE_SUBJECT_TOKEN.camelName(), SUBJECT_TOKEN);
    props.put(
        ArrowFlightConnectionProperty.OAUTH_EXCHANGE_SUBJECT_TOKEN_TYPE.camelName(),
        SUBJECT_TOKEN_TYPE);

    try (Connection conn = DriverManager.getConnection(getJdbcUrl(), props)) {
      conn.getMetaData().getCatalogs().close();
    }

    assertEquals(1, oauthServer.getRequestCount());
  }

  @Test
  public void testSingleTokenRequestAcrossOperations() throws Exception {
    enqueueSuccessfulTokenResponse(VALID_ACCESS_TOKEN);

    try (Connection conn =
        DriverManager.getConnection(getJdbcUrl(), createClientCredentialsProperties())) {
      conn.isValid(5);
      conn.getMetaData().getCatalogs().close();
      conn.getMetaData().getCatalogs().close();
    }

    assertEquals(1, oauthServer.getRequestCount());
  }

  @Test
  public void testWrongBasicAuthUserIsRejected() {
    enqueueSuccessfulTokenResponse(VALID_ACCESS_TOKEN);

    Properties props = createClientCredentialsProperties();
    props.put(ArrowFlightConnectionProperty.OAUTH_BASIC_AUTH_USER.camelName(), "unknown-user");

    assertThrows(SQLException.class, () -> DriverManager.getConnection(getJdbcUrl(), props));
  }

  @Test
  public void testTokenNotAcceptedByServerIsRejected() {
    enqueueSuccessfulTokenResponse("some-other-token");

    assertThrows(
        SQLException.class,
        () -> DriverManager.getConnection(getJdbcUrl(), createClientCredentialsProperties()));
  }

  @Test
  public void testBasicAuthUserWithoutFlowIsRejected() {
    Properties props = createClientCredentialsProperties();
    props.remove(ArrowFlightConnectionProperty.OAUTH_FLOW.camelName());

    SQLException exception =
        assertThrows(SQLException.class, () -> DriverManager.getConnection(getJdbcUrl(), props));
    assertEquals("oauth.basicAuth.user requires oauth.flow", exception.getMessage());
    assertEquals(0, oauthServer.getRequestCount());
  }
}
