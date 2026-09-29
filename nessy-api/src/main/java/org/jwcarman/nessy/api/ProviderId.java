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
 * The name an application gives one of its inference providers: {@code openai}, {@code xai}, {@code
 * openai-batch}.
 *
 * <p>Ours, not the vendor's. Two providers can speak to the same vendor -- two OpenAI keys with
 * different quotas -- and report the same vendor to a trace, but each has its own id, and an agent
 * type names the one it wants by it.
 */
public record ProviderId(String value) {

  private static final int MAX_LENGTH = 64;

  public ProviderId {
    if (value.contains(" ")) {
      throw new IllegalArgumentException("provider id must not contain spaces");
    }
    value = Identifiers.require(value, "provider id", MAX_LENGTH);
  }

  public static ProviderId of(String value) {
    return new ProviderId(value);
  }
}
