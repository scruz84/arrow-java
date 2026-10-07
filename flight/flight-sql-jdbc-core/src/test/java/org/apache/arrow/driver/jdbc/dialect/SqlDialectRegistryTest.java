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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests dialect discovery. The dialects below are listed in this module's test {@code
 * META-INF/services/org.apache.arrow.driver.jdbc.dialect.SqlDialect} file.
 */
public class SqlDialectRegistryTest {
  /** A dialect that writes booleans as numbers. */
  public static class ExampleDialect implements SqlDialect {
    @Override
    public String name() {
      return "example";
    }

    @Override
    public String formatBoolean(boolean b) {
      return b ? "1" : "0";
    }
  }

  /** Same name as {@link HighRankedDialect}, lower priority. */
  public static class LowRankedDialect implements SqlDialect {
    @Override
    public String name() {
      return "ranked";
    }

    @Override
    public int priority() {
      return 1;
    }
  }

  /** Same name as {@link LowRankedDialect}, higher priority. */
  public static class HighRankedDialect implements SqlDialect {
    @Override
    public String name() {
      return "Ranked";
    }

    @Override
    public int priority() {
      return 5;
    }
  }

  /** Clashes with {@link ClashBDialect}: same name, same priority. */
  public static class ClashADialect implements SqlDialect {
    @Override
    public String name() {
      return "clash";
    }
  }

  /** Clashes with {@link ClashADialect}. */
  public static class ClashBDialect implements SqlDialect {
    @Override
    public String name() {
      return "clash";
    }
  }

  /** A provider that cannot be created. */
  public static class BrokenDialect implements SqlDialect {
    public BrokenDialect() {
      throw new IllegalStateException("broken on purpose");
    }

    @Override
    public String name() {
      return "broken";
    }
  }

  /** A provider without a usable name. */
  public static class NamelessDialect implements SqlDialect {
    @Override
    public String name() {
      return " ";
    }
  }

  /** Only listed in a service file that a test adds to a class loader. */
  public static class ContextOnlyDialect implements SqlDialect {
    @Override
    public String name() {
      return "contextonly";
    }
  }

  /** Not a dialect. */
  public static class NotADialect {}

  /** A dialect that cannot be constructed from a property. */
  public abstract static class AbstractDialect implements SqlDialect {}

  @Test
  public void testBuiltInDialect() throws SQLException {
    assertInstanceOf(AnsiSqlDialect.class, SqlDialectRegistry.resolve("ansi", null));
    assertInstanceOf(AnsiSqlDialect.class, SqlDialectRegistry.resolve("ANSI", null));
    assertInstanceOf(AnsiSqlDialect.class, SqlDialectRegistry.resolve("ansi", ""));
  }

  @Test
  public void testBuiltInDialectDoesNotNeedDiscovery() throws SQLException {
    assertInstanceOf(AnsiSqlDialect.class, SqlDialectRegistry.select("ansi", List.of()));
  }

  @Test
  public void testDiscoveredDialectByNameIgnoringCase() throws SQLException {
    assertInstanceOf(ExampleDialect.class, SqlDialectRegistry.resolve("example", null));
    assertInstanceOf(ExampleDialect.class, SqlDialectRegistry.resolve("EXAMPLE", null));
  }

  @Test
  public void testHighestPriorityWins() throws SQLException {
    assertInstanceOf(HighRankedDialect.class, SqlDialectRegistry.resolve("ranked", null));
  }

  @Test
  public void testEqualPriorityIsAnError() {
    final SQLException e =
        assertThrows(SQLException.class, () -> SqlDialectRegistry.resolve("clash", null));
    assertTrue(e.getMessage().contains(ClashADialect.class.getName()), e.getMessage());
    assertTrue(e.getMessage().contains(ClashBDialect.class.getName()), e.getMessage());
    assertTrue(e.getMessage().contains("dialectClass"), e.getMessage());
  }

  @Test
  public void testUnknownDialectListsTheAvailableOnes() {
    final SQLException e =
        assertThrows(SQLException.class, () -> SqlDialectRegistry.resolve("nope", null));
    assertTrue(e.getMessage().contains("'nope'"), e.getMessage());
    assertTrue(e.getMessage().contains("ansi"), e.getMessage());
    assertTrue(e.getMessage().contains("example"), e.getMessage());
  }

  @Test
  public void testBrokenAndNamelessProvidersAreSkipped() throws SQLException {
    final List<SqlDialect> discovered = SqlDialectRegistry.discover();
    assertTrue(discovered.stream().noneMatch(d -> d instanceof NamelessDialect));
    assertTrue(discovered.stream().anyMatch(d -> d instanceof ExampleDialect));
    // The ones around the broken provider are still found.
    assertInstanceOf(ExampleDialect.class, SqlDialectRegistry.resolve("example", null));
    assertThrows(SQLException.class, () -> SqlDialectRegistry.resolve("broken", null));
  }

  @Test
  public void testEachProviderIsReturnedOnce() {
    final List<SqlDialect> discovered = SqlDialectRegistry.discover();
    assertEquals(1, discovered.stream().filter(d -> d instanceof AnsiSqlDialect).count());
    assertEquals(1, discovered.stream().filter(d -> d instanceof ExampleDialect).count());
  }

  @Test
  public void testDialectClassTakesPrecedenceOverTheName() throws SQLException {
    assertInstanceOf(
        ExampleDialect.class, SqlDialectRegistry.resolve("ansi", ExampleDialect.class.getName()));
    // The class is used even when discovery would find a clash.
    assertInstanceOf(
        ClashADialect.class, SqlDialectRegistry.resolve("clash", ClashADialect.class.getName()));
  }

  @Test
  public void testBadDialectClass() {
    assertThrows(SQLException.class, () -> SqlDialectRegistry.resolve("ansi", "no.such.Dialect"));
    assertThrows(
        SQLException.class, () -> SqlDialectRegistry.resolve("ansi", NotADialect.class.getName()));
    assertThrows(
        SQLException.class,
        () -> SqlDialectRegistry.resolve("ansi", AbstractDialect.class.getName()));
    assertThrows(
        SQLException.class,
        () -> SqlDialectRegistry.resolve("ansi", BrokenDialect.class.getName()));
  }

  @Test
  public void testServicesOfTheContextClassLoaderAreFound(@TempDir Path directory)
      throws Exception {
    final Path services = Files.createDirectories(directory.resolve("META-INF/services"));
    Files.write(
        services.resolve(SqlDialect.class.getName()),
        (ContextOnlyDialect.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));

    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {directory.toUri().toURL()}, original)) {
      assertThrows(SQLException.class, () -> SqlDialectRegistry.resolve("contextonly", null));
      thread.setContextClassLoader(loader);
      assertInstanceOf(ContextOnlyDialect.class, SqlDialectRegistry.resolve("contextonly", null));
      // Providers of the driver's own class loader are still there.
      assertInstanceOf(ExampleDialect.class, SqlDialectRegistry.resolve("example", null));
    } finally {
      thread.setContextClassLoader(original);
    }
  }
}
