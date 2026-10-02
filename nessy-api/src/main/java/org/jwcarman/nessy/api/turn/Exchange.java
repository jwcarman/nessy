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
package org.jwcarman.nessy.api.turn;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;

/**
 * One round of asking and answering inside a turn: what the model wanted done, and what came of it.
 *
 * <p>A turn holds as many of these as it took before the model had what it needed. They are
 * strictly sequential -- the model is only asked again once every call in the round has an outcome
 * -- so the last one is the only one that can still be open.
 *
 * <p><b>Re-batched here, having been stored one result at a time.</b> Results are written as they
 * arrive, because they finish at different moments and holding finished work undurable to wait on
 * slow work is how work gets lost. But every wire wants them gathered against the request they
 * answer, so gathering is the projection's job and the pairing is done once, here, rather than by
 * every adapter.
 *
 * @param request everything the model said in that round, calls and prose and vendor state alike,
 *     in the order it said them -- order is load-bearing when a signature covers it
 * @param outcomes what came back, in the order it came back, which need not be the order asked
 * @param actions what each call was recorded as doing, when it was requested -- an entry for every
 *     call in {@code request} and for nothing else
 * @param results what each call that succeeded was recorded as returning -- an entry for every call
 *     that succeeded and for no other; a line may be empty, for a tool with nothing worth saying
 */
public record Exchange(
    Seq seq,
    List<Block.ActionRequestContent> request,
    List<ToolOutcome> outcomes,
    Map<CallId, String> actions,
    Map<CallId, String> results) {

  public Exchange {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(outcomes, "outcomes must not be null");
    Objects.requireNonNull(actions, "actions must not be null");
    Objects.requireNonNull(results, "results must not be null");
    request = List.copyOf(request);
    outcomes = List.copyOf(outcomes);
    actions = Map.copyOf(actions);
    results = Map.copyOf(results);
    Set<CallId> asked =
        request.stream()
            .filter(Block.ToolCall.class::isInstance)
            .map(Block.ToolCall.class::cast)
            .map(Block.ToolCall::id)
            .collect(Collectors.toSet());
    for (CallId id : asked) {
      if (!actions.containsKey(id)) {
        throw new IllegalArgumentException("call " + id.value() + " has no action");
      }
    }
    for (CallId id : actions.keySet()) {
      if (!asked.contains(id)) {
        throw new IllegalArgumentException("an action for " + id.value() + ", which is not a call");
      }
    }
    for (CallId id : results.keySet()) {
      if (!asked.contains(id)) {
        throw new IllegalArgumentException("a result for " + id.value() + ", which is not a call");
      }
    }
  }

  /**
   * What the call was recorded as doing, when it was requested.
   *
   * @throws IllegalArgumentException if {@code id} is not one of this round's calls
   */
  public String actionOf(CallId id) {
    String action = actions.get(id);
    if (action == null) {
      throw new IllegalArgumentException(id.value() + " is not one of this exchange's calls");
    }
    return action;
  }

  /**
   * What the call was recorded as returning, if it has succeeded. A call that failed, was denied or
   * is still out has no line; one that succeeded with nothing to say has the empty string.
   *
   * @throws IllegalArgumentException if {@code id} is not one of this round's calls
   */
  public Optional<String> resultOf(CallId id) {
    actionOf(id);
    return Optional.ofNullable(results.get(id));
  }

  /** The calls this round obliges an outcome for, in the order the model made them. */
  public List<Block.ToolCall> calls() {
    return request.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .toList();
  }

  /**
   * Whether every call in this round has been discharged.
   *
   * <p>Reported rather than enforced. An incomplete exchange is a real state -- it is what the
   * agent looks like while a tool is running -- and it is also what a turn looks like if it was
   * closed while one was outstanding, which is a bug in the fold rather than in the story. The
   * projection's job is to make that visible, not to refuse to read it.
   */
  public boolean complete() {
    return outcomes.size() == calls().size();
  }
}
