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
 * What became of an attempt to end an agent.
 *
 * <p>Returned rather than thrown, because none of these is exceptional: a caller asking to end an
 * agent somebody else is still asking is an ordinary race, and being told so is the whole point.
 *
 * <p><b>The type exists because ending is only ever accepted from idle.</b> A turn in flight is
 * owed its outcome -- abandoning it would leave effects with nobody to deliver them to -- so a
 * request to end a busy agent is refused rather than queued. Returning nothing made that refusal
 * invisible: the caller had asked, nothing had happened, and no part of the API said so.
 */
public sealed interface TerminationOutcome {

  /** The agent has been ended by this call, and refuses everything from here on. */
  record Ended() implements TerminationOutcome {}

  /**
   * It was already over.
   *
   * <p>Told apart from {@link Ended} because the two answer different questions. Both mean the
   * agent is finished, so a caller that only wants that can treat them alike; a caller reconciling
   * its own records wants to know whether THIS request is what ended it.
   */
  record AlreadyEnded() implements TerminationOutcome {}

  /**
   * A turn is in flight, and nothing was written.
   *
   * <p>Not a failure and not a partial success -- the agent is exactly as it was. Ask again once
   * the turn has ended.
   *
   * <p><b>Nothing remembers that you asked.</b> The direct door has nowhere to record the intent:
   * the queued door writes an ending down and honours it when the agent next falls idle, but here a
   * refused termination is simply refused. A caller that means it must ask again.
   */
  record Busy() implements TerminationOutcome {}
}
