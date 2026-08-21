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
package org.jwcarman.nessy.spi.intent;

import java.util.Optional;
import org.jwcarman.nessy.api.intent.Intent;
import org.jwcarman.nessy.spi.Memory;

/**
 * The claim channel's own stash: pre-scoped, like {@link Memory} — no id parameter anywhere
 * (agent-as-scope §3.5). One store answers for exactly one scope; the wiring that builds a scope is
 * the one place that decides which store instance that is.
 */
public interface IntentStore {

  /** Records {@code intent} as the latest declaration, last write wins. */
  void record(Intent intent);

  /** The most recently recorded declaration, or empty if none was ever declared. */
  Optional<Intent> latest();
}
