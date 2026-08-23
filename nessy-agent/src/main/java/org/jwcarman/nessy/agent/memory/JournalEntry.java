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
package org.jwcarman.nessy.agent.memory;

import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * One {@link SubstrateMemory} journal entry, read back either shape (remembrance spec §6, wire
 * compatibility): {@link Fresh} is what this reform ever writes; {@link Legacy} is a bare {@link
 * Message}, exactly the shape a pre-reform {@code SubstrateMemory} appended, read-only — nothing
 * here ever re-encodes one.
 */
sealed interface JournalEntry {

  record Fresh(Remembrance remembrance) implements JournalEntry {}

  record Legacy(Message message) implements JournalEntry {}
}
