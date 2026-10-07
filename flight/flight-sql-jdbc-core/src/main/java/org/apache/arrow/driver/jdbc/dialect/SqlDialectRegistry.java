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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the {@link SqlDialect} a connection asks for.
 *
 * <p>Dialects are discovered with {@link ServiceLoader}: any jar on the classpath can provide one
 * with a {@code META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect} file. Both the
 * thread context class loader and the driver's own class loader are searched. A provider that
 * cannot be loaded is skipped with a warning, so one broken jar does not hide the others.
 *
 * <p>A dialect can also be named by its class with the {@code dialectClass} property, which does
 * not depend on discovery. That helps where service files are not visible, such as some application
 * servers or repackaged jars.
 */
public final class SqlDialectRegistry {
  private static final Logger LOGGER = LoggerFactory.getLogger(SqlDialectRegistry.class);

  /** Stops a service configuration that keeps failing from looping forever. */
  private static final int MAX_PROVIDER_ERRORS = 100;

  private SqlDialectRegistry() {}

  /**
   * Resolves a dialect.
   *
   * @param name the value of the {@code dialect} property, matched ignoring case.
   * @param className the value of the {@code dialectClass} property, which takes precedence over
   *     the name, or {@code null}.
   * @return the dialect.
   * @throws SQLException if no dialect matches, several dialects match equally well, or the class
   *     cannot be loaded.
   */
  public static SqlDialect resolve(String name, @Nullable String className) throws SQLException {
    if (className != null && !className.isEmpty()) {
      return instantiate(className);
    }
    return select(name, discover());
  }

  /**
   * Picks a dialect by name among candidates: the highest priority wins, and equal priorities are
   * an error because the choice would depend on classpath order.
   */
  static SqlDialect select(String name, List<SqlDialect> candidates) throws SQLException {
    final List<SqlDialect> matches =
        candidates.stream()
            .filter(d -> d.name().equalsIgnoreCase(name))
            .collect(Collectors.toList());
    if (matches.isEmpty()) {
      if (AnsiSqlDialect.NAME.equalsIgnoreCase(name)) {
        // The built-in dialect does not depend on discovery working.
        return new AnsiSqlDialect();
      }
      throw new SQLException(
          String.format(
              "Unknown SQL dialect '%s'. Available dialects: %s",
              name, availableNames(candidates)));
    }
    final int best = matches.stream().mapToInt(SqlDialect::priority).max().getAsInt();
    final List<SqlDialect> top =
        matches.stream().filter(d -> d.priority() == best).collect(Collectors.toList());
    if (top.size() > 1) {
      throw new SQLException(
          String.format(
              "Several SQL dialects are named '%s' with priority %s: %s. Set the dialectClass"
                  + " property to choose one.",
              name,
              best,
              top.stream().map(d -> d.getClass().getName()).collect(Collectors.joining(", "))));
    }
    return top.get(0);
  }

  /**
   * Finds the dialects available through {@link ServiceLoader}. Each provider class is returned
   * once, even if both class loaders see it.
   *
   * @return the dialects, with the built-in ANSI dialect always among them.
   */
  static List<SqlDialect> discover() {
    final Map<String, SqlDialect> byClass = new LinkedHashMap<>();
    final ClassLoader context = Thread.currentThread().getContextClassLoader();
    if (context != null) {
      load(context, byClass);
    }
    load(SqlDialectRegistry.class.getClassLoader(), byClass);
    return new ArrayList<>(byClass.values());
  }

  private static void load(@Nullable ClassLoader loader, Map<String, SqlDialect> byClass) {
    final java.util.Iterator<SqlDialect> providers =
        ServiceLoader.load(SqlDialect.class, loader).iterator();
    int errors = 0;
    while (errors < MAX_PROVIDER_ERRORS) {
      try {
        if (!providers.hasNext()) {
          return;
        }
        final SqlDialect dialect = providers.next();
        final String dialectName = dialect.name();
        if (dialectName == null || dialectName.trim().isEmpty()) {
          LOGGER.warn("Ignoring SQL dialect {}: it has no name", dialect.getClass().getName());
          continue;
        }
        byClass.putIfAbsent(dialect.getClass().getName(), dialect);
      } catch (ServiceConfigurationError | RuntimeException e) {
        errors++;
        LOGGER.warn("Ignoring a SQL dialect that cannot be loaded", e);
      }
    }
  }

  private static String availableNames(List<SqlDialect> candidates) {
    final List<String> names =
        candidates.stream()
            .map(SqlDialect::name)
            .map(n -> n.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(ArrayList::new));
    if (!names.contains(AnsiSqlDialect.NAME)) {
      names.add(AnsiSqlDialect.NAME);
    }
    names.sort(Comparator.naturalOrder());
    return names.stream().distinct().collect(Collectors.joining(", "));
  }

  private static SqlDialect instantiate(String className) throws SQLException {
    try {
      final Class<?> type = Class.forName(className, true, classLoader());
      return type.asSubclass(SqlDialect.class).getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
      throw new SQLException(
          String.format("Cannot create the SQL dialect '%s': %s", className, e), e);
    }
  }

  private static ClassLoader classLoader() {
    final ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context != null ? context : SqlDialectRegistry.class.getClassLoader();
  }
}
