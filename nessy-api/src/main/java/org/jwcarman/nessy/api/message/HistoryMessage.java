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
package org.jwcarman.nessy.api.message;

/**
 * A record of something that happened.
 *
 * <p><b>History is what happened; ambient is how things are.</b> That is the whole rule, and the
 * test is one question — did this happen, or is this the current state of something? Someone spoke;
 * calls were made and answered; the assistant answered. Those happened. The notes as they stand
 * today did not happen, they simply are, so {@link AmbientMessage} is not one of these and cannot
 * be remembered.
 *
 * <p>Everything else follows. History is append-only and never rebuilt, because rewriting what
 * happened is lying about it. Ambient content is rebuilt on every single call, because a note from
 * three turns ago is not what the notebook says now — remembering one would freeze a stale snapshot
 * into the record and re-send it forever.
 *
 * <p>A fourth kind of event would join these. A second kind of state would not.
 */
public sealed interface HistoryMessage extends ContextMessage
    permits UserMessage, ExchangeMessage, AnswerMessage {}
