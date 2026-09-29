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

import org.jspecify.annotations.Nullable;

/**
 * A preset or a custom provider once every field has been decided: what {@link ProviderCatalogue}
 * resolves, {@link WireProviders} builds from, and the report reads to say what is registered.
 *
 * <p>Public, rather than package-private like its siblings, only because {@code InferenceReport}
 * (in the parent {@code org.jwcarman.nessy.spring.boot} package) reads {@link ResolvedProviders}'
 * list of these; {@link Wire} itself stays package-private, which is why {@link #wireValue()} hands
 * back the property spelling instead of the enum.
 */
public record ResolvedProvider(
    String id, Wire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey) {

  /** The wire's property spelling ({@code chat-completions}, and so on), for a report to print. */
  public String wireValue() {
    return wire.propertyValue();
  }
}
