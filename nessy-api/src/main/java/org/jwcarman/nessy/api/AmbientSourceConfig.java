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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessy.api.block.Block;

/**
 * What a source is made of; see {@link AmbientSource#of(java.util.function.Consumer)}.
 *
 * <p>A kind and a function, which is the whole of a source small enough not to want a class. What
 * it offers is said one way or the other: as an {@link Ambient}, or as the text of one.
 */
public final class AmbientSourceConfig {

  private String kind;
  private Function<AgentId, Optional<Ambient>> offering;

  AmbientSourceConfig() {}

  /** The section label this source contributes under. Required. */
  public AmbientSourceConfig kind(String kind) {
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    return this;
  }

  /** What it has to say about one agent, or nothing. */
  public AmbientSourceConfig offering(Function<AgentId, Optional<Ambient>> offering) {
    this.offering = Objects.requireNonNull(offering, "offering must not be null");
    return this;
  }

  /** The same, for a source whose background is a paragraph of text. */
  public AmbientSourceConfig text(Function<AgentId, Optional<String>> text) {
    Objects.requireNonNull(text, "text must not be null");
    return offering(agentId -> text.apply(agentId).map(said -> Ambient.text(requiredKind(), said)));
  }

  /** The same again, for one that says the same thing about every agent, every turn. */
  public AmbientSourceConfig saying(List<Block.AmbientContent> content) {
    Objects.requireNonNull(content, "content must not be null");
    return offering(_ -> Optional.of(new Ambient(requiredKind(), content)));
  }

  String requiredKind() {
    return Objects.requireNonNull(
        kind, "a source needs a kind: it is the label it contributes under");
  }

  Function<AgentId, Optional<Ambient>> requiredOffering() {
    return Objects.requireNonNull(
        offering, "a source needs something to offer: offering(...), text(...) or saying(...)");
  }
}
