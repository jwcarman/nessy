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
package org.jwcarman.nessy.api;

/**
 * What became of an input told to an agent.
 *
 * <p>Returned rather than thrown, because the caller is almost always serving a request from
 * somewhere -- a webhook, a queue consumer, a page -- and each arm maps to a different thing to
 * tell whoever is on the other end. A terminated agent is an ordinary answer, not an exception.
 *
 * <p><b>Neither is a quiet no-op</b>, and that is the point of the type. An input dropped without a
 * word leaves the person who sent it believing the agent heard.
 */
public sealed interface TellOutcome {

  /**
   * The agent took the input. Every accepted input is handed to the agent type's backlog policy,
   * and a turn starts at once when the agent is idle. The policy decides what waits: it may keep
   * the input, merge it with what waits, replace what waits, drop older inputs to hold a bound, or
   * discard the arrival itself as a repeat. So an accepted input is not a promise that it will run
   * by itself, or at all. An input that is still waiting when the agent is terminated is abandoned.
   *
   * <p>Inside a caller's transaction, {@code Accepted} is only as durable as the caller's commit.
   */
  record Accepted() implements TellOutcome {}

  /**
   * The agent has been terminated and takes no more input. The input was dropped: nothing was
   * stored, nothing was written to the story, and no turn will run for it.
   *
   * <p>An agent terminated while its last turn is still in progress answers this at once, though
   * its status does not read as terminated until that turn ends.
   */
  record Terminated() implements TellOutcome {}
}
