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
package org.jwcarman.nessy.api;

/**
 * What a call cost, as the vendor counted it: tokens in, tokens out.
 *
 * <p>Output tokens include any the model spent thinking, which is how every vendor bills them.
 * {@link #unknown()} is for an adapter or a server that did not say -- an OpenAI-compatible server
 * that omits usage, a stream cut short -- and is never confused with zero: a reply that cost
 * nothing is a known zero, and a reply nobody counted is unknown.
 */
public record Usage(long inputTokens, long outputTokens) {

  private static final Usage UNKNOWN = new Usage(-1, -1);

  public Usage {
    boolean unknown = inputTokens < 0 && outputTokens < 0;
    if (!unknown && (inputTokens < 0 || outputTokens < 0)) {
      throw new IllegalArgumentException(
          "token counts are known together or not at all: %d in, %d out"
              .formatted(inputTokens, outputTokens));
    }
  }

  /** Nobody counted. */
  public static Usage unknown() {
    return UNKNOWN;
  }

  public boolean known() {
    return inputTokens >= 0;
  }

  public long totalTokens() {
    return known() ? inputTokens + outputTokens : -1;
  }
}
