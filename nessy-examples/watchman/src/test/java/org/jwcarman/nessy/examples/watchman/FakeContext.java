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
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;

/**
 * The harness's side of a tool call, standing in.
 *
 * <p>{@link #defer()} hands out a fixed id and remembers that it was asked — which is exactly the
 * thing {@code LongJobTest} needs to check against what the completion door was later told.
 */
final class FakeContext implements ToolContext {

  static final ComputationId DEFERRED = ComputationId.of("computation-under-test");

  private final ToolCall call =
      new ToolCall("call-under-test", "tool-under-test", JsonNodeFactory.instance.objectNode());
  private final List<String> progress = new ArrayList<>();
  private int defers;

  @Override
  public ToolCall call() {
    return call;
  }

  @Override
  public ComputationId invocation() {
    return ComputationId.of("invocation-under-test");
  }

  @Override
  public void progress(String message) {
    progress.add(message);
  }

  @Override
  public ComputationId defer() {
    defers++;
    return DEFERRED;
  }

  /** How many times the tool asked for an id. */
  int defers() {
    return defers;
  }
}
