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
package org.jwcarman.nessy.engine.store;

import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * One history row, decoded, with the token estimate that was written beside it.
 *
 * <p>Read by {@link Turns} on its way to building turns and never seen outside this package: what
 * leaves here is a {@link Turn}, and what a row looked like is nobody else's business.
 */
record Stored(HistoryEntry message, int tokens) {}
