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
package org.jwcarman.nessy.approval.intent;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Puts the agent's declared intent on an approval request, for whoever decides it to read.
 *
 * <p>An {@link ApprovalEnricher}: it never decides. Whether an undeclared call is refused is a
 * policy's judgement ({@link IntentPolicy}); this only makes the declaration available, under a
 * namespaced fact so two modules cannot collide on a key.
 */
public final class IntentEnricher<T> implements ApprovalEnricher {

  public static final String DECLARED = "intent.declared";

  private final IntentStore<T> store;
  private final ObjectMapper mapper;

  public IntentEnricher(IntentStore<T> store, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public void enrich(ApprovalRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    store
        .latest(request.agentId())
        .ifPresent(intent -> request.fact(DECLARED, mapper.valueToTree(intent)));
  }
}
