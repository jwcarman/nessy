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
package org.jwcarman.nessy.memory.summarizing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.AmbientContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelReplies;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A memory that COMPRESSES old history instead of dropping it.
 *
 * <p>Everything else in Nessy that bounds a context throws information away — {@code
 * TranscriptMemory.recent} drops the oldest until the rest fit, {@code Context.keepRecent} drops
 * all but the last few, {@code elideToolResults} replaces old tool output with a marker. Each is
 * honestly lossy and right sometimes. None preserves what happened, so a long-running agent either
 * carries everything or forgets the customer's name.
 *
 * <p><b>The transcript is never touched.</b> This is a sidecar: one row per agent saying
 * "everything through sequence N is in this paragraph", and recall stitches that onto whatever came
 * after.
 *
 * <pre>{@code
 * recall = the summary  +  transcript.recallAfter(agent, coversThrough)
 * }</pre>
 *
 * <p>The covered messages are never READ, which is the saving. A coverage COUNT could only be
 * applied by loading the whole transcript and discarding the front of it — pointless for a whole
 * memory and outright wrong for one that returns a moving window.
 *
 * <h2>A summary is not history</h2>
 *
 * {@code HistoryMessage} is sealed to {@code UserMessage}, {@code ExchangeMessage} and {@code
 * AnswerMessage}. Nobody SAID a summary — it is derived, like a notebook index or a plan, and both
 * of those are already {@link AmbientMessage}. So it cannot be stored through {@link #remember} at
 * all; the type system is what says it belongs beside the transcript rather than in it.
 *
 * <h2>Compressing happens off the writing thread</h2>
 *
 * {@link #remember} notices that an agent has outrun its summary and hands the work to an {@link
 * Executor}. It never calls a model itself: a vendor call on the write path would make whichever
 * turn happened to cross the threshold mysteriously slow, and would turn a model outage into a
 * turn-completion problem.
 *
 * <p><b>A late summary is not a wrong answer.</b> Until the work finishes, recall returns the
 * previous summary plus more verbatim history than intended — a context slightly larger than
 * planned, which costs tokens, not one that is wrong. That is what makes it safe to do out here,
 * and why a failure needs no recovery: nothing was lost, and the next write will notice again.
 */
public final class SummarizingMemory implements Memory {

  private static final Logger LOG = LoggerFactory.getLogger(SummarizingMemory.class);

  /**
   * What an adapter sees, so it can label the background the way its vendor likes — XML tags for
   * one, a heading for another, nothing at all for a third.
   *
   * <p>Private, like {@code PlanTools} and {@code NotebookTools} keep theirs. Nothing outside needs
   * to name it, and a string nobody asked for is not worth putting on the API surface.
   */
  private static final String KIND = "summary";

  /**
   * The default instruction, written for REPETITION.
   *
   * <p>Public because {@link SummarizingMemoryConfig#systemPrompt} exists and starting from this is
   * usually better than starting from nothing:
   *
   * <pre>{@code
   * config.systemPrompt(SummarizingMemory.SUMMARIZE + "\n\nAlso keep every order number.")
   * }</pre>
   *
   * <p><b>Read this before replacing it.</b> From the second summary onward the input is the
   * PREVIOUS summary plus what has arrived since, so every generation is lossy over the last and a
   * fact mentioned once decays geometrically — an agent does not forget suddenly, it fades. Asking
   * for durable specifics rather than a retelling is what slows that down. A prompt that says
   * "summarize the conversation" produces, after five generations, a paragraph about there having
   * been a conversation.
   */
  public static final String SUMMARIZE =
      """
      You are compressing the earlier part of a conversation so it can be carried forward.

      Your output will later be fed back to you along with newer messages and compressed again,
      so write what survives repetition:

      - names, identifiers, and specific values that were established
      - decisions made, and what they were made for
      - commitments and obligations, in either direction
      - questions raised that are still open

      Do not narrate, and do not describe the conversation as a conversation. Write only what a
      reader would need in order to continue it. Keep exact values: a name, a number or an
      identifier is worth more than a sentence about it.
      """;

  private final TranscriptMemory transcript;
  private final Summaries summaries;
  private final AgentType agentType;
  private final Model model;
  private final Executor executor;
  private final long summarizeAfter;
  private final int keepVerbatim;
  private final int maxTokens;
  private final String systemPrompt;
  private final Clock clock;

  /** Messages seen since this agent was last considered, so most writes cost no query at all. */
  private final ConcurrentHashMap<AgentId, AtomicInteger> sinceChecked = new ConcurrentHashMap<>();

  /** Agents already being compressed, so a burst of writes fires the work once. */
  private final Set<AgentId> compressing = ConcurrentHashMap.newKeySet();

  private SummarizingMemory(Configured configured) {
    this.transcript = configured.transcript;
    this.summaries = new Summaries(configured.dataSource);
    this.agentType = configured.agentType;
    this.model = configured.model;
    this.executor = configured.executor;
    this.summarizeAfter = configured.summarizeAfter;
    this.keepVerbatim = configured.keepVerbatim;
    this.maxTokens = configured.maxTokens;
    this.systemPrompt = configured.systemPrompt;
    this.clock = configured.clock;
  }

  public static SummarizingMemory create(Consumer<SummarizingMemoryConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Configured configured = new Configured();
    customizer.accept(configured);
    configured.check();
    return new SummarizingMemory(configured);
  }

  /** The summary, if there is one, then everything said after it. */
  @Override
  public Context recall(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return summaries
        .find(agentType, agentId)
        .map(found -> assemble(agentId, found))
        .orElseGet(() -> transcript.recall(agentId));
  }

  private Context assemble(AgentId agentId, Summaries.Summary summary) {
    List<ContextMessage> after =
        transcript.recallAfter(agentId, summary.coversThrough()).messages();
    List<ContextMessage> messages = new ArrayList<>(after.size() + 1);
    messages.add(
        new AmbientMessage(KIND, List.<AmbientContentBlock>of(new TextBlock(summary.text()))));
    messages.addAll(after);
    return Context.of(messages);
  }

  /**
   * Remembers, then MAY hand off compression — never performs it.
   *
   * <p>The common write costs one in-memory increment and nothing else. Only every {@code
   * keepVerbatim} messages does this look at the database at all, and only then can it decide there
   * is enough new history to be worth a model call.
   */
  @Override
  public void remember(AgentId agentId, HistoryMessage message) {
    transcript.remember(agentId, message);
    if (sinceChecked.computeIfAbsent(agentId, id -> new AtomicInteger()).incrementAndGet()
        < keepVerbatim) {
      return;
    }
    sinceChecked.get(agentId).set(0);
    considerCompressing(agentId);
  }

  private void considerCompressing(AgentId agentId) {
    long covered =
        summaries.find(agentType, agentId).map(Summaries.Summary::coversThrough).orElse(0L);
    long latest = transcript.lastSeq(agentId);
    if (latest - covered <= summarizeAfter) {
      return;
    }
    // One at a time per agent: a burst of writes past the threshold should cost one model call,
    // not one per message.
    if (!compressing.add(agentId)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              compress(agentId, covered, latest);
            } finally {
              compressing.remove(agentId);
            }
          });
    } catch (RuntimeException rejected) {
      compressing.remove(agentId);
      LOG.warn("[summary] could not hand off compression for {}", agentId.value(), rejected);
    }
  }

  /**
   * The model call, on somebody else's thread.
   *
   * <p>Failure writes nothing: the previous summary stands, the agent carries more verbatim history
   * for a while, and the next write past the threshold will try again. Nothing is ever deleted to
   * make a summary, so the worst case is a context larger than intended.
   */
  private void compress(AgentId agentId, long covered, long latest) {
    long throughSeq = latest - keepVerbatim;
    if (throughSeq <= covered) {
      return;
    }
    List<ContextMessage> uncovered = transcript.recallAfter(agentId, covered).messages();
    int compressible = (int) (throughSeq - covered);
    if (compressible > uncovered.size()) {
      return;
    }
    List<ContextMessage> input = new ArrayList<>(compressible + 1);
    // The PREVIOUS summary, never the whole transcript: this is what bounds the cost.
    summaries
        .find(agentType, agentId)
        .ifPresent(
            found ->
                input.add(
                    new AmbientMessage(
                        KIND, List.<AmbientContentBlock>of(new TextBlock(found.text())))));
    input.addAll(uncovered.subList(0, compressible));

    String summary;
    try {
      summary = ask(input);
    } catch (RuntimeException failed) {
      LOG.warn(
          "[summary] could not compress {}; the previous summary stands", agentId.value(), failed);
      return;
    }
    if (summary.isBlank()) {
      LOG.warn("[summary] {} produced an empty summary; the previous one stands", agentId.value());
      return;
    }
    summaries.advance(agentType, agentId, throughSeq, summary, clock.instant());
    LOG.info("[summary] compressed {} messages for {}", compressible, agentId.value());
  }

  /**
   * One model call, with NO TOOLS.
   *
   * <p>Deliberate: a summarizer reads whatever an agent has been told, including anything a user
   * wrote, and something that reads untrusted content must not be able to act on it. The empty tool
   * list is asserted in a test so a refactor cannot quietly hand it capability.
   */
  private String ask(List<ContextMessage> input) {
    ModelResult result =
        ModelReplies.drain(
            model.stream(
                new ModelRequest(Context.of(input), systemPrompt, maxTokens, List.of(), Set.of())),
            event -> {});
    if (result instanceof ModelResult.Answered answered) {
      return answered.message().content().stream()
          .filter(TextBlock.class::isInstance)
          .map(block -> ((TextBlock) block).text())
          .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
    // A refusal, or a stop that is not an answer, is not a summary. Treated as a failure so the
    // previous one stands rather than recording something that is not one.
    throw new IllegalStateException("the summarizer did not answer: " + result);
  }

  /** Everything the transcript holds for this agent, and the summary of it. */
  @Override
  public void forget(AgentId agentId) {
    transcript.forget(agentId);
    summaries.forget(agentType, agentId);
    sinceChecked.remove(agentId);
  }

  private static final class Configured implements SummarizingMemoryConfig {

    private TranscriptMemory transcript;
    private DataSource dataSource;
    private AgentType agentType;
    private Model model;
    private Executor executor;
    private long summarizeAfter = 100;
    private int keepVerbatim = 20;
    private int maxTokens = 1024;
    private String systemPrompt = SUMMARIZE;
    private Clock clock = Clock.systemUTC();

    void check() {
      if (transcript == null || dataSource == null || agentType == null || model == null) {
        throw new IllegalStateException(
            "a summarizing memory needs a transcript, a dataSource, an agentType and a model");
      }
      if (executor == null) {
        throw new IllegalStateException(
            "a summarizing memory needs an executor: compressing must not happen on the thread that"
                + " is writing");
      }
      if (keepVerbatim < 1) {
        throw new IllegalArgumentException("keepVerbatim must be at least 1, was " + keepVerbatim);
      }
      if (summarizeAfter <= keepVerbatim) {
        // Otherwise the threshold is reached while everything past the summary is still inside the
        // verbatim window, and there would never be anything to compress.
        throw new IllegalArgumentException(
            "summarizeAfter ("
                + summarizeAfter
                + ") must be greater than keepVerbatim ("
                + keepVerbatim
                + "), or there is never anything to compress");
      }
    }

    @Override
    public SummarizingMemoryConfig transcript(TranscriptMemory transcript) {
      this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
      return this;
    }

    @Override
    public SummarizingMemoryConfig dataSource(DataSource dataSource) {
      this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
      return this;
    }

    @Override
    public SummarizingMemoryConfig agentType(AgentType agentType) {
      this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
      return this;
    }

    @Override
    public SummarizingMemoryConfig model(Model model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    @Override
    public SummarizingMemoryConfig executor(Executor executor) {
      this.executor = Objects.requireNonNull(executor, "executor must not be null");
      return this;
    }

    @Override
    public SummarizingMemoryConfig summarizeAfter(long messages) {
      this.summarizeAfter = messages;
      return this;
    }

    @Override
    public SummarizingMemoryConfig keepVerbatim(int messages) {
      this.keepVerbatim = messages;
      return this;
    }

    @Override
    public SummarizingMemoryConfig maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    @Override
    public SummarizingMemoryConfig systemPrompt(String systemPrompt) {
      Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
      if (systemPrompt.isBlank()) {
        throw new IllegalArgumentException("a summarizer needs an instruction, not an empty one");
      }
      this.systemPrompt = systemPrompt;
      return this;
    }

    @Override
    public SummarizingMemoryConfig clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }
  }
}
