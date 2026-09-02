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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * Reads the {@link IntentStore} this enricher was built over and records its latest declaration as
 * a fact on the request — the claim a policy may read back. Absent a declaration, the request
 * passes through untouched: a missing claim is not this enricher's failure to report, only a
 * policy's own choice to weigh.
 *
 * <p><b>Gather and judge stay separate</b>, which is the whole point of a distinct type here even
 * though {@link ApprovalRequest#fact(String, JsonNode)} would let any approver do both. An enricher
 * never denies; it only makes a fact available, so several of them can run in any order before
 * anything decides.
 *
 * <p>Facts are addressed by NAME rather than by a typed key, so this class and {@link IntentPolicy}
 * agree by convention rather than by construction. {@link #DECLARED} is that convention, and both
 * sides read it from here rather than spelling the string twice.
 *
 * @param <T> the declared-intent vocabulary
 */
public final class IntentEnricher<T> {

  /**
   * The fact name a declaration lives under. Namespaced, as {@link ApprovalRequest} asks: this
   * module is not the application's own code, and two modules annotating one question must not
   * collide.
   */
  public static final String DECLARED = "intent.declared";

  private final IntentStore<T> store;
  private final ObjectMapper mapper;

  public IntentEnricher(IntentStore<T> store, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /** Records the latest declaration on {@code request}, or leaves it untouched if there is none. */
  public ApprovalRequest enrich(ApprovalRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    Optional<T> declared = store.latest();
    declared.ifPresent(intent -> request.fact(DECLARED, mapper.valueToTree(intent)));
    return request;
  }
}
