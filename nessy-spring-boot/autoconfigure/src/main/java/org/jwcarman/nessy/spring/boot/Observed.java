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
package org.jwcarman.nessy.spring.boot;

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.engine.observability.ObservedApprover;
import org.jwcarman.nessy.engine.observability.ObservedTool;

/**
 * Observability by WRAPPING the collaborators the engine calls, rather than by listening to what it
 * narrates.
 *
 * <p><b>Why wrapping and not listening.</b> A subscriber hears that a turn started and that it
 * ended, and can time the gap — but a turn that calls tools makes several model calls inside that
 * gap, and narration draws no boundary around any of them. Measured against a real provider: one
 * round was two calls of 5.5s and 6.7s, which a subscriber could only have reported as twelve
 * seconds of something.
 *
 * <p><b>The names are the OpenTelemetry GenAI semantic conventions', not ours.</b> That is the
 * whole point of emitting them: a dashboard that already groups by {@code gen_ai.provider.name}, or
 * an alert that already watches {@code gen_ai.client.operation.duration}, works on a Nessy
 * application without being taught anything. Inventing {@code nessy.model.call} would have made
 * every one of them useless here.
 *
 * <p>Token counts are a HISTOGRAM, never tags. A tag whose value is 606 makes a new time series per
 * distinct token count, which is how a metrics bill becomes a story.
 *
 * <p><b>What this cannot do, and why.</b> Nothing here can say which agent or turn a model call
 * belongs to: {@link ModelRequest} carries a context, a prompt, tools and capabilities, and no
 * identity; {@link ToolCallRequest} carries a reply address and nothing else. So model and tool
 * spans are correctly timed and correctly attributed, and they are ROOTS — they do not nest under a
 * turn, because there is nothing to nest them under. Approvals are the exception: {@link
 * ApprovalRequest} knows its agent and its call.
 */
public final class Observed {

  private Observed() {}

  /**
   * One tool, observed.
   *
   * <p>Semconv gives tool execution its own operation name and its own attribute, so this is not a
   * Nessy-shaped metric either: it lands in the same {@code gen_ai.client.operation.duration}
   * histogram as a chat call, distinguished by {@code gen_ai.operation.name}.
   */
  public static <I> Tool<I> tool(Tool<I> delegate, ObservationRegistry observations) {
    return ObservedTool.wrap(delegate, observations);
  }

  /** One approver, observed; see {@link ObservedApprover#wrap}. */
  public static Approver approver(Approver delegate, ObservationRegistry observations) {
    return ObservedApprover.wrap(delegate, observations);
  }
}
