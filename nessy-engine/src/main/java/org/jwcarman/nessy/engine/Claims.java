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
package org.jwcarman.nessy.engine;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Content a turn must keep for its own duration and no longer.
 *
 * <p>What a tool was asked and what it answered are CONTENT — the size of whatever the tool decided
 * to hand back. Keeping them in a turn's document would make that document grow with what its tools
 * do, which is the one thing the document's shape is for. They cannot live in the transcript
 * either: an exchange is written whole, so for exactly the window a call is in flight the
 * transcript is designed not to hold it.
 *
 * <p><b>The OWNER is the kind, not the key.</b> Everything for one turn lives under {@code
 * claim/{agentId}/{turnId}}, so ending a turn is "delete that kind" rather than "delete the claims
 * something remembered to write down". That matters for more than tidiness: a claim written just
 * before a crash, before the state naming it was persisted, is an ORPHAN no key list contains.
 * Scoping by kind sweeps it anyway, because it is in the kind.
 */
final class Claims {

  private final Substrate substrate;

  Claims(Substrate substrate) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
  }

  static String kindOf(AgentId agentId, String turnId) {
    return "claim/" + agentId.value() + "/" + turnId;
  }

  /**
   * Writes, overwriting whatever was there.
   *
   * <p>Overwriting matters: a turn that re-drives after a crash claims the same keys again, and a
   * write that insisted the key was new would turn an ordinary recovery into a dead actor. Reading
   * the version first is safe because a turn is the only writer for its own kind.
   */
  void put(AgentId agentId, String turnId, String key, byte[] value) {
    String kind = kindOf(agentId, turnId);
    long version = substrate.read(kind, key).map(Substrate.Document::version).orElse(0L);
    substrate.write(kind, key, value, version);
  }

  Optional<byte[]> get(AgentId agentId, String turnId, String key) {
    return substrate.read(kindOf(agentId, turnId), key).map(Substrate.Document::payload);
  }

  /** The turn ended, so its claims end — including any orphan no state ever referenced. */
  void deleteTurn(AgentId agentId, String turnId) {
    substrate.deleteKind(kindOf(agentId, turnId));
  }
}
