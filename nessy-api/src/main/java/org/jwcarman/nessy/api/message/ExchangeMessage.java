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
package org.jwcarman.nessy.api.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;

/**
 * The assistant using tools: what it said while working, the calls it made, and what came back.
 *
 * <p>Named for the exchange rather than for tools, though tool calls are the only kind there is
 * today. The shape — the model asks the agent for something, and gets an answer back in the same
 * breath — is what this arm is about; if a second kind of exchange ever arrives, it belongs here
 * rather than in a second arm that would need the same invariant all over again.
 *
 * <p><b>One value, because they are one thing.</b> A call and its result were three separate
 * invariants before this existed — validated when a context was built, validated again when the
 * pair was remembered, and held together by hand in the engine while the calls settled. Making the
 * exchange a single value retires all three: a call with no answer and an answer to no call are
 * both unrepresentable, and no transformer can split what was never two.
 *
 * <p><b>Replayed intact.</b> Providers hand back opaque state with a call — a signature, a
 * continuity token — and need it returned to trust the exchange next turn. It rides in {@code
 * content} as a {@code ProviderBlock}, which is why nothing here may be quietly dropped.
 *
 * @param content what the assistant said and asked for: commentary, calls, provider state
 * @param results one result per call, answering exactly those calls
 */
public record ExchangeMessage(List<ExchangeContentBlock> content, List<ToolResultBlock> results)
    implements ContextMessage {

  public ExchangeMessage {
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(results, "results must not be null");
    content = List.copyOf(content);
    results = List.copyOf(results);
    requireAnswered(content, results);
  }

  /** Every call answered, every answer expected — checked once, here, and nowhere else again. */
  private static void requireAnswered(
      List<ExchangeContentBlock> content, List<ToolResultBlock> results) {
    List<String> called = new ArrayList<>();
    for (ExchangeContentBlock block : content) {
      if (block instanceof ToolCallBlock call) {
        called.add(call.call().id());
      }
    }
    if (called.isEmpty()) {
      throw new IllegalArgumentException("an asking with no tool calls is not asking for anything");
    }
    List<String> answered = results.stream().map(ToolResultBlock::toolUseId).toList();
    for (String id : called) {
      if (!answered.contains(id)) {
        throw new IllegalArgumentException("unanswered tool call: " + id);
      }
    }
    for (String id : answered) {
      if (!called.contains(id)) {
        throw new IllegalArgumentException("tool result answering no call: " + id);
      }
    }
  }

  /** The calls this exchange made, in the order the model made them. */
  public List<ToolCallBlock> calls() {
    return content.stream()
        .filter(ToolCallBlock.class::isInstance)
        .map(ToolCallBlock.class::cast)
        .toList();
  }
}
