package org.jwcarman.nessy.memory.summarizing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Summarises the head of a story once it has grown past what the model is shown.
 *
 * <p>The engine shows a model the summary and then the last {@code maxTail} turns after it. Once
 * more than {@code maxTail} turns follow the summary, the oldest of them are no longer shown at all
 * -- that is when they are folded in: the model is shown the summary so far and those turns, and
 * what it writes replaces the summary, now covering the story through the last of them and leaving
 * the newest {@code minTail} verbatim. One summary per agent, always.
 *
 * <p><b>In the background, and opportunistically.</b> Attached to a harness through {@link
 * #listener()}, it hears every turn end, counts that agent's unsummarised turns (one query, nothing
 * loaded), and when they exceed {@code maxTail} takes the {@code summary} lease for the agent and
 * summarises. Several processes may hear the same agent; the lease sees it is not summarised twice
 * at once. No turn ever waits for any of this, and a missed event costs nothing but delay: the next
 * turn end counts again.
 */
public class HeadSummarizer {

  private static final Logger LOG = LoggerFactory.getLogger(HeadSummarizer.class);

  public static final String PROMPT =
      """
      You are compressing the earlier part of a conversation so it can be carried forward. \
      You may be shown a summary of the part before this; fold it in, so that what you write \
      stands alone as the summary of everything so far. It will be shown in place of all of \
      it, before the newer turns.

      Keep what a reader would need in order to continue:
      - names, identifiers and specific values that were established
      - decisions made, and what they were made for
      - commitments and obligations, in either direction
      - questions raised that are still open

      Do not narrate, and do not describe the conversation as a conversation. Keep exact values: \
      a name, a number or an identifier is worth more than a sentence about it.""";

  /** How the head is rendered for the model: one line per thing that happened. */
  static String transcript(List<Turn> turns) {
    StringBuilder out = new StringBuilder();
    for (Turn turn : turns) {
      out.append("user: ").append(text(turn.observation().blocks())).append('\n');
      for (Exchange exchange : turn.exchanges()) {
        String said = text(exchange.request());
        if (!said.isBlank()) {
          out.append("assistant: ").append(said).append('\n');
        }
        exchange
            .calls()
            .forEach(
                call ->
                    out.append("assistant called ")
                        .append(call.name().value())
                        .append(' ')
                        .append(call.arguments())
                        .append('\n'));
        exchange
            .outcomes()
            .forEach(
                outcome ->
                    out.append("tool: ")
                        .append(
                            switch (outcome) {
                              case ToolOutcome.Succeeded(var _, var blocks) -> text(blocks);
                              case ToolOutcome.Failed(var _, String message) ->
                                  "failed: " + message;
                              case ToolOutcome.Denied(var _, String reason) -> "denied: " + reason;
                            })
                        .append('\n'));
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) ->
            out.append("assistant: ").append(text(blocks)).append('\n');
        case TurnResult.Failed _ -> out.append("(the assistant could not answer)\n");
        case TurnResult.Refused _ -> out.append("(the assistant declined to answer)\n");
        case null -> {
          // Still under way; never summarised.
        }
      }
    }
    return out.toString();
  }

  private static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(
            block ->
                switch (block) {
                  case Block.Text(String text) -> text;
                  case Block.Commentary(String text) -> text;
                  case Block.Provider _, Block.ToolCall _ -> "";
                })
        .filter(text -> !text.isEmpty())
        .collect(Collectors.joining("\n"));
  }

  /** What a summariser is made of; see {@link HeadSummarizer#create(Consumer)}. */
  public static final class Config {
    private AgentType agentType;
    private JdbcSummaries summaries;
    private TurnHistories histories;
    private Leases leases;
    private InferenceProvider provider;
    private InferenceOptions options;
    private int maxTail = 20;
    private int minTail = 8;
    private Duration leaseTtl = Duration.ofMinutes(2);

    private Config() {}

    /** Whose stories. */
    public Config agentType(AgentType agentType) {
      this.agentType = agentType;
      return this;
    }

    /** Where summaries are kept -- the same store the harness reads through. */
    public Config summaries(JdbcSummaries summaries) {
      this.summaries = summaries;
      return this;
    }

    /** The stories, from the factory. */
    public Config histories(TurnHistories histories) {
      this.histories = histories;
      return this;
    }

    /** Whose turn it is. */
    public Config leases(Leases leases) {
      this.leases = leases;
      return this;
    }

    /** What writes the summary: typically the same provider and model the agent talks to. */
    public Config inference(InferenceProvider provider, InferenceOptions options) {
      this.provider = provider;
      this.options = options;
      return this;
    }

    /**
     * The thresholds. {@code maxTail} must match the harness's context ({@code ctx.maxTail}): it is
     * the number of turns the model is shown after the summaries, and the head is summarised once
     * more than that many have accumulated. {@code minTail} is how many stay verbatim after a cut.
     */
    public Config tail(int maxTail, int minTail) {
      this.maxTail = maxTail;
      this.minTail = minTail;
      return this;
    }

    /** How long one summary may take before another process may assume this one died. */
    public Config leaseTtl(Duration leaseTtl) {
      this.leaseTtl = leaseTtl;
      return this;
    }
  }

  public static HeadSummarizer create(Consumer<Config> customizer) {
    Config config = new Config();
    customizer.accept(config);
    return new HeadSummarizer(config);
  }

  private final AgentType agentType;
  private final JdbcSummaries summaries;
  private final TurnHistories histories;
  private final Leases leases;
  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final int maxTail;
  private final int minTail;
  private final Duration leaseTtl;

  private HeadSummarizer(Config config) {
    this.agentType = Objects.requireNonNull(config.agentType, "agentType is required");
    this.summaries = Objects.requireNonNull(config.summaries, "summaries are required");
    this.histories = Objects.requireNonNull(config.histories, "histories are required");
    this.leases = Objects.requireNonNull(config.leases, "leases are required");
    this.provider =
        Objects.requireNonNull(config.provider, "inference(provider, options) is required");
    this.options =
        Objects.requireNonNull(config.options, "inference(provider, options) is required");
    if (config.minTail < 1 || config.maxTail <= config.minTail) {
      throw new IllegalArgumentException(
          "tail(maxTail, minTail) needs 1 <= minTail < maxTail, got (%d, %d)"
              .formatted(config.maxTail, config.minTail));
    }
    this.maxTail = config.maxTail;
    this.minTail = config.minTail;
    this.leaseTtl = Objects.requireNonNull(config.leaseTtl, "leaseTtl must not be null");
  }

  /**
   * The listener to attach to the harness: hears this type's turns end, on a thread of its own per
   * event, because a summary is a model call and the engine's narration thread must not wait for
   * one.
   */
  public AgentEventListener listener() {
    return AgentEventListener.of(
            c -> c.agentType(agentType).onTurnEnded((_, agentId, _) -> summarizeIfDue(agentId)))
        .async();
  }

  /** The check, then the work under the lease; public so an application can also ask outright. */
  public void summarizeIfDue(AgentId agentId) {
    TurnHistory history = histories.forAgent(agentType, agentId);
    if (history.turnsAfter(through(agentId)) > maxTail) {
      leases.tryRun("summary", agentId.value().toString(), leaseTtl, () -> summarize(agentId));
    }
  }

  /** The last summarised turn, or zero: a TurnId cannot say "none", so this is a number. */
  private long through(AgentId agentId) {
    return summaries.summarizedThrough(agentId).map(TurnId::value).orElse(0L);
  }

  /** Under the lease: read again, because another process may have got here first. */
  private void summarize(AgentId agentId) {
    TurnHistory history = histories.forAgent(agentType, agentId);
    List<Turn> head = history.turnsFrom(through(agentId) + 1);
    if (head.size() <= maxTail) {
      return;
    }
    // The oldest turns, leaving minTail verbatim -- and never a turn still under way, which can
    // only be the last and is kept by minTail >= 1.
    List<Turn> cut =
        head.subList(0, head.size() - minTail).stream().filter(Turn::complete).toList();
    if (cut.isEmpty()) {
      return;
    }
    // The summary so far, then the turns being folded in: the shape the engine shows a model
    // anyway, so every adapter already renders it.
    List<Summary> soFar = summaries.forAgent(agentId);
    InferenceResult result =
        provider.infer(
            new InferenceRequest(
                new SystemPrompt(PROMPT),
                new InferenceContext(soFar, cut, List.of()),
                List.of(),
                options));
    if (!(result instanceof InferenceResult.Answer(var blocks))) {
      // Not an error to anybody: the summary stays as it was, and the next turn end tries again.
      LOG.warn("[{}] could not summarise agent {}: {}", agentType.value(), agentId.value(), result);
      return;
    }
    String summary = text(blocks);
    if (summary.isBlank()) {
      LOG.warn(
          "[{}] the summary of agent {} was empty; kept what there was",
          agentType.value(),
          agentId.value());
      return;
    }
    TurnId from = soFar.isEmpty() ? cut.getFirst().id() : soFar.getFirst().from();
    summaries.replace(agentId, Summary.text(from, cut.getLast().id(), summary));
    LOG.info(
        "[{}] folded turns {}..{} into the summary of agent {}",
        agentType.value(),
        cut.getFirst().id().value(),
        cut.getLast().id().value(),
        agentId.value());
  }
}
