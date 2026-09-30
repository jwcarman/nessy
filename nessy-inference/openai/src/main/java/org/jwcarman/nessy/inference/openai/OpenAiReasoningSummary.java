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
package org.jwcarman.nessy.inference.openai;

/**
 * How much of its reasoning a model summarises, as the value of {@code openai.reasoning.summary}.
 * Only the openai-responses wire carries it.
 */
public enum OpenAiReasoningSummary {
  AUTO("auto"),
  CONCISE("concise"),
  DETAILED("detailed");

  private final String spelling;

  OpenAiReasoningSummary(String spelling) {
    this.spelling = spelling;
  }

  /** The text the vendor spells this value as, and the text a property carries. */
  @Override
  public String toString() {
    return spelling;
  }
}
