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
package org.jwcarman.nessy.spring.boot.embedding;

import org.springframework.util.StringUtils;

/** What the three vendors' auto-configurations agree on about a configured model name. */
final class EmbeddingModels {

  private EmbeddingModels() {}

  /** A model named as blank is a model nobody named: the vendor's own default stands. */
  static String modelOr(String configured, String fallback) {
    return StringUtils.hasText(configured) ? configured : fallback;
  }
}
