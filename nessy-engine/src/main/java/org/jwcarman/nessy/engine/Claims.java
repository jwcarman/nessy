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

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Content the agent must keep for the duration of a turn and no longer.
 *
 * <p>Tool ARGUMENTS live here, always -- not above some size threshold. Uniformity makes the size
 * of the agent's state independent of what its tools do, and removes a branch and a number to tune.
 * They cannot live in Memory, because the fold WITHHOLDS an assistant message naming tool_use ids
 * until every one has a matching exchange: for exactly the window a call is in flight, Memory is
 * designed not to hand it back.
 *
 * <p><b>The OWNER is the kind, not the key.</b> Every claim for one turn is written under {@code
 * claim/{agentId}/{turnId}}, so ending a turn is "delete that kind" rather than "delete the claims
 * something remembered to write down". That matters for more than tidiness: a claim written just
 * before a crash -- after {@code put}, before the state referencing it was persisted -- is an
 * ORPHAN that no state names. Scoping by kind sweeps it anyway, because it is in the kind. Owning
 * by key would have leaked it until some future sweep noticed.
 *
 * <p>{@code Substrate} has no bulk delete-by-kind door today, so this lists and deletes. The list
 * is already scoped to one turn, so it is a handful of rows, not a scan. If a {@code
 * deleteKind(String)} door is ever added, this class is the only caller that changes -- and on JDBC
 * it collapses to one {@code DELETE ... WHERE kind = ?}.
 */
public final class Claims {

  private final Substrate substrate;

  public Claims(Substrate substrate) {
    this.substrate = substrate;
  }

  /** All of one turn's claims share this kind, which is what makes them deletable together. */
  static String kindOf(String agentId, String turnId) {
    return "claim/" + agentId + "/" + turnId;
  }

  public String put(String agentId, String turnId, byte[] value) {
    String claimId = Identifiers.next();
    substrate.write(kindOf(agentId, turnId), claimId, value, 0L);
    return claimId;
  }

  public Optional<byte[]> get(String agentId, String turnId, String claimId) {
    return substrate.read(kindOf(agentId, turnId), claimId).map(Substrate.Document::payload);
  }

  /** What this turn is holding. Exists for the tests and for a future abandoned-turn sweep. */
  public List<String> keysOf(String agentId, String turnId) {
    return substrate.keys(kindOf(agentId, turnId), 1000);
  }

  /** The turn ended, so its claims end -- including any orphan no state ever referenced. */
  public void deleteTurn(String agentId, String turnId) {
    String kind = kindOf(agentId, turnId);
    for (String claimId : substrate.keys(kind, 1000)) {
      substrate
          .read(kind, claimId)
          .ifPresent(doc -> substrate.delete(kind, claimId, doc.version()));
    }
  }
}
