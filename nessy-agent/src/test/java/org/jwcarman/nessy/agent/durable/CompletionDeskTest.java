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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;

/**
 * The result door: completes {@code tool:} slots with a {@code ToolResult} (spec §4.3 amendment).
 */
class CompletionDeskTest {

  private record Fired(Continuation continuation, Outcome outcome) {}

  private final StoredComputations backend = new StoredComputations(new InMemoryScopedStore());
  private final ContinuationDispatcher dispatcher = new ContinuationDispatcher();
  private final List<Fired> fired = new ArrayList<>();
  private final CompletionDesk desk = new CompletionDesk(backend, dispatcher);

  private static final ComputationId SLOT = ComputationId.of("tool:t:a:c1");
  private static final Continuation RESUME = new Continuation("RESUME", "{}");

  private void park() {
    dispatcher.register("RESUME", (c, o) -> fired.add(new Fired(c, o)));
    backend.create(SLOT);
    backend.await(SLOT, RESUME);
  }

  @Test
  void completingFiresTheContinuationWithTheResult() {
    park();
    desk.complete(SLOT, ToolResult.ok("approved"));
    assertThat(fired)
        .containsExactly(new Fired(RESUME, new Outcome.Success(ToolResult.ok("approved"))));
  }

  @Test
  void failingFiresAFailure() {
    park();
    desk.fail(SLOT, "too risky");
    assertThat(fired).containsExactly(new Fired(RESUME, new Outcome.Failure("too risky")));
  }

  @Test
  void completingAnUnknownIdBirthsTheSlotAlreadySucceededAndFiresNothing() {
    var ghost = ComputationId.of("ghost");
    desk.complete(ghost, ToolResult.ok("x"));
    assertThat(backend.status(ghost)).contains(ComputationStatus.SUCCEEDED);
    assertThat(fired).isEmpty();
  }

  @Test
  void aSecondCompletionIsRefusedAsAlreadyCompleted() {
    park();
    desk.complete(SLOT, ToolResult.ok("approved"));
    var again = ToolResult.ok("again");
    assertThatThrownBy(() -> desk.complete(SLOT, again))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already completed");
  }

  @Test
  void thereIsExactlyOneHandlePerQuestion() {
    // the deterministic id IS the handle: a re-derived id equals the original — no siblings exist
    park();
    var reDerived = ComputationId.of("tool:t:a:c1");
    desk.complete(reDerived, ToolResult.ok("approved via the re-derived handle"));
    assertThat(fired).hasSize(1);
  }
}
