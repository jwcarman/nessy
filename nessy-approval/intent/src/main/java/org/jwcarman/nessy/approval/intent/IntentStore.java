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

import java.util.Optional;
import org.jwcarman.nessy.api.memory.Memory;

/**
 * The claim channel's own stash: pre-scoped — no id parameter anywhere. One store answers for
 * exactly one scope; the wiring that builds a scope is the one place that decides which store
 * instance that is.
 *
 * <p>{@link Memory} no longer keeps it company in that: memory became id-keyed, because ONE memory
 * serves every agent of a type. A store here still answers for one agent, which is why {@link
 * JdbcIntentStore} takes the agent TYPE and id at construction rather than per call — an id is
 * unique within its type and no further.
 *
 * @param <T> the declared-intent vocabulary this store holds — the freeform {@code Intent} record,
 *     or an organization's own sealed vocabulary (vocabulary amendment §3)
 */
public interface IntentStore<T> {

  /** Declares {@code declaration} as the latest, last write wins. */
  void declare(T declaration);

  /** The most recently declared value, or empty if none was ever declared. */
  Optional<T> latest();
}
