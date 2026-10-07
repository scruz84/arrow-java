.. Licensed to the Apache Software Foundation (ASF) under one
.. or more contributor license agreements.  See the NOTICE file
.. distributed with this work for additional information
.. regarding copyright ownership.  The ASF licenses this file
.. to you under the Apache License, Version 2.0 (the
.. "License"); you may not use this file except in compliance
.. with the License.  You may obtain a copy of the License at

..   http://www.apache.org/licenses/LICENSE-2.0

.. Unless required by applicable law or agreed to in writing,
.. software distributed under the License is distributed on an
.. "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
.. KIND, either express or implied.  See the License for the
.. specific language governing permissions and limitations
.. under the License.

============================
Arrow Flight SQL JDBC Driver
============================

The Flight SQL JDBC driver is a JDBC driver implementation that uses
the :external+arrow:doc:`Flight SQL protocol <format/FlightSql>` under
the hood.  This driver can be used with any database that implements
Flight SQL.

Installation and Requirements
=============================

The driver is compatible with JDK 17+. Note that the following JVM
parameter is required:

.. code-block:: shell

   java --add-opens=java.base/java.nio=ALL-UNNAMED ...

To add a dependency via Maven, use a ``pom.xml`` like the following:

.. code-block:: xml

   <?xml version="1.0" encoding="UTF-8"?>
   <project xmlns="http://maven.apache.org/POM/4.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
     <modelVersion>4.0.0</modelVersion>
     <groupId>org.example</groupId>
     <artifactId>demo</artifactId>
     <version>1.0-SNAPSHOT</version>
     <properties>
       <arrow.version>18.1.0</arrow.version>
     </properties>
     <dependencies>
       <dependency>
         <groupId>org.apache.arrow</groupId>
         <artifactId>flight-sql-jdbc-driver</artifactId>
         <version>${arrow.version}</version>
       </dependency>
     </dependencies>
   </project>

Connecting to a Database
========================

The URI format is as follows::

  jdbc:arrow-flight-sql://HOSTNAME:PORT[/?param1=val1&param2=val2&...]

For example, take this URI::

  jdbc:arrow-flight-sql://localhost:12345/?username=admin&password=pass&useEncryption=1

This will connect to a Flight SQL service running on ``localhost`` on
port 12345.  It will create a secure, encrypted connection, and
authenticate using the username ``admin`` and the password ``pass``.

The components of the URI are as follows.

* The URI scheme must be ``jdbc:arrow-flight-sql://``.
* **HOSTNAME** is the hostname of the Flight SQL service.
* **PORT** is the port of the Flight SQL service.

Additional options can be passed as query parameters. Parameter names are
case-sensitive. The supported parameters are:

.. list-table::
   :header-rows: 1

   * - Parameter
     - Default
     - Description

   * - disableCertificateVerification
     - false
     - When TLS is enabled, whether to verify the server certificate

   * - password
     - null
     - The password for user/password authentication

   * - threadPoolSize
     - 1
     - The size of an internal thread pool

   * - token
     - null
     - The token used for token authentication

   * - trustStore
     - null
     - When TLS is enabled, the path to the certificate store. Blank values
       are treated as unset. Takes precedence over ``useSystemTrustStore``,
       and is ignored when ``tlsRootCerts`` is set.

   * - trustStorePassword
     - null
     - When TLS is enabled, the password for the certificate store. Required
       when ``trustStore`` is set.

   * - tlsRootCerts
     - null
     - Path to PEM-encoded root certificates for TLS - use this as
       an alternative to ``trustStore``. Blank values are treated as unset.

   * - hostnameOverride
     - null
     - When TLS is enabled, the hostname to verify the server certificate
       against and to send for SNI and as the gRPC ``:authority`` header,
       instead of **HOSTNAME**. Use this when
       connecting through a tunnel, a private endpoint, or an IP address
       that does not match the server certificate. The value replaces the
       whole ``:authority``, so **PORT** is not sent unless it is included,
       for example ``flight.example.com:32010``; only the hostname is used
       for SNI and certificate verification. Endpoints returned by
       the server are only verified against this hostname if they are on
       the same host and port as the connection. Ignored when
       ``useEncryption`` is false.

   * - clientCertificate
     - null
     - Path to PEM-encoded client mTLS certificate when the Flight
       SQL server requires client verification.

   * - clientKey
     - null
     - Path to PEM-encoded client mTLS key when the Flight
       SQL server requires client verification.

   * - useEncryption
     - true
     - Whether to use TLS (the default is an encrypted connection).
       When true, endpoint locations returned by the server that do not
       use TLS, such as ``grpc+tcp``, are refused and the endpoint's
       other locations are tried instead.

   * - user
     - null
     - The username for user/password authentication

   * - useSystemTrustStore
     - true
     - When TLS is enabled, whether to use the system certificate store.
       Ignored when ``trustStore`` or ``tlsRootCerts`` is set.

   * - retainCookies
     - true
     - Whether to use cookies from the initial connection in subsequent
       internal connections when retrieving streams from separate endpoints.

   * - retainAuth
     - true
     - Whether to use bearer tokens obtained from the initial connection
       in subsequent internal connections used for retrieving streams
       from separate endpoints.

   * - disableServerPreparedStatements
     - false
     - Do not prepare any statement on the server. The driver writes the
       parameter values into the SQL text instead. See
       :ref:`flight-sql-jdbc-client-side-statements`.

   * - disableServerPreparedQueries
     - false
     - Like ``disableServerPreparedStatements``, but only for queries.
       Updates with parameters are still prepared on the server. Implied by
       ``disableServerPreparedStatements``.

   * - dialect
     - ansi
     - The name of the SQL dialect used to write parameter values into the
       SQL text. Only used for client-side statements.

   * - dialectClass
     - null
     - The fully qualified name of a ``SqlDialect`` class to use instead of
       looking one up by name.

   * - clientSideMetadataProbe
     - true
     - Whether the result set metadata of a client-side query may be fetched
       from the server before the query runs.

Note that URI values must be URI-encoded if they contain characters such
as !, @, $, etc.

Any URI parameters that are not handled by the driver are passed to
the Flight SQL service as gRPC headers. For example, the following URI ::

  jdbc:arrow-flight-sql://localhost:12345/?useEncryption=0&database=mydb

This will connect without authentication or encryption, to a Flight
SQL service running on ``localhost`` on port 12345. Each request will
also include a ``database=mydb`` gRPC header.

Connection parameters may also be supplied using the Properties object
when using the JDBC Driver Manager to connect. When supplying using
the Properties object, values should *not* be URI-encoded.

Parameters specified by the URI supercede parameters supplied by the
Properties object. When calling the `user/password overload of
DriverManager#getConnection()
<https://docs.oracle.com/javase/8/docs/api/java/sql/DriverManager.html#getConnection-java.lang.String-java.lang.String-java.lang.String->`_,
the username and password supplied on the URI supercede the username and
password arguments to the function call.

Use ``DriverManager.setLoginTimeout(seconds)`` to limit the time the driver
waits for a connection, including the authentication handshake and initial
session setup. On expiration, the driver cancels pending login RPCs and throws
``SQLTimeoutException``. A value of zero disables the login timeout. The
``connectTimeoutMs`` property separately controls the Netty socket connection
timeout.

.. _flight-sql-jdbc-client-side-statements:

Client-Side Prepared Statements
===============================

By default a ``PreparedStatement`` is prepared on the server, and its
parameters are sent separately from the SQL text.  Some Flight SQL
services cannot do that: they accept a statement, but cannot prepare it or
bind parameters.  For those, the driver can keep the ``PreparedStatement``
API and do the binding itself, by replacing each ``?`` with the value
written as a SQL literal.  The server then receives a statement without
parameters, which the driver sends through the Flight SQL prepared statement
endpoints.

Two properties choose which statements are handled this way:

* ``disableServerPreparedStatements=true``: no statement is prepared on
  the server.  This also applies to a plain ``Statement``, which is sent to
  the server exactly as written, so a ``?`` in it is just a character.  A
  plain ``Statement`` is only handled this way by this property, not by
  ``disableServerPreparedQueries``.
* ``disableServerPreparedQueries=true``: only queries are handled by the
  driver.  Statements that change data or schema are still prepared on the
  server.  The driver decides which is which by the first keyword of the
  statement: ``SELECT``, ``WITH``, ``VALUES``, ``SHOW``, ``DESCRIBE``,
  ``DESC``, ``EXPLAIN`` and ``TABLE`` start a query, and anything else is an
  update.  A dialect can change this rule.

.. code-block:: java

   Properties properties = new Properties();
   properties.put("disableServerPreparedStatements", "true");
   try (Connection connection = DriverManager.getConnection(
            "jdbc:arrow-flight-sql://localhost:12345/?useEncryption=0", properties);
        PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM orders WHERE customer = ? AND placed > ?")) {
     statement.setString(1, "O'Brien");
     statement.setObject(2, LocalDate.of(2024, 1, 31));
     // The server receives:
     //   SELECT id FROM orders WHERE customer = 'O''Brien' AND placed > DATE '2024-01-31'
     try (ResultSet resultSet = statement.executeQuery()) {
       ...
     }
   }

Limitations
-----------

* The statement text is different for every set of values, so the server
  cannot reuse a plan, and values appear in its logs.  The driver does not
  log them above DEBUG level.
* A parameter that was never set is written as ``NULL``.
* ``setNull`` loses its SQL type, so a null is written as plain ``NULL``.
  A ``null`` inside an array is typed when the array declares its element
  type.
* A ``Struct`` has no field names in JDBC, so it is written as a positional
  row.
* Streams and LOBs are read when set, and are limited to 16 MiB.
* ``{fn ...}`` and ``{call ...}`` escapes are not translated.
* ``executeBatch`` runs one statement per row of parameters.
* The ``backslashEscapesInStrings`` rule of a dialect applies to every string
  literal.  A prefix that turns backslash escapes on for a single literal,
  such as ``E'...'`` in PostgreSQL, is not recognized when looking for
  parameter markers.

Result set metadata
-------------------

A server-prepared statement knows its result columns before it runs.  A
client-side query does not, so the first call to
``PreparedStatement#getMetaData()`` asks the server for the schema: the
driver prepares the statement with every ``?`` replaced by ``NULL`` and reads
the schema from the result, without running it or fetching any rows.  This happens once per
statement, and never for updates.  If it fails, the metadata has no columns
and nothing is reported.  Once the query has run, the metadata comes from the
results.

Replacing a ``?`` with ``NULL`` can change what the server infers: ``SELECT ?``
has no type, for example.  Some services also do real work when asked for the
schema.  Set ``clientSideMetadataProbe=false`` to turn this off.
``ParameterMetaData`` always reports unknown parameter types.

Writing a dialect
-----------------

A dialect implements ``org.apache.arrow.driver.jdbc.dialect.SqlDialect``.
Every method but ``name()`` has an ANSI default, so a dialect only overrides
what its database does differently:

.. code-block:: java

   package com.example;

   import java.sql.SQLException;
   import org.apache.arrow.driver.jdbc.dialect.SqlDialect;
   import org.apache.arrow.driver.jdbc.dialect.SqlLexicalRules;

   public class MyDbDialect implements SqlDialect {
     @Override
     public String name() {
       return "mydb";
     }

     // How to find the ? markers: this database lets a backslash escape a quote.
     @Override
     public SqlLexicalRules lexicalRules() {
       return SqlLexicalRules.builder().backslashEscapesInStrings(true).build();
     }

     @Override
     public String formatBoolean(boolean value) throws SQLException {
       return value ? "1" : "0";
     }

     @Override
     public String formatString(String value, boolean national) throws SQLException {
       return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
     }
   }

Values reach the dialect as a small typed model in
``org.apache.arrow.driver.jdbc.dialect.value`` (``SqlScalar``, ``SqlNull``,
``SqlArray``, ``SqlRow`` and ``SqlIntervalValue``), never as JDBC or Avatica
objects.  ``render(SqlValue)`` is the entry point, and arrays and rows render
their elements through it, so an override applies at every level.  A
dialect can also override ``classify(String)`` to decide what is a query and
``metadataProbeSql(ScannedSql)`` to change the metadata probe.

The default ``formatString`` doubles the quotes of a string.  It also doubles
the backslashes when ``lexicalRules()`` says that a backslash is an escape,
so a value can never end its literal early.  A dialect that overrides
``formatString``, as the one above does, has to keep that guarantee itself.

The driver finds dialects with ``java.util.ServiceLoader``.  List the class
in ``META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect`` in
the jar that contains it, and put that jar on the classpath:

.. code-block:: text

   com.example.MyDbDialect

Then select it with ``dialect=mydb``.  Names are not case-sensitive.  If two
dialects have the same name, the one with the higher ``priority()`` wins, and
equal priorities are an error.  A class that cannot be loaded is skipped with a
warning.

Where service files are not visible, for example in some application servers,
name the class directly with ``dialectClass=com.example.MyDbDialect``.

The dialect classes are the same in ``flight-sql-jdbc-driver``, which is
shaded: they are not relocated, so a dialect compiles and runs against either
artifact.

OAuth 2.0 Authentication
========================

The driver supports OAuth 2.0 authentication for obtaining access tokens
from an authorization server. Two OAuth flows are currently supported:

* **Client Credentials** - For service-to-service authentication where no
  user interaction is required. The application authenticates using its own
  credentials (client ID and client secret).

* **Token Exchange** (RFC 8693) - For exchanging one token for another,
  commonly used for federated authentication, delegation, or impersonation
  scenarios.

OAuth Connection Properties
---------------------------

The following properties configure OAuth authentication. These properties
should be provided via the ``Properties`` object when connecting, as they
may contain special characters that are difficult to encode in a URI.

**Common OAuth Properties**

.. list-table::
   :header-rows: 1

   * - Parameter
     - Type
     - Required
     - Default
     - Description

   * - oauth.flow
     - String
     - Yes (to enable OAuth)
     - null
     - The OAuth grant type. Supported values: ``client_credentials``,
       ``token_exchange``

   * - oauth.tokenUri
     - String
     - Yes
     - null
     - The OAuth 2.0 token endpoint URL (e.g.,
       ``https://auth.example.com/oauth/token``)

   * - oauth.clientId
     - String
     - Conditional
     - null
     - The OAuth 2.0 client ID. Required for ``client_credentials`` flow,
       optional for ``token_exchange``

   * - oauth.clientSecret
     - String
     - Conditional
     - null
     - The OAuth 2.0 client secret. Required for ``client_credentials`` flow,
       optional for ``token_exchange``

   * - oauth.scope
     - String
     - No
     - null
     - Space-separated list of OAuth scopes to request

   * - oauth.resource
     - String
     - No
     - null
     - The resource indicator for the token request (RFC 8707)

**Token Exchange Properties**

These properties are specific to the ``token_exchange`` flow:

.. list-table::
   :header-rows: 1

   * - Parameter
     - Type
     - Required
     - Default
     - Description

   * - oauth.exchange.subjectToken
     - String
     - Yes
     - null
     - The subject token to exchange (e.g., a JWT from an identity provider)

   * - oauth.exchange.subjectTokenType
     - String
     - Yes
     - null
     - The token type URI of the subject token. Common values:
       ``urn:ietf:params:oauth:token-type:access_token``,
       ``urn:ietf:params:oauth:token-type:jwt``

   * - oauth.exchange.actorToken
     - String
     - No
     - null
     - The actor token for delegation/impersonation scenarios

   * - oauth.exchange.actorTokenType
     - String
     - No
     - null
     - The token type URI of the actor token

   * - oauth.exchange.aud
     - String
     - No
     - null
     - The target audience for the exchanged token

   * - oauth.exchange.requestedTokenType
     - String
     - No
     - null
     - The desired token type for the exchanged token
