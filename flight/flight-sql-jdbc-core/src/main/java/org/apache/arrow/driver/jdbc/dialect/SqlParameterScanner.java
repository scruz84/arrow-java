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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the {@code ?} parameter markers of a SQL statement, ignoring the ones inside string
 * literals, quoted identifiers and comments.
 */
public final class SqlParameterScanner {
  private SqlParameterScanner() {}

  /**
   * Splits a statement at its parameter markers.
   *
   * <p>An unterminated literal or comment extends to the end of the statement, so it never hides an
   * error from the server.
   *
   * @param sql the statement.
   * @param rules the lexical rules of the dialect.
   * @return the statement split at its markers.
   */
  public static ScannedSql scan(String sql, SqlLexicalRules rules) {
    final List<String> segments = new ArrayList<>();
    final StringBuilder current = new StringBuilder(sql.length());
    final int n = sql.length();
    int i = 0;
    while (i < n) {
      final char c = sql.charAt(i);
      switch (c) {
        case '\'':
          i = copyQuoted(sql, i, '\'', rules.backslashEscapesInStrings(), current);
          break;
        case '"':
          i = copyQuoted(sql, i, '"', false, current);
          break;
        case '`':
          if (rules.backtickIdentifiers()) {
            i = copyQuoted(sql, i, '`', false, current);
          } else {
            current.append(c);
            i++;
          }
          break;
        case '[':
          if (rules.bracketIdentifiers()) {
            i = copyQuoted(sql, i, ']', false, current);
          } else {
            current.append(c);
            i++;
          }
          break;
        case '-':
          if (i + 1 < n && sql.charAt(i + 1) == '-') {
            i = copyLineComment(sql, i, current);
          } else {
            current.append(c);
            i++;
          }
          break;
        case '/':
          if (i + 1 < n && sql.charAt(i + 1) == '*') {
            i = copyBlockComment(sql, i, rules.nestedBlockComments(), current);
          } else {
            current.append(c);
            i++;
          }
          break;
        case '$':
          i = rules.dollarQuoting() ? copyDollarQuoted(sql, i, current) : append(c, i, current);
          break;
        case '?':
          if (rules.doubleQuestionMarkEscape() && i + 1 < n && sql.charAt(i + 1) == '?') {
            current.append('?');
            i += 2;
          } else {
            segments.add(current.toString());
            current.setLength(0);
            i++;
          }
          break;
        default:
          current.append(c);
          i++;
      }
    }
    segments.add(current.toString());
    return new ScannedSql(segments);
  }

  /**
   * Gets the first keyword of a statement under the plain ANSI rules.
   *
   * @param sql the statement.
   * @return the keyword, or an empty string when the statement does not start with one.
   * @see #leadingKeyword(String, SqlLexicalRules)
   */
  public static String leadingKeyword(String sql) {
    return leadingKeyword(sql, SqlLexicalRules.ansi());
  }

  /**
   * Gets the first keyword of a statement, upper-cased, skipping leading whitespace, comments and
   * opening parentheses.
   *
   * @param sql the statement.
   * @param rules the lexical rules of the dialect, which tell whether block comments nest.
   * @return the keyword, or an empty string when the statement does not start with one.
   */
  public static String leadingKeyword(String sql, SqlLexicalRules rules) {
    final int n = sql.length();
    int i = 0;
    while (i < n) {
      final char c = sql.charAt(i);
      if (Character.isWhitespace(c) || c == '(') {
        i++;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        i = copyLineComment(sql, i, new StringBuilder());
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        i = copyBlockComment(sql, i, rules.nestedBlockComments(), new StringBuilder());
      } else {
        break;
      }
    }
    final int start = i;
    while (i < n && Character.isLetter(sql.charAt(i))) {
      i++;
    }
    return sql.substring(start, i).toUpperCase(Locale.ROOT);
  }

  private static int append(char c, int i, StringBuilder out) {
    out.append(c);
    return i + 1;
  }

  /**
   * Copies a quoted section; {@code i} is at the opening delimiter and {@code quote} is the closing
   * one. Returns the next index.
   */
  private static int copyQuoted(
      String sql, int i, char quote, boolean backslash, StringBuilder out) {
    final int n = sql.length();
    out.append(sql.charAt(i));
    i++;
    while (i < n) {
      final char c = sql.charAt(i);
      out.append(c);
      i++;
      if (backslash && c == '\\' && i < n) {
        out.append(sql.charAt(i));
        i++;
      } else if (c == quote) {
        if (i < n && sql.charAt(i) == quote) {
          // A doubled closing delimiter is an escaped delimiter.
          out.append(quote);
          i++;
        } else {
          return i;
        }
      }
    }
    return n;
  }

  private static int copyLineComment(String sql, int i, StringBuilder out) {
    final int n = sql.length();
    while (i < n) {
      final char c = sql.charAt(i);
      out.append(c);
      i++;
      if (c == '\n' || c == '\r') {
        break;
      }
    }
    return i;
  }

  private static int copyBlockComment(String sql, int i, boolean nested, StringBuilder out) {
    final int n = sql.length();
    int depth = 1;
    out.append("/*");
    i += 2;
    while (i < n) {
      if (nested && sql.startsWith("/*", i)) {
        depth++;
        out.append("/*");
        i += 2;
      } else if (sql.startsWith("*/", i)) {
        depth--;
        out.append("*/");
        i += 2;
        if (depth == 0) {
          return i;
        }
      } else {
        out.append(sql.charAt(i));
        i++;
      }
    }
    return n;
  }

  /**
   * Copies a {@code $tag$...$tag$} section. If the {@code $} at {@code i} does not start one (a
   * positional parameter such as {@code $1}, or the middle of an identifier), copies just that
   * character.
   */
  private static int copyDollarQuoted(String sql, int i, StringBuilder out) {
    final int n = sql.length();
    if (i > 0 && isIdentifierPart(sql.charAt(i - 1))) {
      out.append('$');
      return i + 1;
    }
    int j = i + 1;
    while (j < n
        && (Character.isLetter(sql.charAt(j))
            || sql.charAt(j) == '_'
            || (j > i + 1 && Character.isDigit(sql.charAt(j))))) {
      j++;
    }
    if (j >= n || sql.charAt(j) != '$') {
      out.append('$');
      return i + 1;
    }
    final String delimiter = sql.substring(i, j + 1);
    final int end = sql.indexOf(delimiter, j + 1);
    final int next = end < 0 ? n : end + delimiter.length();
    out.append(sql, i, next);
    return next;
  }

  private static boolean isIdentifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}
