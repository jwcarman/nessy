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
package org.jwcarman.nessy.spring.boot.inference;

import java.util.Locale;

/**
 * The protocol a provider speaks, named for the protocol rather than a vendor: one wire serves many
 * vendors (xAI speaks chat-completions), and one vendor may eventually speak several. Bound from
 * {@code nessy.providers.<id>.wire}'s property values ({@code chat-completions}, {@code messages},
 * {@code generate-content}) by Boot's relaxed binding, so a typo is a binding error naming the
 * allowed values rather than a provider that silently fails to exist.
 *
 * <p>Package-private and not in the SPI: nothing outside the starter depends on it.
 */
enum Wire {
  CHAT_COMPLETIONS("openai"),
  MESSAGES("anthropic"),
  GENERATE_CONTENT("gcp.gemini");

  private final String defaultVendor;

  Wire(String defaultVendor) {
    this.defaultVendor = defaultVendor;
  }

  String defaultVendor() {
    return defaultVendor;
  }

  /** The property value this wire is spelled as under {@code nessy.providers.<id>.wire}. */
  String propertyValue() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
