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
package org.jwcarman.nessy.examples.toollab;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A provider that returns what it was told to, in order, and remembers every request it was sent.
 * An entry that is an exception is thrown instead of returned.
 */
final class ScriptedProvider implements InferenceProvider {

  private final Deque<Object> script = new ArrayDeque<>();
  private final List<InferenceRequest> requests = new CopyOnWriteArrayList<>();

  ScriptedProvider then(InferenceResult result) {
    script.add(result);
    return this;
  }

  ScriptedProvider thenThrow(RuntimeException failure) {
    script.add(failure);
    return this;
  }

  List<InferenceRequest> requests() {
    return new ArrayList<>(requests);
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    requests.add(request);
    Object next = script.poll();
    if (next == null) {
      throw new IllegalStateException("the script ran out after " + requests.size() + " requests");
    }
    if (next instanceof RuntimeException failure) {
      throw failure;
    }
    return (InferenceResult) next;
  }

  /** A reply that calls one tool. */
  static InferenceResult calling(String id, String tool, String arguments) {
    return new InferenceResult.Actions(List.of(new Block.ToolCall(id, tool, arguments)));
  }

  /** A reply that calls one tool, and was counted. */
  static InferenceResult calling(String id, String tool, String arguments, Usage usage) {
    return new InferenceResult.Actions(List.of(new Block.ToolCall(id, tool, arguments)), usage);
  }

  /** A reply that is text. */
  static InferenceResult saying(String text) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)));
  }

  /** A reply that is text, and was counted. */
  static InferenceResult saying(String text, Usage usage) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)), usage);
  }
}
