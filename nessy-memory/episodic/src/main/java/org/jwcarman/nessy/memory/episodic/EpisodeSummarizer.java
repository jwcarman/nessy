package org.jwcarman.nessy.memory.episodic;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.memory.summarizing.SummaryObservation;
import org.jwcarman.nessy.memory.summarizing.Transcripts;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the summary of each episode once it has closed.
 *
 * <p><b>In the background, and opportunistically.</b> Attached to a harness through {@link
 * #listener()}, it hears every turn end, asks the store whether any of that agent's closed episodes
 * are still unsummarised (one query), and if so takes the {@code episode} lease for the agent and
 * summarises them oldest first: the model is shown the episode's turns and asked for the summary,
 * and what it writes is stored -- embedded, when the store has an embedder. Several processes may
 * hear the same agent; the lease sees it is not done twice at once. No turn ever waits for any of
 * this, and a missed event costs nothing but delay: the next turn end asks again.
 */
public class EpisodeSummarizer {

  private static final Logger LOG = LoggerFactory.getLogger(EpisodeSummarizer.class);

  public static final String PROMPT =
      """
      You are writing the summary of one episode of a longer conversation, so that it can stand \
      in for the episode's turns from now on. You are shown the episode's turns; the rest of the \
      conversation is not your concern.

      Write a title on the first line: at most eight words, naming what the episode turned out \
      to be about, which may differ from what it was called when it began. Then a blank line, \
      then the summary.

      Keep what a reader would need if this episode came up again:
      - what was being done, and how it ended
      - names, identifiers and specific values that were established
      - decisions made, and what they were made for
      - commitments and obligations, in either direction
      - questions raised that were left open

      Do not narrate, and do not describe the conversation as a conversation. Keep exact values: \
      a name, a number or an identifier is worth more than a sentence about it.""";

  /** What a summariser is made of; see {@link EpisodeSummarizer#create(Consumer)}. */
  public static final class Config {
    private AgentType agentType;
    private JdbcEpisodes episodes;
    private TurnHistories histories;
    private Leases leases;
    private InferenceProvider provider;
    private InferenceOptions options;
    private Duration leaseTtl = Duration.ofMinutes(2);
    private ObservationRegistry observations = ObservationRegistry.NOOP;

    private Config() {}

    /** Whose stories. */
    public Config agentType(AgentType agentType) {
      this.agentType = agentType;
      return this;
    }

    /** Where episodes are kept -- the same store the harness reads through. */
    public Config episodes(JdbcEpisodes episodes) {
      this.episodes = episodes;
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

    /** How long one agent's summaries may take before another process may assume this one died. */
    public Config leaseTtl(Duration leaseTtl) {
      this.leaseTtl = leaseTtl;
      return this;
    }

    /**
     * Where to report: each summary becomes a {@code nessy.summary} span with the model call inside
     * it as a {@code chat} span. The summariser observes the provider it is given, and one already
     * observed is used as it is.
     */
    public Config observations(ObservationRegistry observations) {
      this.observations = Objects.requireNonNull(observations, "observations must not be null");
      return this;
    }
  }

  public static EpisodeSummarizer create(Consumer<Config> customizer) {
    Config config = new Config();
    customizer.accept(config);
    return new EpisodeSummarizer(config);
  }

  private final AgentType agentType;
  private final JdbcEpisodes episodes;
  private final TurnHistories histories;
  private final Leases leases;
  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final Duration leaseTtl;
  private final SummaryObservation observation;

  private EpisodeSummarizer(Config config) {
    this.agentType = Objects.requireNonNull(config.agentType, "agentType is required");
    this.episodes = Objects.requireNonNull(config.episodes, "episodes are required");
    this.histories = Objects.requireNonNull(config.histories, "histories are required");
    this.leases = Objects.requireNonNull(config.leases, "leases are required");
    Objects.requireNonNull(config.provider, "inference(provider, options) is required");
    this.options =
        Objects.requireNonNull(config.options, "inference(provider, options) is required");
    this.leaseTtl = Objects.requireNonNull(config.leaseTtl, "leaseTtl must not be null");
    this.provider = ObservedInferenceProvider.wrap(config.provider, config.observations);
    this.observation = new SummaryObservation(config.observations, "episode", agentType);
  }

  /**
   * The listener to attach to the harness: hears this type's turns end, on a thread of its own per
   * event, because a summary is a model call and the engine's narration thread must not wait.
   */
  public AgentEventListener listener() {
    return AgentEventListener.of(
            c -> c.agentType(agentType).onTurnEnded((_, agentId, _) -> summarizeIfDue(agentId)))
        .async();
  }

  /** The check, then the work under the lease; public so an application can also ask outright. */
  public void summarizeIfDue(AgentId agentId) {
    if (!episodes.unsummarized(agentId).isEmpty()) {
      observation.observe(
          agentId,
          () -> {
            String[] outcome = {"lease-refused"};
            leases.tryRun(
                "episode",
                agentId.value().toString(),
                leaseTtl,
                () -> outcome[0] = summarizeAll(agentId));
            return outcome[0];
          });
    }
  }

  /**
   * Under the lease: read again, because another process may have got here first. Says how it came
   * out, for the span: what the last episode attempted came to.
   */
  private String summarizeAll(AgentId agentId) {
    String outcome = "nothing";
    for (Episode episode : episodes.unsummarized(agentId)) {
      outcome = summarize(agentId, episode);
      if (!"written".equals(outcome)) {
        // Oldest first, and in order: a later episode summarised before an earlier one would
        // leave a hole the store cannot show past.
        return outcome;
      }
    }
    return outcome;
  }

  private String summarize(AgentId agentId, Episode episode) {
    List<Turn> turns =
        histories.forAgent(agentType, agentId).turnsFrom(episode.from().value()).stream()
            .filter(turn -> turn.id().value() <= episode.through().value())
            .filter(Turn::complete)
            .toList();
    if (turns.isEmpty()) {
      // Nothing was said in it: a boundary drawn and redrawn. Close the gap with the title.
      return episodes.summarize(agentId, episode.number(), episode.title()) ? "written" : "nothing";
    }
    // The episode's turns, the shape the engine shows a model anyway, and then the ask: every turn
    // is complete, so without it the conversation would end on the assistant's own words.
    List<Turn> shown = new ArrayList<>(turns);
    shown.add(
        Transcripts.ask(
            turns.getLast(),
            "Write the title and then the summary of this episode, opened as '"
                + episode.title()
                + "', now."));
    InferenceResult result =
        provider.infer(
            new InferenceRequest(
                new SystemPrompt(PROMPT),
                new InferenceContext(List.of(), shown, List.of()),
                List.of(),
                options));
    if (!(result instanceof InferenceResult.Answer(var blocks, var _))) {
      // Not an error to anybody: the episode stays unsummarised, and the next turn end tries again.
      LOG.warn(
          "[{}] could not summarise episode {} of agent {}: {}",
          agentType.value(),
          episode.number(),
          agentId.value(),
          result);
      return "fault";
    }
    Titled titled = Titled.parse(Transcripts.text(blocks));
    String summary = titled.summary();
    if (summary.isBlank()) {
      LOG.warn(
          "[{}] the summary of episode {} of agent {} was empty; left for next time",
          agentType.value(),
          episode.number(),
          agentId.value());
      return "empty";
    }
    if (!episodes.summarize(agentId, episode.number(), titled.title(), summary)) {
      // Another process wrote it while this one was asking the model.
      return "nothing";
    }
    LOG.info(
        "[{}] summarised episode {} (turns {}..{}) of agent {} as '{}'",
        agentType.value(),
        episode.number(),
        episode.from().value(),
        episode.through().value(),
        agentId.value(),
        titled.title() == null ? episode.title() : titled.title());
    return "written";
  }

  /**
   * What the model wrote, split as asked: the first line is the title, the rest the summary. A
   * reply of one line is all summary and no title, so a model that ignored the ask retitles
   * nothing. A "Title:" label, quotes and markdown dressing around the title are forgiven.
   */
  record Titled(String title, String summary) {

    private static final Pattern LABEL = Pattern.compile("^(?i)title\\s*:\\s*");
    private static final Pattern LEADING_DRESSING = Pattern.compile("^[\\s*_\"'`#]+");
    private static final Pattern TRAILING_DRESSING = Pattern.compile("[\\s*_\"'`#]+$");

    static Titled parse(String text) {
      String whole = text.strip();
      String[] lines = whole.split("\\R", 2);
      if (lines.length < 2) {
        return new Titled(null, whole);
      }
      String title = LABEL.matcher(lines[0].strip()).replaceFirst("");
      title = LEADING_DRESSING.matcher(title).replaceFirst("");
      title = TRAILING_DRESSING.matcher(title).replaceFirst("");
      String summary = lines[1].strip();
      if (title.isBlank() || summary.isBlank()) {
        return new Titled(null, whole);
      }
      return new Titled(title, summary);
    }
  }
}
