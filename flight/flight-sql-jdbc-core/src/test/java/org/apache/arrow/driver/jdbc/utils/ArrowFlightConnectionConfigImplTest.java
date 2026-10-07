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
package org.apache.arrow.driver.jdbc.utils;

import static java.lang.Runtime.getRuntime;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.CATALOG;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.CONNECT_TIMEOUT_MILLIS;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.HOST;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.PASSWORD;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.PORT;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.THREAD_POOL_SIZE;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.USER;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.USE_CLIENT_CACHE;
import static org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty.USE_ENCRYPTION;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.Duration;
import java.util.Properties;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.arrow.driver.jdbc.utils.ArrowFlightConnectionConfigImpl.ArrowFlightConnectionProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public final class ArrowFlightConnectionConfigImplTest {

  private static final Random RANDOM = new Random(12L);

  private Properties properties;
  private ArrowFlightConnectionConfigImpl arrowFlightConnectionConfig;

  public ArrowFlightConnectionProperty property;
  public Object value;
  public Function<ArrowFlightConnectionConfigImpl, ?> arrowFlightConnectionConfigFunction;

  @BeforeEach
  public void setUp() {
    properties = new Properties();
    arrowFlightConnectionConfig = new ArrowFlightConnectionConfigImpl(properties);
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void testGetProperty(
      ArrowFlightConnectionProperty property,
      Object value,
      Object expected,
      Function<ArrowFlightConnectionConfigImpl, ?> configFunction) {
    properties.put(property.camelName(), value);
    arrowFlightConnectionConfigFunction = configFunction;
    assertThat(configFunction.apply(arrowFlightConnectionConfig), is(expected));
    assertThat(
        arrowFlightConnectionConfigFunction.apply(arrowFlightConnectionConfig), is(expected));
  }

  public static Stream<Arguments> provideParameters() {
    int port = RANDOM.nextInt(Short.toUnsignedInt(Short.MAX_VALUE));
    boolean useEncryption = RANDOM.nextBoolean();
    int threadPoolSize = RANDOM.nextInt(getRuntime().availableProcessors());
    return Stream.of(
        Arguments.of(
            HOST,
            "host",
            "host",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getHost),
        Arguments.of(
            PORT,
            port,
            port,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getPort),
        Arguments.of(
            USER,
            "user",
            "user",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getUser),
        Arguments.of(
            PASSWORD,
            "password",
            "password",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getPassword),
        Arguments.of(
            USE_ENCRYPTION,
            useEncryption,
            useEncryption,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::useEncryption),
        Arguments.of(
            THREAD_POOL_SIZE,
            threadPoolSize,
            threadPoolSize,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::threadPoolSize),
        Arguments.of(
            CATALOG,
            "catalog",
            "catalog",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getCatalog),
        Arguments.of(
            CONNECT_TIMEOUT_MILLIS,
            5000,
            Duration.ofMillis(5000),
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getConnectTimeout),
        Arguments.of(
            USE_CLIENT_CACHE,
            false,
            false,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::useClientCache),
        Arguments.of(
            ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_STATEMENTS,
            true,
            true,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::disableServerPreparedStatements),
        Arguments.of(
            ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_QUERIES,
            true,
            true,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::disableServerPreparedQueries),
        Arguments.of(
            ArrowFlightConnectionProperty.DIALECT,
            "postgresql",
            "postgresql",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getDialect),
        Arguments.of(
            ArrowFlightConnectionProperty.DIALECT_CLASS,
            "com.example.MyDialect",
            "com.example.MyDialect",
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::getDialectClass),
        Arguments.of(
            ArrowFlightConnectionProperty.CLIENT_SIDE_METADATA_PROBE,
            false,
            false,
            (Function<ArrowFlightConnectionConfigImpl, ?>)
                ArrowFlightConnectionConfigImpl::useClientSideMetadataProbe));
  }

  @Test
  public void testClientSidePreparedStatementDefaults() {
    assertThat(arrowFlightConnectionConfig.disableServerPreparedStatements(), is(false));
    assertThat(arrowFlightConnectionConfig.disableServerPreparedQueries(), is(false));
    assertThat(arrowFlightConnectionConfig.getDialect(), is("ansi"));
    assertThat(arrowFlightConnectionConfig.getDialectClass(), nullValue());
    assertThat(arrowFlightConnectionConfig.useClientSideMetadataProbe(), is(true));
  }

  @Test
  public void testDisablingAllStatementsImpliesDisablingQueries() {
    properties.put(
        ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_STATEMENTS.camelName(), true);
    assertThat(arrowFlightConnectionConfig.disableServerPreparedQueries(), is(true));
  }

  @Test
  public void testDisablingQueriesDoesNotDisableAllStatements() {
    properties.put(ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_QUERIES.camelName(), true);
    assertThat(arrowFlightConnectionConfig.disableServerPreparedQueries(), is(true));
    assertThat(arrowFlightConnectionConfig.disableServerPreparedStatements(), is(false));
  }

  @Test
  public void testClientSidePropertiesAreNotSentAsHeaders() {
    properties.put(
        ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_STATEMENTS.camelName(), true);
    properties.put(ArrowFlightConnectionProperty.DISABLE_SERVER_PREPARED_QUERIES.camelName(), true);
    properties.put(ArrowFlightConnectionProperty.DIALECT.camelName(), "postgresql");
    properties.put(ArrowFlightConnectionProperty.DIALECT_CLASS.camelName(), "com.example.D");
    properties.put(ArrowFlightConnectionProperty.CLIENT_SIDE_METADATA_PROBE.camelName(), false);
    assertThat(arrowFlightConnectionConfig.getHeaderAttributes().isEmpty(), is(true));
  }
}
