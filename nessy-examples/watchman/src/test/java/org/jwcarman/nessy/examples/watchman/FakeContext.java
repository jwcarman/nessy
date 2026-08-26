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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The harness's side of a tool call, standing in.
 *
 * <p>A {@link ToolContext} is a plain record now (deferral-by-callback spec §7), so there is
 * nothing left to fake about it. What DOES need standing in is the harness's half of a deferral:
 * {@link #handOff} is what the {@code DeferToolCall} effect does for real — create the computation,
 * then run the tool's callback with the id and the agreed deadline. A tool cannot start its own
 * work until that happens, which is exactly the property the tests here assert.
 */
final class FakeContext {

  static final ComputationId DEFERRED = ComputationId.of("computation-under-test");

  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  private FakeContext() {}

  /** The context a tool under test is handed. */
  static ToolContext toolContext() {
    return toolContext(ToolEventListener.noop());
  }

  /** As {@link #toolContext()}, with somewhere for a tool's progress to be heard. */
  static ToolContext toolContext(ToolEventListener events) {
    ToolCall call =
        new ToolCall("call-under-test", "tool-under-test", JsonNodeFactory.instance.objectNode());
    return new ToolContext(call, events, ComputationId.of("invocation-under-test"));
  }

  /**
   * What the harness does once it has parked the work: run the deferral's callback with {@link
   * #DEFERRED} and a deadline. Fails loudly on a tool that did not defer, because every caller here
   * is asserting about one that did.
   */
  static void handOff(Awaited<ToolResult> awaited) {
    if (!(awaited instanceof Awaited.Deferred<ToolResult>(var callback, var _))) {
      throw new AssertionError("expected a deferral, got " + awaited);
    }
    callback.accept(DEFERRED, DEADLINE);
  }
}
