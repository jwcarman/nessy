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
package org.jwcarman.nessy.agent.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;

/**
 * Answers each callModel() with the next scripted outcome, delivered asynchronously through the
 * pump — honoring the §4 contract: the Sink never fires on the dispatching stack.
 */
public final class ScriptedModelExecutor implements ModelCallExecutor {

  private final Executor pump;
  private final Sink sink;
  private final Deque<ModelOutcome> script = new ArrayDeque<>();
  private final List<Integer> memorySizesAtCall = new ArrayList<>();
  private final RecordingMemory memory;

  public ScriptedModelExecutor(Executor pump, Sink sink, RecordingMemory memory) {
    this.pump = pump;
    this.sink = sink;
    this.memory = memory;
  }

  public void enqueue(ModelOutcome outcome) {
    script.add(outcome);
  }

  @Override
  public void callModel() {
    memorySizesAtCall.add(memory.remembered().size());
    ModelOutcome outcome = script.poll();
    if (outcome == null) {
      throw new IllegalStateException("callModel with an empty script");
    }
    pump.execute(() -> sink.deliver(new AgentEvent.ModelFinished(outcome)));
  }

  public int callCount() {
    return memorySizesAtCall.size();
  }

  /** What memory held at the instant of each call — the commit-before-dispatch witness (§3.4). */
  public List<Integer> memorySizesAtCall() {
    return List.copyOf(memorySizesAtCall);
  }
}
