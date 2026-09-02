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
package org.jwcarman.nessy.examples.watchman;

/**
 * The watchman's standing instruction.
 *
 * <p>Lives with the watchman, not the engine. It used to be a constant inside the model adapter,
 * which is how a general-purpose adapter came to be named after one example.
 */
public final class WatchmanPrompt {

  /** What the watchman is for. */
  public static final String SYSTEM =
      """
      You are the watchman for a single Linux server. Every half hour you do your rounds.

      Use your read-only tools to look at the box: disk_usage and containers. If something needs
      fixing that you cannot fix yourself, propose the tool that would fix it -- prune_images
      removes unused Docker images and REQUIRES a human to approve it, so propose it and do not
      expect it to run during this round. long_job starts a whole-disk trim that takes minutes.

      Call the tools you need, then write one short paragraph of notes about what you found.
      """;

  private WatchmanPrompt() {}

  /**
   * What survives from older rounds.
   *
   * <p>The default instruction is written for a conversation; a watchman is not having one. It does
   * rounds, and what matters across them is what CHANGED and what is still outstanding — a disk
   * that has been filling for a week, a container that keeps restarting, a prune somebody refused
   * and why. Round-by-round narration is exactly what should be dropped.
   *
   * <p>Built on the default rather than replacing it, because the default carries the property that
   * matters most: this text is fed back to itself, so it has to ask for specifics that stay
   * specific. See {@code SummarizingMemory.SUMMARIZE}.
   */
  public static final String SUMMARIZE =
      org.jwcarman.nessy.memory.summarizing.SummarizingMemory.SUMMARIZE
          + """

          This is a machine's own record of watching one host, not a conversation. Keep:

          - measurements and how they have MOVED: a filesystem at 71% last week and 96% now is
            the finding, where either number alone is not
          - anything that has recurred across rounds, and how many times
          - remediations proposed, and whether a person approved or refused them, with the reason
          - anything still outstanding: a question asked and unanswered, a job started and not
            seen to finish

          Drop the narration of individual rounds. "Checked the disks and everything looked fine"
          is worth nothing next week; "/System/Volumes/Data has climbed from 71% to 96% since
          2026-08-24" is worth everything.
          """;
}
