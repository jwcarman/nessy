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
package org.jwcarman.nessy.api.conversation;

/** Where a session is in its lifecycle. */
public enum ConversationStatus {

  /** Nothing has happened yet, or the last turn finished and we are waiting on a human. */
  IDLE,

  /** A model call is in flight. */
  AWAITING_MODEL,

  /** A tool call needs an approval decision before it can run. */
  AWAITING_APPROVAL,

  /** An approved tool is running. */
  EXECUTING_TOOL,

  /** A summarization call is in flight. */
  COMPACTING,

  /** The model ended its turn with nothing left to do. */
  COMPLETE,

  /** Too many consecutive tool errors. The loop gave up rather than burn tokens. */
  FAILED
}
