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

import java.time.Duration;
import java.util.List;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A watchman with no model behind it, for soaks and tests: every round checks the disks, proposes a
 * prune, and writes its notes once both have been dealt with.
 *
 * <p>It answers by looking at where the turn stands rather than by counting calls, so it needs no
 * memory of its own and survives a restart mid-round exactly as a real model would.
 */
public final class ScriptedWatchmanProvider implements InferenceProvider {

  private final Duration latency;

  public ScriptedWatchmanProvider(Duration latency) {
    this.latency = latency;
  }

  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    sleep(latency);
    Turn round =
        request.context().turns().stream()
            .filter(turn -> !turn.complete())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("asked with no round under way"));
    if (!round.exchanges().isEmpty()) {
      return new InferenceResult.Answer(
          List.of(new Block.Text("Rounds complete. Nothing needs your attention.")));
    }
    // Call ids carry the round, as a real model's would be unique: the board is keyed on the
    // call, and a prune proposed every round under the same id would be one question forever.
    String prefix = "round-" + round.id().value();
    return new InferenceResult.Actions(
        List.of(
            new Block.Commentary("Checking the disks, and proposing a prune."),
            new Block.ToolCall(prefix + "-disks", "disk_usage", "{}"),
            new Block.ToolCall(prefix + "-prune", "prune_images", "{}")));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
