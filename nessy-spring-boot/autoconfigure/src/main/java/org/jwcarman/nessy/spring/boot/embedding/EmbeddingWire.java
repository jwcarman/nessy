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

import java.util.Locale;

/**
 * The API shape an embedding provider speaks, named for the vendor that defined it: {@code openai}
 * is OpenAI's {@code /v1/embeddings} shape, which local servers and gateways also serve; {@code
 * gemini} and {@code voyage} are those vendors' own. Bound from {@code nessy.embedders.<id>.wire}'s
 * property values by Boot's relaxed binding, so a typo is a binding error naming the allowed values
 * rather than an embedder that silently fails to exist.
 *
 * <p>Its own enum, apart from the inference wire: the property path already says which family is
 * meant, and OpenAI has one embeddings shape, so the value needs no qualifier. Package-private and
 * not in the SPI: nothing outside the starter depends on it.
 */
enum EmbeddingWire {
  OPENAI("openai"),
  GEMINI("gcp.gemini"),
  VOYAGE("voyage");

  private final String defaultVendor;

  EmbeddingWire(String defaultVendor) {
    this.defaultVendor = defaultVendor;
  }

  String defaultVendor() {
    return defaultVendor;
  }

  /** The property value this wire is spelled as under {@code nessy.embedders.<id>.wire}. */
  String propertyValue() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
