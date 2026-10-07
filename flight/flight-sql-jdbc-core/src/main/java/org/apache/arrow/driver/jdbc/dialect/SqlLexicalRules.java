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

/**
 * The lexical rules {@link SqlParameterScanner} needs to find parameter markers: which constructs
 * can hide a {@code ?} character.
 *
 * <p>Single quotes delimit strings, double quotes delimit identifiers, and {@code --} and {@code /*
 * *}{@code /} delimit comments in every dialect. A doubled quote is an escaped quote.
 */
public final class SqlLexicalRules {
  private static final SqlLexicalRules ANSI = builder().build();

  private final boolean backslashEscapesInStrings;
  private final boolean dollarQuoting;
  private final boolean nestedBlockComments;
  private final boolean backtickIdentifiers;
  private final boolean bracketIdentifiers;
  private final boolean doubleQuestionMarkEscape;

  private SqlLexicalRules(Builder builder) {
    this.backslashEscapesInStrings = builder.backslashEscapesInStrings;
    this.dollarQuoting = builder.dollarQuoting;
    this.nestedBlockComments = builder.nestedBlockComments;
    this.backtickIdentifiers = builder.backtickIdentifiers;
    this.bracketIdentifiers = builder.bracketIdentifiers;
    this.doubleQuestionMarkEscape = builder.doubleQuestionMarkEscape;
  }

  /** The plain ANSI rules. */
  public static SqlLexicalRules ansi() {
    return ANSI;
  }

  /** A builder starting from the plain ANSI rules. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether a backslash escapes the next character inside a string literal (MySQL). */
  public boolean backslashEscapesInStrings() {
    return backslashEscapesInStrings;
  }

  /** Whether {@code $tag$...$tag$} delimits a string literal (PostgreSQL). */
  public boolean dollarQuoting() {
    return dollarQuoting;
  }

  /** Whether block comments nest (PostgreSQL). */
  public boolean nestedBlockComments() {
    return nestedBlockComments;
  }

  /** Whether backticks delimit identifiers (MySQL). */
  public boolean backtickIdentifiers() {
    return backtickIdentifiers;
  }

  /** Whether square brackets delimit identifiers (SQL Server). */
  public boolean bracketIdentifiers() {
    return bracketIdentifiers;
  }

  /** Whether {@code ??} stands for a literal {@code ?} instead of two parameters. */
  public boolean doubleQuestionMarkEscape() {
    return doubleQuestionMarkEscape;
  }

  /** Builder for {@link SqlLexicalRules}. */
  public static final class Builder {
    private boolean backslashEscapesInStrings;
    private boolean dollarQuoting;
    private boolean nestedBlockComments;
    private boolean backtickIdentifiers;
    private boolean bracketIdentifiers;
    private boolean doubleQuestionMarkEscape;

    private Builder() {}

    public Builder backslashEscapesInStrings(boolean value) {
      this.backslashEscapesInStrings = value;
      return this;
    }

    public Builder dollarQuoting(boolean value) {
      this.dollarQuoting = value;
      return this;
    }

    public Builder nestedBlockComments(boolean value) {
      this.nestedBlockComments = value;
      return this;
    }

    public Builder backtickIdentifiers(boolean value) {
      this.backtickIdentifiers = value;
      return this;
    }

    public Builder bracketIdentifiers(boolean value) {
      this.bracketIdentifiers = value;
      return this;
    }

    public Builder doubleQuestionMarkEscape(boolean value) {
      this.doubleQuestionMarkEscape = value;
      return this;
    }

    public SqlLexicalRules build() {
      return new SqlLexicalRules(this);
    }
  }
}
