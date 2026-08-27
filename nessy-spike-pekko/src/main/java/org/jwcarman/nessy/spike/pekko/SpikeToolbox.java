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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * THROWAWAY SPIKE. Two trivial tools and a one-line approval policy.
 *
 * <p>{@code clock} is harmless and runs on sight. {@code delete} is not, so its call parks: its
 * {@link ToolCallActor} spawns an {@link ApprovalActor} and waits for a human, costing one small
 * actor and no thread.
 */
public final class SpikeToolbox {

  public boolean needsApproval(String tool) {
    return "delete".equals(tool);
  }

  public String questionFor(SpikeToolCall call) {
    return "may I run " + call.tool() + " on " + call.argument() + "?";
  }

  /** Runs a call on the executor it is handed — never on a Pekko dispatcher. */
  public CompletionStage<String> run(SpikeToolCall call, Executor blocking) {
    return CompletableFuture.supplyAsync(
        () ->
            switch (call.tool()) {
              case "clock" -> "12:00";
              case "delete" -> "deleted " + call.argument();
              default -> "no such tool: " + call.tool();
            },
        blocking);
  }
}
