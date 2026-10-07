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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

public class SqlParameterScannerTest {
  private static final SqlLexicalRules ANSI = SqlLexicalRules.ansi();

  private static List<String> segments(String sql, SqlLexicalRules rules) {
    return SqlParameterScanner.scan(sql, rules).segments();
  }

  @Test
  public void testNoParameters() {
    final ScannedSql scanned = SqlParameterScanner.scan("select 1", ANSI);
    assertEquals(0, scanned.parameterCount());
    assertEquals(List.of("select 1"), scanned.segments());
  }

  @Test
  public void testEmptyStatement() {
    assertEquals(List.of(""), segments("", ANSI));
  }

  @Test
  public void testParameters() {
    final ScannedSql scanned = SqlParameterScanner.scan("select ?, ? from t where a = ?", ANSI);
    assertEquals(3, scanned.parameterCount());
    assertEquals(List.of("select ", ", ", " from t where a = ", ""), scanned.segments());
  }

  @Test
  public void testMarkerInStringLiteralIsIgnored() {
    assertEquals(List.of("select '?' , ", ""), segments("select '?' , ?", ANSI));
  }

  @Test
  public void testDoubledQuoteStaysInsideString() {
    assertEquals(List.of("select 'it''s ?', ", ""), segments("select 'it''s ?', ?", ANSI));
  }

  @Test
  public void testQuotedIdentifierIsIgnored() {
    assertEquals(
        List.of("select \"a?\" from t where b=", ""),
        segments("select \"a?\" from t where b=?", ANSI));
  }

  @Test
  public void testLineCommentIsIgnored() {
    assertEquals(List.of("select 1 -- what? \n, ", ""), segments("select 1 -- what? \n, ?", ANSI));
  }

  @Test
  public void testBlockCommentIsIgnored() {
    assertEquals(List.of("select /* ? */ 1, ", ""), segments("select /* ? */ 1, ?", ANSI));
  }

  @Test
  public void testBlockCommentsDoNotNestByDefault() {
    // The comment ends at the first terminator, so the last marker is a real one.
    assertEquals(List.of("/* /* ? */ ", ""), segments("/* /* ? */ ?", ANSI));
  }

  @Test
  public void testNestedBlockComments() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().nestedBlockComments(true).build();
    assertEquals(List.of("/* /* ? */ ? */ ", ""), segments("/* /* ? */ ? */ ?", rules));
  }

  @Test
  public void testMinusIsNotAComment() {
    assertEquals(List.of("select 1 - ", ""), segments("select 1 - ?", ANSI));
  }

  @Test
  public void testBackslashIsNotAnEscapeByDefault() {
    // The string is 'a\' and the marker after it is real.
    assertEquals(List.of("select 'a\\' , ", ""), segments("select 'a\\' , ?", ANSI));
  }

  @Test
  public void testBackslashEscape() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().backslashEscapesInStrings(true).build();
    assertEquals(List.of("select 'a\\'?' , ", ""), segments("select 'a\\'?' , ?", rules));
  }

  @Test
  public void testBacktickIdentifiers() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().backtickIdentifiers(true).build();
    assertEquals(List.of("select `a?` , ", ""), segments("select `a?` , ?", rules));
    // Without the rule a backtick is an ordinary character.
    assertEquals(List.of("select `a", "` , ", ""), segments("select `a?` , ?", ANSI));
  }

  @Test
  public void testBracketIdentifiers() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().bracketIdentifiers(true).build();
    assertEquals(List.of("select [a?]]b] , ", ""), segments("select [a?]]b] , ?", rules));
    assertEquals(List.of("select a[", "] , ", ""), segments("select a[?] , ?", ANSI));
  }

  @Test
  public void testDollarQuoting() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().dollarQuoting(true).build();
    assertEquals(List.of("select $$a?$$, ", ""), segments("select $$a?$$, ?", rules));
    assertEquals(
        List.of("select $t$a ? $$ ? $t$, ", ""), segments("select $t$a ? $$ ? $t$, ?", rules));
  }

  @Test
  public void testDollarSignThatDoesNotStartAQuote() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().dollarQuoting(true).build();
    // A positional parameter and an identifier containing a dollar sign.
    assertEquals(List.of("select $1, a$b, ", ""), segments("select $1, a$b, ?", rules));
    // Without the rule nothing is special.
    assertEquals(List.of("select $$a", "$$, ", ""), segments("select $$a?$$, ?", ANSI));
  }

  @Test
  public void testDoubleQuestionMarkEscape() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().doubleQuestionMarkEscape(true).build();
    assertEquals(List.of("select a ? b, ", ""), segments("select a ?? b, ?", rules));
    // Without the rule both question marks are parameters.
    assertEquals(List.of("select a ", "", ", ", ""), segments("select a ??, ?", ANSI));
  }

  @Test
  public void testUnterminatedLiteralHidesTheRest() {
    assertEquals(List.of("select 'abc ? "), segments("select 'abc ? ", ANSI));
    assertEquals(List.of("select /* abc ? "), segments("select /* abc ? ", ANSI));
  }

  @Test
  public void testJoin() {
    final ScannedSql scanned = SqlParameterScanner.scan("select ?, ?", ANSI);
    assertEquals("select 1, 'a'", scanned.join(List.of("1", "'a'")));
    assertThrows(IllegalArgumentException.class, () -> scanned.join(List.of("1")));
  }

  @Test
  public void testLeadingKeyword() {
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("select 1"));
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("  \n\t select 1"));
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("-- note\n/* more */ (select 1)"));
    assertEquals("INSERT", SqlParameterScanner.leadingKeyword("insert into t values (?)"));
    assertEquals("", SqlParameterScanner.leadingKeyword("  "));
    assertEquals("", SqlParameterScanner.leadingKeyword("123"));
  }

  @Test
  public void testMarkerAtTheStartAndTheEnd() {
    assertEquals(List.of("", ""), segments("?", ANSI));
    assertEquals(List.of("", " = ", ""), segments("? = ?", ANSI));
  }

  @Test
  public void testDoubledQuoteStaysInsideIdentifier() {
    assertEquals(
        List.of("select \"a\"\"?\" from t where b=", ""),
        segments("select \"a\"\"?\" from t where b=?", ANSI));
  }

  @Test
  public void testLineCommentEndsAtCarriageReturn() {
    assertEquals(List.of("select 1 -- what? \r, ", ""), segments("select 1 -- what? \r, ?", ANSI));
    assertEquals(
        List.of("select 1 -- what? \r\n, ", ""), segments("select 1 -- what? \r\n, ?", ANSI));
  }

  @Test
  public void testLineCommentAtTheEndOfTheStatement() {
    assertEquals(List.of("select ", " -- what?"), segments("select ? -- what?", ANSI));
  }

  @Test
  public void testQuoteInsideCommentDoesNotStartALiteral() {
    assertEquals(List.of("select /* it's */ ", ""), segments("select /* it's */ ?", ANSI));
    assertEquals(List.of("select 1 -- it's\n, ", ""), segments("select 1 -- it's\n, ?", ANSI));
  }

  @Test
  public void testCommentInsideLiteralDoesNotStartAComment() {
    assertEquals(List.of("select '--', ", ""), segments("select '--', ?", ANSI));
    assertEquals(List.of("select '/*', ", " /* ? */"), segments("select '/*', ? /* ? */", ANSI));
  }

  @Test
  public void testSlashIsNotAComment() {
    assertEquals(List.of("select 1 / ", ""), segments("select 1 / ?", ANSI));
  }

  @Test
  public void testUnterminatedNestedBlockCommentHidesTheRest() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().nestedBlockComments(true).build();
    assertEquals(List.of("/* /* ? */ ? "), segments("/* /* ? */ ? ", rules));
  }

  @Test
  public void testUnterminatedIdentifiersHideTheRest() {
    assertEquals(List.of("select \"abc ? "), segments("select \"abc ? ", ANSI));
    assertEquals(
        List.of("select `abc ? "),
        segments("select `abc ? ", SqlLexicalRules.builder().backtickIdentifiers(true).build()));
    assertEquals(
        List.of("select [abc ? "),
        segments("select [abc ? ", SqlLexicalRules.builder().bracketIdentifiers(true).build()));
  }

  @Test
  public void testEscapedBackslashDoesNotEscapeTheQuote() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().backslashEscapesInStrings(true).build();
    // The string is 'a\\' and the marker after it is real.
    assertEquals(List.of("select 'a\\\\' , ", ""), segments("select 'a\\\\' , ?", rules));
  }

  @Test
  public void testBackslashDoesNotEscapeInIdentifiers() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().backslashEscapesInStrings(true).build();
    assertEquals(List.of("select \"a\\\" , ", ""), segments("select \"a\\\" , ?", rules));
  }

  @Test
  public void testDollarQuotingTags() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().dollarQuoting(true).build();
    assertEquals(List.of("select $a1_b$?$a1_b$, ", ""), segments("select $a1_b$?$a1_b$, ?", rules));
    // A tag cannot start with a digit, so these are positional parameters.
    assertEquals(List.of("select $1$", "$1$"), segments("select $1$?$1$", rules));
  }

  @Test
  public void testUnterminatedDollarQuoteHidesTheRest() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().dollarQuoting(true).build();
    assertEquals(List.of("select $$abc ? "), segments("select $$abc ? ", rules));
    assertEquals(List.of("select $t$abc ? $$ "), segments("select $t$abc ? $$ ", rules));
  }

  @Test
  public void testDoubleQuestionMarkEscapeFollowedByAMarker() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().doubleQuestionMarkEscape(true).build();
    assertEquals(List.of("select a ?", ""), segments("select a ???", rules));
    assertEquals(List.of("select a ??"), segments("select a ????", rules));
  }

  @Test
  public void testJoinWithoutParameters() {
    final ScannedSql scanned = SqlParameterScanner.scan("select '?'", ANSI);
    assertEquals("select '?'", scanned.join(List.of()));
    assertThrows(IllegalArgumentException.class, () -> scanned.join(List.of("1")));
  }

  @Test
  public void testScannedSqlNeedsASegment() {
    assertThrows(IllegalArgumentException.class, () -> new ScannedSql(List.of()));
    assertThrows(NullPointerException.class, () -> new ScannedSql(null));
  }

  @Test
  public void testLeadingKeywordStopsAtTheFirstWord() {
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("select"));
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("select(1)"));
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("((select 1))"));
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword("-- one\r\n-- two\nselect 1"));
    assertEquals("", SqlParameterScanner.leadingKeyword(""));
    assertEquals("", SqlParameterScanner.leadingKeyword("-- only a comment"));
    assertEquals("", SqlParameterScanner.leadingKeyword("/* only a comment"));
    assertEquals("", SqlParameterScanner.leadingKeyword("? = call f()"));
  }

  @Test
  public void testLeadingKeywordAfterNestedBlockComments() {
    final SqlLexicalRules rules = SqlLexicalRules.builder().nestedBlockComments(true).build();
    final String sql = "/* a /* b */ insert */ select 1";
    assertEquals("SELECT", SqlParameterScanner.leadingKeyword(sql, rules));
    // Without the rule the comment ends at the first terminator.
    assertEquals("INSERT", SqlParameterScanner.leadingKeyword(sql, ANSI));
    assertEquals("INSERT", SqlParameterScanner.leadingKeyword(sql));
    assertEquals("", SqlParameterScanner.leadingKeyword("/* a /* b */ select 1", rules));
  }
}
