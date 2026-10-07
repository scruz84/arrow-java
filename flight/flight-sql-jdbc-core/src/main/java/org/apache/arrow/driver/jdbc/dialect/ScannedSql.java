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

import java.util.List;
import java.util.Objects;

/**
 * A SQL statement split at its parameter markers.
 *
 * @param segments the text around the markers. There is always one more segment than there are
 *     markers.
 */
public record ScannedSql(List<String> segments) {
  /** Creates a scanned statement. */
  public ScannedSql {
    segments = List.copyOf(Objects.requireNonNull(segments));
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("A scanned statement has at least one segment");
    }
  }

  /** The number of parameter markers. */
  public int parameterCount() {
    return segments.size() - 1;
  }

  /**
   * Rebuilds the statement with the given text in place of each marker.
   *
   * @param replacements one replacement per marker.
   * @return the statement text.
   */
  public String join(List<String> replacements) {
    if (replacements.size() != parameterCount()) {
      throw new IllegalArgumentException(
          String.format(
              "Statement has %s parameters, but %s values were given",
              parameterCount(), replacements.size()));
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < replacements.size(); i++) {
      sb.append(segments.get(i)).append(replacements.get(i));
    }
    return sb.append(segments.get(segments.size() - 1)).toString();
  }
}
