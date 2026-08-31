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
package org.jwcarman.nessy.api.block;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Something a provider handed us and wants back, which we neither read nor understand.
 *
 * <p>Like the {@code state} parameter in an authorization redirect: opaque by contract. Anthropic
 * signs its thinking so it can trust the block on replay; Gemini attaches a {@code
 * thoughtSignature} to a function call as a continuity token; another vendor will invent a third
 * thing. Modelling any of them as a nessy concept means squeezing every other vendor through a hole
 * cut for the first — which is exactly how we ended up with an adapter that keeps reasoning and
 * another that silently drops it.
 *
 * <p><b>The provider is named because a transcript outlives a model choice.</b> Point a
 * conversation at one vendor today and another tomorrow, and without this an adapter is handed a
 * rival's opaque state and either fails or sends nonsense. An adapter takes its own blocks and
 * ignores every other.
 *
 * @param provider whose state this is — the same value as {@code gen_ai.provider.name}
 * @param data whatever that provider put there; never inspected here
 */
public record ProviderBlock(String provider, JsonNode data)
    implements ExchangeContentBlock, AssistantContentBlock {

  public ProviderBlock {
    Objects.requireNonNull(provider, "provider must not be null");
    if (provider.isBlank()) {
      throw new IllegalArgumentException("provider must not be blank");
    }
    Objects.requireNonNull(data, "data must not be null");
  }
}
