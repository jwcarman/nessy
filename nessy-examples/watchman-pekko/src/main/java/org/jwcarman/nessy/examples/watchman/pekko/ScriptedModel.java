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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * A watchman round with no tokens and no network: look at the box, propose the one thing that needs
 * a human, then write the notes. Deterministic on the transcript, so a round resumed in a different
 * JVM asks the same question and gets the same answer.
 */
public final class ScriptedModel implements WatchmanModel {

  private final Duration latency;

  public ScriptedModel(Duration latency) {
    this.latency = latency;
  }

  @Override
  public CompletionStage<ModelReply> reply(List<Turn> transcript, Executor blocking) {
    ModelReply reply = script(transcript);
    return CompletableFuture.supplyAsync(
        () -> {
          sleep(latency);
          return reply;
        },
        blocking);
  }

  /**
   * Round-aware, and it has to be: the transcript now spans every round this agent has ever done,
   * so "have any tools run?" is true forever after the first one. The question is whether tools
   * have run since the LAST thing a human said.
   *
   * <p>Call ids are unique per round for the same reason. A real model never reissues an id, and
   * {@link Transcript#recall} keeps only the first result for any given id -- so reusing {@code
   * call-disk} every round would silently drop every result after the first.
   */
  private static ModelReply script(List<Turn> transcript) {
    int rounds = (int) transcript.stream().filter(Turn.User.class::isInstance).count();
    int lastUser = -1;
    for (int i = 0; i < transcript.size(); i++) {
      if (transcript.get(i) instanceof Turn.User) {
        lastUser = i;
      }
    }
    boolean answeredThisRound =
        transcript.subList(lastUser + 1, transcript.size()).stream()
            .anyMatch(Turn.ToolResult.class::isInstance);
    if (answeredThisRound) {
      return new ModelReply.Said(
          "Rounds complete. Disk is filling and there are unused images to reclaim.");
    }
    String suffix = "-" + Math.max(rounds, 1);
    return new ModelReply.AskedForTools(
        "Looking at the box.",
        List.of(
            new Turn.ToolRequest("call-disk" + suffix, "disk_usage", "{}"),
            new Turn.ToolRequest("call-containers" + suffix, "containers", "{}"),
            new Turn.ToolRequest("call-prune" + suffix, "prune_images", "{}")));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted in the scripted model", e);
    }
  }
}
