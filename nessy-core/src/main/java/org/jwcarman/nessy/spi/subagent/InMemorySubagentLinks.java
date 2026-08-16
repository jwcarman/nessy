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
package org.jwcarman.nessy.spi.subagent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The in-process {@link SubagentLinks}: one parent {@link ParkToken} per child conversation, kept
 * in a map for the life of the process, last write wins.
 */
final class InMemorySubagentLinks implements SubagentLinks {

  private final Map<ConversationId, ParkToken> links = new ConcurrentHashMap<>();

  @Override
  public Optional<ParkToken> find(ConversationId child) {
    return Optional.ofNullable(links.get(child));
  }

  @Override
  public void save(ConversationId child, ParkToken parentToken) {
    Objects.requireNonNull(child, "child must not be null");
    Objects.requireNonNull(parentToken, "parentToken must not be null");
    links.put(child, parentToken);
  }

  @Override
  public void forget(ConversationId child) {
    Objects.requireNonNull(child, "child must not be null");
    links.remove(child);
  }
}
