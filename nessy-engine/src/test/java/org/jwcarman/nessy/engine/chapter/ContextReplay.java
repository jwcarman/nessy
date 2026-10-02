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
package org.jwcarman.nessy.engine.chapter;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays one scripted conversation through the real direct harness and reports, call by call, what
 * the model was sent and how much of it was unchanged from the call before.
 *
 * <p>It needs no key and no database: the provider plays the script, the backend is in memory, and
 * the summariser is a stub that never reaches the provider. What it measures is the harness's
 * context, not any model. A provider caches the leading text of a request, so the characters a call
 * shares with the previous call (its {@link Call#stablePrefix()}) stand in for what would be read
 * from cache.
 *
 * <p>The canonical text of a call is its strata in order, each ended by a newline: the system
 * prompt, every summary's text, the tail rendered by {@link Transcripts#render}, memory, state, the
 * active turn rendered the same way, and ambient. That order is the replay's own stand-in for the
 * strata's order, not the order any provider's wire uses, so what the replay shows is its own
 * accounting and not an adapter's placement.
 */
final class ContextReplay {

  private static final AgentType TYPE = new AgentType("replay");
  private static final String MODEL = "the-replayed-model";
  private static final String SYSTEM_PROMPT = "You are terse.";
  private static final Duration PATIENCE_PER_TURN = Duration.ofSeconds(10);
  private static final ToolName LOOKUP = new ToolName("lookup");
  private static final AtomicInteger CALL_IDS = new AtomicInteger();

  /** What {@code lookup} returns: long enough that a tool result is a real share of a turn. */
  static final String LOOKUP_RESULT = "the record says: " + "x".repeat(400);

  private final List<ScriptedTurn> turns;

  private ContextReplay(List<ScriptedTurn> turns) {
    this.turns = turns;
  }

  /** Starts an empty conversation. */
  static Builder conversation() {
    return new Builder();
  }

  /** The model's reply to one call. */
  record Reply(InferenceResult result) {}

  /** The model answers. */
  static Reply answer(String text) {
    return new Reply(new InferenceResult.Answer(List.of(new Block.Text(text)), unreported()));
  }

  /** The model calls one tool with these JSON arguments. */
  static Reply calls(String tool, String arguments) {
    CallId id = new CallId("call-" + CALL_IDS.incrementAndGet());
    return new Reply(
        new InferenceResult.Actions(
            List.of(new Block.ToolCall(id, new ToolName(tool), arguments)), unreported()));
  }

  private static Usage unreported() {
    return Usage.unreported(MODEL);
  }

  private record ScriptedTurn(String input, List<Reply> replies) {}

  /** Collects the scripted turns. */
  static final class Builder {
    private final List<ScriptedTurn> turns = new ArrayList<>();

    /** One turn: the user's input, then the model's replies in order, the last an answer. */
    Builder turn(String input, Reply... replies) {
      turns.add(new ScriptedTurn(input, List.of(replies)));
      return this;
    }

    ContextReplay build() {
      return new ContextReplay(List.copyOf(turns));
    }
  }

  /**
   * Runs the conversation under these settings. Nothing watches the chapter keeper, so use this for
   * a harness without chapters; with chapters, use {@link #run(ChapterPolicy, Customizer)}.
   */
  Report run(Customizer<DirectHarnessConfig<String>> settings) {
    return replay(Optional.empty(), settings);
  }

  /**
   * Runs the conversation with this chapter policy, then the settings. The policy is wrapped so
   * that after each turn the replay waits until the keeper has asked it again, has closed the
   * chapter it named, and has written every summary. The wait is only correct for a policy that
   * closes chapters itself before the maximum chapter length is reached: when the policy names
   * nothing and the maximum is reached, the keeper cuts anyway, and the replay does not see that
   * cut and so does not wait for it.
   */
  Report run(ChapterPolicy policy, Customizer<DirectHarnessConfig<String>> settings) {
    return replay(Optional.of(new Watched(Objects.requireNonNull(policy, "policy"))), settings);
  }

  /** Counts each time the policy is asked and remembers the furthest end it has named. */
  private static final class Watched implements ChapterPolicy {
    private final ChapterPolicy policy;
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicReference<TurnId> furthestEnd = new AtomicReference<>();

    Watched(ChapterPolicy policy) {
      this.policy = policy;
    }

    @Override
    public List<TurnId> ends(OpenTurns open) {
      List<TurnId> ends = policy.ends(open);
      ends.forEach(end -> furthestEnd.accumulateAndGet(end, Watched::later));
      asked.incrementAndGet();
      return ends;
    }

    private static TurnId later(TurnId current, TurnId candidate) {
      return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }
  }

  /** Plays the replies for the turn being driven, and records every request with its place. */
  private static final class Scripted implements InferenceProvider {
    private final Deque<InferenceResult> replies = new ArrayDeque<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int turn;
    private volatile int call;

    void begin(int turnNumber, List<Reply> script) {
      turn = turnNumber;
      call = 0;
      replies.clear();
      script.forEach(reply -> replies.add(reply.result()));
    }

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      call++;
      seen.add(new Seen(turn, call, request));
      InferenceResult next = replies.poll();
      if (next == null) {
        throw new IllegalStateException(
            "the script for turn %d ran out of replies at call %d".formatted(turn, call));
      }
      return next;
    }
  }

  private record Seen(int turn, int call, InferenceRequest request) {}

  private Report replay(
      Optional<Watched> watched, Customizer<DirectHarnessConfig<String>> settings) {
    Scripted model = new Scripted();
    InMemoryDirectBackend backend =
        new InMemoryDirectBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
    AgentId agent = AgentId.random();
    try (DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(backend)
                    .provider(ProviderId.of("test"), model)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build()))) {
      DirectHarness<String, String> harness =
          factory.<String>create(
              TYPE,
              c -> {
                c.systemPrompt(SYSTEM_PROMPT);
                c.inputRenderer(said -> List.of(new Block.Text(said)));
                c.inference(in -> in.provider("test").model(MODEL));
                c.tool(lookup(), t -> t.approver(Approver.allow()));
                c.summarizer(
                    chapter ->
                        "summary of turns %d..%d"
                            .formatted(chapter.from().value(), chapter.through().value()));
                watched.ifPresent(c::chapterPolicy);
                settings.customize(c);
              });
      for (int i = 0; i < turns.size(); i++) {
        int turnNumber = i + 1;
        ScriptedTurn scripted = turns.get(i);
        int before = watched.map(w -> w.asked.get()).orElse(0);
        model.begin(turnNumber, scripted.replies());
        Outcome<String> outcome = harness.ask(agent, scripted.input());
        if (!(outcome instanceof Outcome.Answered<String>)) {
          throw new IllegalStateException(
              "turn %d was not answered: %s".formatted(turnNumber, outcome));
        }
        watched.ifPresent(w -> settle(w, backend, agent, before, turnNumber));
      }
    }
    return Report.of(model.seen.stream().map(ContextReplay::row).toList());
  }

  /** Waits, with a deadline, until the keeper has finished what the policy asked of it. */
  private static void settle(
      Watched watched, InMemoryDirectBackend backend, AgentId agent, int before, int turn) {
    await()
        .alias("the chapter keeper after turn " + turn)
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(5))
        .atMost(PATIENCE_PER_TURN)
        .until(
            () -> {
              if (watched.asked.get() <= before) {
                return false;
              }
              TurnId end = watched.furthestEnd.get();
              boolean closed =
                  end == null
                      || backend
                          .chapters()
                          .closedThrough(TYPE, agent)
                          .filter(through -> through.compareTo(end) >= 0)
                          .isPresent();
              return closed && backend.chapters().unsummarized(TYPE, agent).isEmpty();
            });
  }

  private static Tool<Lookup> lookup() {
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
        return "looks a record up by key";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text(LOOKUP_RESULT)));
      }
    };
  }

  /** The input of {@code lookup}. */
  record Lookup(String key) {}

  /**
   * What the model was sent on one call. Sizes are in characters; {@code summarizedThrough} is the
   * last turn id any summary covers (zero for none) and {@code firstVerbatim} the first turn id
   * shown in full; turn ids are the engine's, not the replay's turn numbers.
   */
  record Call(
      int turn,
      int call,
      int summaries,
      int tail,
      int exchanges,
      int summarizedThrough,
      int firstVerbatim,
      int systemChars,
      int summaryChars,
      int tailChars,
      int memoryChars,
      int stateChars,
      int activeChars,
      int ambientChars,
      int stablePrefix,
      String text) {

    int chars() {
      return text.length();
    }

    private Call withStablePrefix(int prefix) {
      return new Call(
          turn,
          call,
          summaries,
          tail,
          exchanges,
          summarizedThrough,
          firstVerbatim,
          systemChars,
          summaryChars,
          tailChars,
          memoryChars,
          stateChars,
          activeChars,
          ambientChars,
          prefix,
          text);
    }
  }

  private static String lines(List<String> texts) {
    StringBuilder out = new StringBuilder();
    texts.forEach(text -> out.append(text).append('\n'));
    return out.toString();
  }

  private static Call row(Seen seen) {
    InferenceRequest request = seen.request();
    InferenceContext context = request.context();
    String system = request.systemPrompt().value() + "\n";
    String summaries = lines(context.summaries().stream().map(Summary::text).toList());
    String tail = Transcripts.render(context.tail());
    String memory =
        lines(context.memory().stream().map(m -> Transcripts.text(m.content())).toList());
    String state = lines(context.state().stream().map(s -> Transcripts.text(s.content())).toList());
    String active = Transcripts.render(List.of(context.activeTurn()));
    String ambient =
        lines(context.ambient().stream().map(a -> Transcripts.text(a.content())).toList());
    int through =
        context.summaries().stream()
            .mapToInt(summary -> (int) summary.chapter().through().value())
            .max()
            .orElse(0);
    int firstVerbatim =
        (int) context.turns().stream().map(Turn::id).mapToLong(TurnId::value).min().orElseThrow();
    return new Call(
        seen.turn(),
        seen.call(),
        context.summaries().size(),
        context.tail().size(),
        context.activeTurn().exchanges().size(),
        through,
        firstVerbatim,
        system.length(),
        summaries.length(),
        tail.length(),
        memory.length(),
        state.length(),
        active.length(),
        ambient.length(),
        0,
        system + summaries + tail + memory + state + active + ambient);
  }

  /** The calls of one run, in order. */
  static final class Report {
    private final List<Call> calls;

    private Report(List<Call> calls) {
      this.calls = calls;
    }

    private static Report of(List<Call> raw) {
      List<Call> calls = new ArrayList<>(raw.size());
      String previous = "";
      for (Call call : raw) {
        calls.add(call.withStablePrefix(sharedPrefix(previous, call.text())));
        previous = call.text();
      }
      return new Report(List.copyOf(calls));
    }

    private static int sharedPrefix(String a, String b) {
      int limit = Math.min(a.length(), b.length());
      int i = 0;
      while (i < limit && a.charAt(i) == b.charAt(i)) {
        i++;
      }
      return i;
    }

    /** Every call, in order. */
    List<Call> calls() {
      return calls;
    }

    /** The calls made on turns after this one. */
    List<Call> after(int turn) {
      return calls.stream().filter(call -> call.turn() > turn).toList();
    }

    /** The calls made during one turn. */
    List<Call> onTurn(int turn) {
      return calls.stream().filter(call -> call.turn() == turn).toList();
    }

    /** Calls, characters sent and characters in the stable prefix, over every call. */
    Totals totals() {
      return Totals.of(calls);
    }

    /** The same, over the calls made on turns after this one. */
    Totals totalsAfter(int turn) {
      return Totals.of(after(turn));
    }

    /** A fixed-width table, one row per call. */
    String table() {
      StringBuilder out = new StringBuilder();
      out.append(
          "turn call  sums  tail  exch  sumThru  1stVerb |   system  summary     tail   memory"
              + "    state   active  ambient |    total   stable  share\n");
      for (Call c : calls) {
        out.append(
            "%4d %4d %5d %5d %5d %8d %8d | %8d %8d %8d %8d %8d %8d %8d | %8d %8d %5.1f%%%n"
                .formatted(
                    c.turn(),
                    c.call(),
                    c.summaries(),
                    c.tail(),
                    c.exchanges(),
                    c.summarizedThrough(),
                    c.firstVerbatim(),
                    c.systemChars(),
                    c.summaryChars(),
                    c.tailChars(),
                    c.memoryChars(),
                    c.stateChars(),
                    c.activeChars(),
                    c.ambientChars(),
                    c.chars(),
                    c.stablePrefix(),
                    100.0 * c.stablePrefix() / Math.max(1, c.chars())));
      }
      return out.toString();
    }
  }

  /** Totals over some calls. */
  record Totals(int calls, long chars, long stable) {

    /** The characters that differ from the call before: what a provider would read uncached. */
    long changed() {
      return chars - stable;
    }

    private static Totals of(List<Call> calls) {
      return new Totals(
          calls.size(),
          calls.stream().mapToLong(Call::chars).sum(),
          calls.stream().mapToLong(Call::stablePrefix).sum());
    }

    /** The share of what was sent that was unchanged from the call before, from 0 to 1. */
    double ratio() {
      return chars == 0 ? 0.0 : (double) stable / chars;
    }

    /** One line, labelled. */
    String line(String label) {
      return "%-14s calls %4d   sent %10d chars   changed (uncached) %10d chars   stable share %5.1f%%"
          .formatted(label, calls, chars, changed(), 100.0 * ratio());
    }
  }
}
