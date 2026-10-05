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
package org.jwcarman.nessy.api.tool;

/**
 * What became of a late answer.
 *
 * <p>Returned rather than thrown, because the caller is almost always serving a request from
 * somewhere -- a webhook, a queue consumer, a page -- and each arm maps to a different thing to
 * tell whoever is on the other end. A stale link is an ordinary Tuesday, not an exception.
 *
 * <p><b>Neither is a quiet no-op</b>, and that is the point of the type. A dropped answer strands
 * two parties at once: the agent, which waits out a deadline for something already decided, and the
 * person who answered and reasonably believes they are done.
 */
public sealed interface ReplyOutcome {

  /** The answer changed the agent's state: the agent has been told, and the call is settled. */
  record Applied() implements ReplyOutcome {}

  /**
   * The answer changed nothing.
   *
   * <p>An answer that arrives at or after its call's deadline is ignored, even when the engine has
   * not yet recorded the expiry. Deliberately does not say why -- answered already, past its
   * deadline, settled by something else a moment sooner, a key this engine does not know, or for an
   * agent type this process does not serve. They mean the same thing to a caller, and telling them
   * apart would need a record of settled calls that nothing else wants and somebody would have to
   * sweep.
   */
  record Ignored() implements ReplyOutcome {}
}
