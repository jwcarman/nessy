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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Usage;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fix for Opus review Finding 4: a batch of effects is fanned out concurrently rather than
 * performed one at a time, and folding stays serialized regardless.
 *
 * <p>Each test here builds a turn that asks for several tool calls at once -- what {@link
 * org.jwcarman.nessy.engine.core.AgentState.Inferring#completed} turns one {@code
 * InferenceResult.Actions} into -- so {@link DefaultDirectHarness#drive} has a real batch of more
 * than one effect to fan out. Every wait here is a latch with a bounded timeout, never a sleep: a
 * test that slept and hoped would prove nothing about the old serial code, which would simply make
 * the same assertion true a little later.
 */
class DirectHarnessFanOutTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final ToolName LOOKUP = new ToolName("lookup");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Clock clock = Clock.systemUTC();
  private final InMemoryAgentEvents events = new InMemoryAgentEvents(clock);
  private final InMemoryPayloads payloads = new InMemoryPayloads();

  record Lookup(String id) {}

  /** N tool calls at once, each with its own call id, all asking the same tool. */
  private static InferenceResult asking(int n) {
    List<Block.ActionRequestContent> calls = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      calls.add(new Block.ToolCall(new CallId("call-" + i), LOOKUP, "{\"id\":\"" + i + "\"}"));
    }
    return new InferenceResult.Actions(calls, Usage.unknown());
  }

  private static InferenceResult answering(String text) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)), Usage.unknown());
  }

  /** Answers with whatever it is handed, in order, one per call. */
  private static final class Scripted implements InferenceProvider {
    private final java.util.Deque<InferenceResult> answers = new java.util.ArrayDeque<>();

    Scripted then(InferenceResult result) {
      answers.add(result);
      return this;
    }

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      return answers.poll();
    }
  }

  /** A tool that answers at once, whoever it is called for. */
  private static Tool<Lookup> trivialTool() {
    return new Tool<Lookup>() {
      @Override
      public Class<Lookup> inputType() {
        return Lookup.class;
      }

      @Override
      public ToolName name() {
        return LOOKUP;
      }

      @Override
      public String description() {
        return "answers at once";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("done " + request.callId().value())));
      }
    };
  }

  /**
   * A tool that counts down {@code started} the moment it is entered and then waits on {@code
   * release} before answering -- so a test can pin exactly how many of it are inside {@link #call}
   * at once, deterministically, rather than inferring concurrency from timing.
   */
  private static final class GatedTool implements Tool<Lookup> {
    private final CountDownLatch started;
    private final CountDownLatch release;
    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    GatedTool(CountDownLatch started, CountDownLatch release) {
      this.started = started;
      this.release = release;
    }

    @Override
    public Class<Lookup> inputType() {
      return Lookup.class;
    }

    @Override
    public ToolName name() {
      return LOOKUP;
    }

    @Override
    public String description() {
      return "counts how many of it run at once";
    }

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
      int now = current.incrementAndGet();
      peak.accumulateAndGet(now, Math::max);
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        current.decrementAndGet();
      }
      return Awaited.ready(ToolResult.ok(new Block.Text("done " + request.callId().value())));
    }

    int current() {
      return current.get();
    }

    int peak() {
      return peak.get();
    }
  }

  /**
   * An approver that signals {@code arrived} the moment it is asked -- proving the batch's later
   * calls were attempted at all -- and then blocks for one call id the way a synchronous call to a
   * slow human approver does, until the test releases it.
   */
  private static final class SlowFor implements Approver {
    private final CallId slow;
    private final CountDownLatch arrived;
    private final CountDownLatch release;

    SlowFor(CallId slow, CountDownLatch arrived, CountDownLatch release) {
      this.slow = slow;
      this.arrived = arrived;
      this.release = release;
    }

    @Override
    public Awaited<ApprovalResult> approve(ApprovalRequest request) {
      arrived.countDown();
      if (request.callId().equals(slow)) {
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return Awaited.ready(ApprovalResult.approved());
    }
  }

  private DefaultDirectHarnessFactory factoryFor(InferenceProvider model, Locks locks) {
    return DefaultDirectHarnessFactory.of(
        c ->
            c.backend(new FixedDirectBackend(locks, events, payloads))
                .provider(model)
                .schemas(new VictoolsInputSchemaGenerator())
                .mapper(MAPPER)
                .clock(clock));
  }

  /**
   * The defect, pinned: on the old serial {@code drive}, {@code perform} for call-1's approval
   * blocks the one thread driving the turn, so call-2 and call-3's approvals are never even
   * attempted until call-1 returns -- {@code arrived} would sit at 2 forever and this test's
   * bounded wait would time out. On the fan-out fix, all three approvals are submitted before any
   * of them is waited on, so {@code arrived} reaches zero while call-1 is still held.
   */
  @Test
  @DisplayName(
      "a batch's later calls are attempted while an earlier one's approval is still outstanding --"
          + " the double-run defect Opus review Finding 4 describes")
  void later_calls_are_attempted_while_an_earlier_approval_is_still_outstanding() throws Exception {
    AgentId agent = AgentId.random();
    CallId slowCall = new CallId("call-1");
    CountDownLatch arrived = new CountDownLatch(3);
    CountDownLatch release = new CountDownLatch(1);
    Approver approver = new SlowFor(slowCall, arrived, release);
    Scripted model = new Scripted().then(asking(3)).then(answering("done"));

    DirectHarness<String, String> harness =
        factoryFor(model, new InMemoryLocks())
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("terse")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.model("a-model"));
                  c.tool(trivialTool(), t -> t.approver(approver));
                });

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> future = callers.submit(() -> harness.ask(agent, "look up three"));

      assertThat(arrived.await(5, TimeUnit.SECONDS))
          .as(
              "all three approvals were asked for while the first is still blocked; serial"
                  + " performance would never reach the second or third until the first returns")
          .isTrue();

      release.countDown();
      assertThat(future.get()).isEqualTo(new Outcome.Answered<>("done"));
      assertThat(events.readFrom(agent, Seq.NONE))
          .extracting(e -> e.getClass().getSimpleName())
          .filteredOn(name -> name.equals("ToolSucceeded"))
          .as("all three calls ran to completion, exactly once each, once released")
          .hasSize(3);
    }
  }

  /**
   * With the limit set to two and six calls in the batch, the high-water mark of concurrent tool
   * calls is exactly two -- not "at most two", which a broken fan-out (everything serial, peak
   * always one) would also satisfy.
   */
  @Test
  @DisplayName("no more than maxInFlight effects of one turn's batch ever run at once")
  void max_in_flight_bounds_one_turns_batch() throws Exception {
    AgentId agent = AgentId.random();
    int limit = 2;
    int calls = 6;
    CountDownLatch started = new CountDownLatch(limit);
    CountDownLatch release = new CountDownLatch(1);
    GatedTool tool = new GatedTool(started, release);
    Scripted model = new Scripted().then(asking(calls)).then(answering("done"));

    DirectHarness<String, String> harness =
        factoryFor(model, new InMemoryLocks())
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("terse")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.model("a-model"))
                      .maxInFlight(limit);
                  c.tool(tool, t -> t.approver(Approver.allow()));
                });

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> future = callers.submit(() -> harness.ask(agent, "look up six"));

      assertThat(started.await(5, TimeUnit.SECONDS))
          .as("exactly the permitted number started running concurrently")
          .isTrue();
      assertThat(tool.current())
          .as("no more than the limit are ever inside the tool at once")
          .isEqualTo(limit);

      release.countDown();
      assertThat(future.get()).isEqualTo(new Outcome.Answered<>("done"));
    }

    assertThat(tool.peak())
        .as("the high-water mark is exactly the limit, not merely under it")
        .isEqualTo(limit);
  }

  /** Counts concurrent holders of one lock kind, with no mutex of its own to mask a bug. */
  private static final class CountingLocks implements Locks {
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger max = new AtomicInteger();

    @Override
    public <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
      Objects.requireNonNull(work, "work must not be null");
      int now = concurrent.incrementAndGet();
      max.accumulateAndGet(now, Math::max);
      try {
        return work.get();
      } finally {
        concurrent.decrementAndGet();
      }
    }

    int max() {
      return max.get();
    }
  }

  /**
   * Folding is the transaction boundary and must never overlap, even though the effects that feed
   * it now run concurrently. Every fold is held open until the test says to proceed -- via {@code
   * atFold} -- so a real overlap would show as {@code concurrent} exceeding one while the counter
   * is being observed, not something a fast fold could hide by finishing before anyone looked.
   */
  @Test
  @DisplayName("folding stays serialized: two folds of the same batch never overlap")
  void folding_never_overlaps_though_the_effects_that_feed_it_do() throws Exception {
    AgentId agent = AgentId.random();
    int calls = 4;
    CountDownLatch started = new CountDownLatch(calls);
    CountDownLatch release = new CountDownLatch(1);
    GatedTool tool = new GatedTool(started, release);
    Scripted model = new Scripted().then(asking(calls)).then(answering("done"));
    CountingLocks locks = new CountingLocks();

    DirectHarness<String, String> harness =
        factoryFor(model, locks)
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("terse")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.model("a-model"))
                      .maxInFlight(calls);
                  c.tool(tool, t -> t.approver(Approver.allow()));
                });

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> future = callers.submit(() -> harness.ask(agent, "look up four"));

      assertThat(started.await(5, TimeUnit.SECONDS))
          .as("the tool calls themselves genuinely overlapped")
          .isTrue();
      assertThat(tool.current()).isEqualTo(calls);

      release.countDown();
      assertThat(future.get()).isEqualTo(new Outcome.Answered<>("done"));
    }

    assertThat(locks.max())
        .as("but no two folds -- the withLock steps that write an outcome down -- ever overlapped")
        .isEqualTo(1);
  }

  /** A clock that stands still until a test moves it, so "overdue" is something a test states. */
  private static final class AdvanceableClock extends Clock {
    private Instant now;

    AdvanceableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("the test's clock has one zone");
    }
  }

  /**
   * The budget fix, pinned: {@code inFlight} is harness-wide, so call-2's {@code CallTool} effect
   * can queue for a permit behind call-1's -- held here by a latch standing in for a slow tool --
   * for arbitrarily long. If {@link DefaultDirectHarness#perform} handed a freshly-permitted effect
   * a fresh full timeout, call-2 would run anyway, however late its permit arrived; measuring what
   * is left of its OWN budget instead means that once that budget is spent while queued, call-2 is
   * discharged as failed the moment it is finally permitted, and its {@link Tool#call} is never
   * invoked at all.
   */
  @Test
  @DisplayName(
      "an effect whose own budget elapsed while queued for a permit is discharged without being"
          + " performed, not handed a fresh timeout")
  void an_effect_whose_budget_elapsed_while_queued_for_a_permit_is_not_performed()
      throws Exception {
    AdvanceableClock stepped = new AdvanceableClock(Instant.now());
    InMemoryAgentEvents steppedEvents = new InMemoryAgentEvents(stepped);
    InMemoryPayloads steppedPayloads = new InMemoryPayloads();
    AgentId agent = AgentId.random();

    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger secondRan = new AtomicInteger();
    Duration toolTimeout = Duration.ofSeconds(30);
    Tool<Lookup> tool =
        new Tool<Lookup>() {
          @Override
          public Class<Lookup> inputType() {
            return Lookup.class;
          }

          @Override
          public ToolName name() {
            return LOOKUP;
          }

          @Override
          public String description() {
            return "call-1 holds the only permit; call-2 must never run";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
            if (request.callId().equals(new CallId("call-1"))) {
              firstStarted.countDown();
              try {
                releaseFirst.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            } else {
              secondRan.incrementAndGet();
            }
            return Awaited.ready(ToolResult.ok(new Block.Text("done " + request.callId().value())));
          }
        };
    Scripted model = new Scripted().then(asking(2)).then(answering("done"));

    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                c ->
                    c.backend(
                            new FixedDirectBackend(
                                new InMemoryLocks(), steppedEvents, steppedPayloads))
                        .provider(model)
                        .schemas(new VictoolsInputSchemaGenerator())
                        .mapper(MAPPER)
                        .clock(stepped))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("terse")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.model("a-model"))
                      // One permit: call-2's CallTool effect cannot even be submitted until
                      // call-1's is released, which is the gap the budget fix has to survive.
                      .maxInFlight(1);
                  c.tool(tool, t -> t.timeout(toolTimeout).approver(Approver.allow()));
                });

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> future = callers.submit(() -> harness.ask(agent, "look up two"));

      assertThat(firstStarted.await(5, TimeUnit.SECONDS))
          .as("call-1 is running and holding the only permit")
          .isTrue();

      // Real time passing while call-2 waits for a permit -- exactly what a harness-wide semaphore
      // opens up, and exactly what a fresh-timeout bug would paper over.
      stepped.advance(toolTimeout.plusSeconds(1));

      releaseFirst.countDown();
      assertThat(future.get()).isEqualTo(new Outcome.Answered<>("done"));
    }

    assertThat(secondRan.get())
        .as(
            "call-2's own budget had already elapsed by the time its permit freed; it must never"
                + " run")
        .isZero();
    assertThat(steppedEvents.readFrom(agent, Seq.NONE))
        .extracting(e -> e.getClass().getSimpleName())
        .as("call-1 ran to completion; call-2 was discharged as failed without ever running")
        .contains("ToolSucceeded", "ToolFailed");
  }
}
