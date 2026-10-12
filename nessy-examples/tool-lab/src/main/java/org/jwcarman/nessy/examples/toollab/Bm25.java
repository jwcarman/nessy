/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.examples.toollab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BM25 over a catalog. A tool's text is its name split on underscores plus its description,
 * lowercased and split on anything that is not a letter or digit. There is no stemming.
 */
final class Bm25 {

  private static final double K1 = 1.2;
  private static final double B = 0.75;

  private final List<CatalogTool> tools;
  private final List<Map<String, Integer>> frequencies = new ArrayList<>();
  private final List<Integer> lengths = new ArrayList<>();
  private final Map<String, Integer> documentFrequency = new HashMap<>();
  private final double averageLength;

  Bm25(List<CatalogTool> tools) {
    this.tools = List.copyOf(tools);
    long total = 0;
    for (CatalogTool tool : this.tools) {
      List<String> words = tokens(tool.name() + " " + tool.description());
      Map<String, Integer> counts = new HashMap<>();
      for (String word : words) {
        counts.merge(word, 1, Integer::sum);
      }
      for (String word : counts.keySet()) {
        documentFrequency.merge(word, 1, Integer::sum);
      }
      frequencies.add(counts);
      lengths.add(words.size());
      total += words.size();
    }
    this.averageLength = this.tools.isEmpty() ? 0 : (double) total / this.tools.size();
  }

  /**
   * The best {@code limit} tools for {@code query}, best first; tools that match nothing are left
   * out.
   */
  List<CatalogTool> search(String query, int limit) {
    Set<String> terms = new LinkedHashSet<>(tokens(query));
    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < tools.size(); i++) {
      double score = score(i, terms);
      if (score > 0) {
        scored.add(new Scored(i, score));
      }
    }
    scored.sort(
        Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
    return scored.stream().limit(limit).map(s -> tools.get(s.index())).toList();
  }

  private double score(int index, Set<String> terms) {
    Map<String, Integer> counts = frequencies.get(index);
    double score = 0;
    for (String term : terms) {
      Integer count = counts.get(term);
      if (count == null) {
        continue;
      }
      double n = documentFrequency.get(term);
      double idf = Math.log(1 + (tools.size() - n + 0.5) / (n + 0.5));
      double tf = count;
      double norm = 1 - B + B * lengths.get(index) / averageLength;
      score += idf * tf * (K1 + 1) / (tf + K1 * norm);
    }
    return score;
  }

  static List<String> tokens(String text) {
    List<String> words = new ArrayList<>();
    for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (!word.isEmpty()) {
        words.add(word);
      }
    }
    return words;
  }

  private record Scored(int index, double score) {}
}
