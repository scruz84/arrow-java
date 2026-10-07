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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Check the content of the JDBC driver jar
 *
 * <p>After shading everything should be either under org.apache.arrow.driver.jdbc. package
 */
public class ITDriverJarValidation {
  /**
   * Use this property to provide path to the JDBC driver jar. Can be used to run the test from an
   * IDE
   */
  public static final String JDBC_DRIVER_PATH_OVERRIDE =
      System.getProperty("arrow-flight-jdbc-driver.jar.override");

  /** List of allowed prefixes a jar entry may match. */
  public static final Set<String> ALLOWED_PREFIXES =
      ImmutableSet.of(
          "org/apache/arrow/driver/jdbc/", // Driver code
          "META-INF/maven/", // Maven metadata (useful for security scanner
          "META-INF/services/", // ServiceLoader implementations
          "META-INF/license/",
          "META-INF/licenses/",
          // Prefixes for native libraries
          "META-INF/native/liborg_apache_arrow_driver_jdbc_shaded_",
          "META-INF/native/org_apache_arrow_driver_jdbc_shaded_");

  /** List of allowed files a jar entry may match. */
  public static final Set<String> ALLOWED_FILES =
      ImmutableSet.of(
          "LICENSE.txt",
          "NOTICE.txt",
          "arrow-git.properties",
          "iso3166_1alpha2-codes.properties",
          "iso3166_1alpha3-codes.properties",
          "iso3166_1alpha-2-3-map.properties",
          "iso3166_3-codes.properties",
          "properties/flight.properties",
          "META-INF/io.netty.versions.properties",
          "META-INF/MANIFEST.MF",
          "META-INF/DEPENDENCIES",
          "META-INF/FastDoubleParser-LICENSE",
          "META-INF/FastDoubleParser-NOTICE",
          "META-INF/LICENSE",
          "META-INF/LICENSE.txt",
          "META-INF/NOTICE",
          "META-INF/NOTICE.txt",
          "META-INF/thirdparty-LICENSE",
          "META-INF/bigint-LICENSE");

  // This method is designed to work with Maven failsafe plugin and expects the
  // JDBC driver jar to be present in the test classpath (instead of the individual classes)
  private static File getJdbcJarFile() throws IOException {
    // Check if an override has been set
    if (JDBC_DRIVER_PATH_OVERRIDE != null) {
      return new File(JDBC_DRIVER_PATH_OVERRIDE);
    }

    // Check classpath to find the driver jar (without loading the class)
    URL driverClassURL =
        ITDriverJarValidation.class
            .getClassLoader()
            .getResource("org/apache/arrow/driver/jdbc/ArrowFlightJdbcDriver.class");

    assertNotNull(driverClassURL, "Driver class was not detected in the classpath");
    assertEquals(
        "jar", driverClassURL.getProtocol(), "Driver class was not found inside a jar file");

    // Return the enclosing jar file
    JarURLConnection connection = (JarURLConnection) driverClassURL.openConnection();
    try {
      return new File(connection.getJarFileURL().toURI());
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  /** Validate the content of the jar to enforce all 3rd party dependencies have been shaded. */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  public void validateShadedJar() throws IOException {

    try (JarFile jar = new JarFile(getJdbcJarFile())) {
      Stream<Executable> executables =
          jar.stream()
              .filter(Predicate.not(JarEntry::isDirectory))
              .map(
                  entry -> {
                    return () -> checkEntryAllowed(entry.getName());
                  });

      Assertions.assertAll(executables);
    }
  }

  /** Check that relocated netty code can also load matching native library. */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  public void checkNettyOpenSslNativeLoader() throws Throwable {
    try (URLClassLoader driverClassLoader =
        new URLClassLoader(new URL[] {getJdbcJarFile().toURI().toURL()}, null)) {
      Class<?> openSslClass =
          driverClassLoader.loadClass(
              "org.apache.arrow.driver.jdbc.shaded.io.netty.handler.ssl.OpenSsl");
      Method method = openSslClass.getDeclaredMethod("ensureAvailability");
      try {
        method.invoke(null);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  private static final String DIALECT_SERVICE =
      "META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect";

  /** The built-in dialect must be listed by its real name, not a relocated one. */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  public void dialectServiceFileIsNotRelocated() throws IOException {
    try (JarFile jar = new JarFile(getJdbcJarFile())) {
      final JarEntry entry = jar.getJarEntry(DIALECT_SERVICE);
      assertNotNull(entry, DIALECT_SERVICE + " is missing from the jar");
      final String content =
          new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
      Assertions.assertTrue(
          content.contains("org.apache.arrow.driver.jdbc.dialect.AnsiSqlDialect"), content);
      Assertions.assertFalse(content.contains(".shaded."), content);
    }
  }

  /** Dialect authors compile against the dialect classes, so they must not expose shaded types. */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  public void dialectApiDoesNotExposeShadedTypes() throws Exception {
    try (URLClassLoader driverClassLoader =
        new URLClassLoader(
            new URL[] {getJdbcJarFile().toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
      for (String name :
          List.of(
              "org.apache.arrow.driver.jdbc.dialect.SqlDialect",
              "org.apache.arrow.driver.jdbc.dialect.SqlDialectRegistry",
              "org.apache.arrow.driver.jdbc.dialect.SqlLexicalRules",
              "org.apache.arrow.driver.jdbc.dialect.ScannedSql",
              "org.apache.arrow.driver.jdbc.dialect.value.SqlValue",
              "org.apache.arrow.driver.jdbc.dialect.value.SqlType",
              "org.apache.arrow.driver.jdbc.dialect.value.SqlArray",
              "org.apache.arrow.driver.jdbc.dialect.value.SqlRow")) {
        final Class<?> type = driverClassLoader.loadClass(name);
        for (Method method : type.getDeclaredMethods()) {
          assertNotShaded(method.getReturnType(), method);
          for (Class<?> parameter : method.getParameterTypes()) {
            assertNotShaded(parameter, method);
          }
        }
      }
    }
  }

  private static void assertNotShaded(Class<?> type, Method method) {
    Assertions.assertFalse(
        type.getName().contains(".shaded."), type.getName() + " is exposed by " + method);
  }

  /**
   * Compile a dialect outside the jar, against the jar only, and check the driver discovers it
   * through its service file, as a user's own jar would be.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  public void customDialectOutsideTheJarIsDiscovered(@TempDir Path directory) throws Exception {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "These tests need a JDK");

    final Path source = directory.resolve("src/it/ItDialect.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        "package it;\n"
            + "import org.apache.arrow.driver.jdbc.dialect.SqlDialect;\n"
            + "public class ItDialect implements SqlDialect {\n"
            + "  public String name() { return \"it\"; }\n"
            + "  public String formatBoolean(boolean b) { return b ? \"1\" : \"0\"; }\n"
            + "}\n");
    final Path classes = Files.createDirectories(directory.resolve("classes"));
    final int result =
        compiler.run(
            null,
            null,
            null,
            "-classpath",
            getJdbcJarFile().getAbsolutePath(),
            "-d",
            classes.toString(),
            source.toString());
    assertEquals(0, result, "The dialect did not compile against the driver jar");

    final Path services = Files.createDirectories(classes.resolve("META-INF/services"));
    Files.writeString(
        services.resolve("org.apache.arrow.driver.jdbc.dialect.SqlDialect"), "it.ItDialect\n");

    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(
            new URL[] {getJdbcJarFile().toURI().toURL(), classes.toUri().toURL()},
            // The platform loader sees java.sql but none of the test classpath.
            ClassLoader.getPlatformClassLoader())) {
      thread.setContextClassLoader(loader);
      final Method resolve =
          loader
              .loadClass("org.apache.arrow.driver.jdbc.dialect.SqlDialectRegistry")
              .getMethod("resolve", String.class, String.class);

      final Object custom = resolve.invoke(null, "it", null);
      assertEquals("it.ItDialect", custom.getClass().getName());
      assertEquals(
          "1", custom.getClass().getMethod("formatBoolean", boolean.class).invoke(custom, true));

      final Object ansi = resolve.invoke(null, "ansi", null);
      assertEquals(
          "org.apache.arrow.driver.jdbc.dialect.AnsiSqlDialect", ansi.getClass().getName());
    } finally {
      thread.setContextClassLoader(original);
    }
  }

  /**
   * Check if a jar entry is allowed.
   *
   * <p>A jar entry is allowed if either it is part of the allowed files or it matches one of the
   * allowed prefixes
   *
   * @param name the jar entry name
   * @throws AssertionError if the entry is not allowed
   */
  private void checkEntryAllowed(String name) {
    // Check if there's a matching file entry first
    if (ALLOWED_FILES.contains(name)) {
      return;
    }

    for (String prefix : ALLOWED_PREFIXES) {
      if (name.startsWith(prefix)) {
        return;
      }
    }

    throw new AssertionError("'" + name + "' is not an allowed jar entry");
  }
}
