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

/**
 * THROWAWAY SPIKE. Two trivial tools and a one-line approval policy.
 *
 * <p>{@code clock} is harmless and runs on sight. {@code delete} is not, so it parks: the harness
 * opens it {@link SpikeCallPhase.AwaitingApproval} and waits for a human to send an answer to the
 * agent's entity id, whenever that happens to be.
 */
public final class SpikeToolbox {

  /** The approval policy, such as it is. */
  public SpikeToolCall open(SpikeModelReply.Request request) {
    SpikeCallPhase phase =
        needsApproval(request.tool())
            ? new SpikeCallPhase.AwaitingApproval(
                "may I run " + request.tool() + " on " + request.argument() + "?")
            : new SpikeCallPhase.Running();
    return new SpikeToolCall(request.id(), request.tool(), request.argument(), phase);
  }

  private static boolean needsApproval(String tool) {
    return "delete".equals(tool);
  }

  /**
   * Runs a call. Asynchronous on purpose: the entity must never hold its thread across a tool, so
   * the result comes back as a message like everything else.
   */
  public CompletionStage<String> run(SpikeToolCall call) {
    return CompletableFuture.supplyAsync(
        () ->
            switch (call.tool()) {
              case "clock" -> "12:00";
              case "delete" -> "deleted " + call.argument();
              default -> "no such tool: " + call.tool();
            });
  }
}
