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
package org.jwcarman.nessy.spike.pekko;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * THROWAWAY SPIKE. A model that spends no tokens and reaches no network.
 *
 * <p>Deterministic on the transcript, so a turn resumed on a different JVM asks the same question
 * and gets the same answer — which is what lets the restart test assert an exact final transcript.
 * Two rounds: the first asks for two tools, the second wraps the turn up.
 *
 * <p>Returns a {@link CompletionStage} rather than a value so the entity is forced to treat a model
 * call the way a real one behaves: slow, off-thread, and completing by message.
 */
public final class ScriptedSpikeModel implements SpikeModel {

  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().name("spike-model").daemon().unstarted(runnable));

  private final Duration latency;

  public ScriptedSpikeModel(Duration latency) {
    this.latency = latency;
  }

  @Override
  public CompletionStage<SpikeModelReply> reply(List<String> transcript) {
    SpikeModelReply reply = script(transcript);
    CompletableFuture<SpikeModelReply> answer = new CompletableFuture<>();
    clock.schedule(() -> answer.complete(reply), latency.toMillis(), TimeUnit.MILLISECONDS);
    return answer;
  }

  private static SpikeModelReply script(List<String> transcript) {
    boolean toolsHaveRun = transcript.stream().anyMatch(line -> line.startsWith("tool: "));
    if (toolsHaveRun) {
      return new SpikeModelReply.Said("the clock says noon and the file is gone");
    }
    return new SpikeModelReply.AskedForTools(
        List.of(
            new SpikeModelReply.Request("call-1", "clock", "now"),
            new SpikeModelReply.Request("call-2", "delete", "/tmp/everything")));
  }

  @Override
  public void close() {
    clock.shutdownNow();
  }
}
