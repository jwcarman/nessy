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
package org.jwcarman.nessy.intent;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Key;

/**
 * Reads the {@link IntentStore} this enricher was built over and deposits its latest declaration
 * under {@link #declared(Class)} — the claim a rule may read back. Absent a declaration, the draft
 * passes through untouched: a missing claim is not this enricher's failure to report, only a rule's
 * own choice to weigh.
 *
 * <p>Typed, now that facts are (approval-lifecycle spec §1.2): the key names {@code vocabulary}
 * concretely, so the deposit renders through the pinned mapper and the read decodes back to it.
 *
 * @param <T> the declared-intent vocabulary
 */
public final class IntentEnricher<T> implements Enricher {

  private final IntentStore<T> store;
  private final Class<T> vocabulary;

  public IntentEnricher(IntentStore<T> store, Class<T> vocabulary) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
  }

  /**
   * The key a declaration of {@code vocabulary} lives under — value-equal wherever it is built, so
   * an enricher in one module and a rule in another address the same fact by construction.
   *
   * @param vocabulary the declared-intent vocabulary
   * @param <T> the declared-intent vocabulary
   * @return the key
   */
  public static <T> Key<T> declared(Class<T> vocabulary) {
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    return new Key<>(vocabulary, "intent.declared");
  }

  @Override
  public void enrich(ApprovalRequest.Draft draft) {
    Optional<T> declared = store.latest();
    declared.ifPresent(intent -> draft.deposit(declared(vocabulary), intent));
  }

  @Override
  public Optional<String> displayName() {
    return Optional.of("intent");
  }
}
