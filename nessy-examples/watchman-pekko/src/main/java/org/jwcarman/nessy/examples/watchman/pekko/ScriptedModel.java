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

  private static ModelReply script(List<Turn> transcript) {
    boolean toolsHaveRun = transcript.stream().anyMatch(Turn.ToolResult.class::isInstance);
    if (toolsHaveRun) {
      return new ModelReply.Said(
          "Rounds complete. Disk is filling and there are unused images to reclaim.");
    }
    return new ModelReply.AskedForTools(
        "Looking at the box.",
        List.of(
            new Turn.ToolRequest("call-disk", "disk_usage", "{}"),
            new Turn.ToolRequest("call-containers", "containers", "{}"),
            new Turn.ToolRequest("call-prune", "prune_images", "{}")));
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
