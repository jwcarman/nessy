package org.jwcarman.nessy.memory.episodic;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.Embedding;
import org.jwcarman.nessy.memory.summarizing.Transcripts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An agent's episodes in {@code nessy_episode}, and the {@link Summarizer} that shows the relevant
 * ones.
 *
 * <p><b>What is shown.</b> The summarised episodes from the start of the story up to the first one
 * that is open or not yet summarised are the candidates: everything after that point is still
 * turns, and the engine's tail begins there ({@link #summarizedThrough}). Of the candidates, at
 * most {@code shown} are sent. The most recent is always one of them, because the story just left
 * it; the rest are chosen by relevance to the turn being answered when an {@link Embedder} was
 * given, and by recency when not. Chosen or not, they are sent in story order, and a gap between
 * two of them is an episode the model was not shown.
 *
 * <p><b>Relevance is cosine similarity</b> between the summary's embedding, written when the
 * summary was, and the embedding of the observation being answered -- one embedding call per model
 * call, against a handful of vectors read with the rows. That is a scan in Java over one agent's
 * episodes, which is tens of rows, not a vector index; the day an agent has thousands of episodes
 * is the day for pgvector, and the column is already there to index. A summary embedded by a
 * different model than the store's current embedder cannot be compared and is ranked last.
 */
public class JdbcEpisodes implements Summarizer {

  /** How many episodes are shown when nothing else is said. */
  public static final int DEFAULT_SHOWN = 5;

  private static final String COLUMNS =
      "episode_no, from_turn, through_turn, title, reason, summary, embedding, embedding_model";

  private static final String ALL =
      "SELECT "
          + COLUMNS
          + " FROM nessy_episode WHERE agent_type = ? AND agent_id = ?"
          + " ORDER BY episode_no";

  private static final String OPEN =
      "SELECT "
          + COLUMNS
          + " FROM nessy_episode"
          + " WHERE agent_type = ? AND agent_id = ? AND through_turn IS NULL";

  private static final String UNSUMMARIZED =
      "SELECT "
          + COLUMNS
          + " FROM nessy_episode"
          + " WHERE agent_type = ? AND agent_id = ? AND through_turn IS NOT NULL"
          + " AND summary IS NULL ORDER BY episode_no";

  private static final String CLOSE =
      "UPDATE nessy_episode SET through_turn = ?, closed_at = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND through_turn IS NULL";

  private static final String RENAME =
      "UPDATE nessy_episode SET title = ?, reason = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND episode_no = ?";

  private static final String NEXT_NUMBER =
      "SELECT COALESCE(MAX(episode_no), 0) + 1 FROM nessy_episode"
          + " WHERE agent_type = ? AND agent_id = ?";

  private static final String INSERT =
      "INSERT INTO nessy_episode"
          + " (agent_type, agent_id, episode_no, from_turn, title, reason, opened_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

  // Only ever writes a first summary: two summarisers racing -- which the lease prevents, but a
  // row must hold on its own -- cannot overwrite each other.
  private static final String SUMMARIZE =
      "UPDATE nessy_episode SET summary = ?, embedding = ?, embedding_model = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND episode_no = ?"
          + " AND through_turn IS NOT NULL AND summary IS NULL";

  /** What a store is made of; see {@link JdbcEpisodes#create(Consumer)}. */
  public static final class Config {
    private DataSource dataSource;
    private AgentType agentType;
    private Embedder embedder;
    private int shown = DEFAULT_SHOWN;
    private Clock clock = Clock.systemUTC();

    private Config() {}

    /** The database the engine's own tables are in. */
    public Config dataSource(DataSource dataSource) {
      this.dataSource = dataSource;
      return this;
    }

    /** Whose episodes. */
    public Config agentType(AgentType agentType) {
      this.agentType = agentType;
      return this;
    }

    /**
     * What ranks episodes by relevance to the turn being answered. Without one, the most recent are
     * shown. The embedding model belongs to the store: change it and the summaries embedded by the
     * old one rank last until they are embedded again.
     */
    public Config embedder(Embedder embedder) {
      this.embedder = embedder;
      return this;
    }

    /**
     * How many summarised episodes the model is shown at most; {@value #DEFAULT_SHOWN} unless said.
     */
    public Config shown(int shown) {
      if (shown < 1) {
        throw new IllegalArgumentException("at least one episode must be shown, not " + shown);
      }
      this.shown = shown;
      return this;
    }

    public Config clock(Clock clock) {
      this.clock = clock;
      return this;
    }
  }

  public static JdbcEpisodes create(Consumer<Config> customizer) {
    Config config = new Config();
    customizer.accept(config);
    return new JdbcEpisodes(config);
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final String agentType;
  private final Embedder embedder;
  private final int shown;
  private final Clock clock;

  private JdbcEpisodes(Config config) {
    DataSource dataSource = Objects.requireNonNull(config.dataSource, "dataSource is required");
    this.jdbc = JdbcClient.create(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.agentType = Objects.requireNonNull(config.agentType, "agentType is required").value();
    this.embedder = config.embedder;
    this.shown = config.shown;
    this.clock = Objects.requireNonNull(config.clock, "clock must not be null");
  }

  /** The embedder this store ranks with, if it has one. */
  public Optional<Embedder> embedder() {
    return Optional.ofNullable(embedder);
  }

  /** Every episode, oldest first. */
  public List<Episode> all(AgentId agentId) {
    return rows(ALL, agentId).stream().map(Row::episode).toList();
  }

  /** The one still being added to, if any. */
  public Optional<Episode> open(AgentId agentId) {
    return rows(OPEN, agentId).stream().map(Row::episode).findFirst();
  }

  /** The one with this number, if there is one. */
  public Optional<Episode> find(AgentId agentId, int number) {
    return all(agentId).stream().filter(e -> e.number() == number).findFirst();
  }

  /** Closed, and not yet summarised: what the {@link EpisodeSummarizer} has to do. */
  public List<Episode> unsummarized(AgentId agentId) {
    return rows(UNSUMMARIZED, agentId).stream().map(Row::episode).toList();
  }

  /**
   * Begins an episode at this turn, closing the open one at the turn before. Begun twice in the
   * same turn, the second call renames the first's episode rather than opening an empty one.
   */
  public Episode begin(AgentId agentId, TurnId at, String title, String reason) {
    Objects.requireNonNull(at, "at must not be null");
    String named = required(title, "title");
    String because = required(reason, "reason");
    return transactions.execute(
        _ -> {
          Optional<Episode> current = open(agentId);
          if (current.isPresent() && current.get().from().value() >= at.value()) {
            Episode renamed = current.get();
            jdbc.sql(RENAME)
                .params(named, because, agentType, key(agentId), renamed.number())
                .update();
            return new Episode(renamed.number(), renamed.from(), null, named, because, null);
          }
          OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
          jdbc.sql(CLOSE).params(at.value() - 1, now, agentType, key(agentId)).update();
          int number =
              jdbc.sql(NEXT_NUMBER).params(agentType, key(agentId)).query(Integer.class).single();
          jdbc.sql(INSERT)
              .params(agentType, key(agentId), number, at.value(), named, because, now)
              .update();
          return new Episode(number, at, null, named, because, null);
        });
  }

  /**
   * Writes an episode's summary, and its embedding by this store's embedder when it has one. Says
   * whether it did: a summary already there is kept, and a summary of nothing is refused.
   */
  public boolean summarize(AgentId agentId, int number, String summary) {
    String text = required(summary, "summary");
    Embedding embedding = embedder == null ? null : embedder.embed(text);
    return jdbc.sql(SUMMARIZE)
            .params(
                text,
                embedding == null ? null : embedding.vector(),
                embedding == null ? null : embedding.model(),
                agentType,
                key(agentId),
                number)
            .update()
        > 0;
  }

  /** Without a turn to rank against: the most recent of the candidates. */
  @Override
  public List<Summary> forAgent(AgentId agentId) {
    return choose(candidates(agentId), null);
  }

  /** Ranked against the observation being answered, when there is an embedder to rank with. */
  @Override
  public List<Summary> forAgent(AgentId agentId, Turn current) {
    Objects.requireNonNull(current, "current must not be null");
    return choose(candidates(agentId), Transcripts.text(current.observation().blocks()));
  }

  /** The end of the last candidate, shown or not: the tail begins after it. */
  @Override
  public Optional<TurnId> summarizedThrough(AgentId agentId) {
    List<Row> candidates = candidates(agentId);
    return candidates.isEmpty()
        ? Optional.empty()
        : Optional.of(candidates.getLast().episode().through());
  }

  /** The summarised prefix of the story: every episode before the first unsummarised one. */
  private List<Row> candidates(AgentId agentId) {
    List<Row> prefix = new ArrayList<>();
    for (Row row : rows(ALL, agentId)) {
      if (!row.episode().summarized()) {
        break;
      }
      prefix.add(row);
    }
    return prefix;
  }

  private List<Summary> choose(List<Row> candidates, String query) {
    List<Row> chosen;
    if (candidates.size() <= shown) {
      chosen = candidates;
    } else if (embedder != null && query != null && !query.isBlank()) {
      chosen = mostRelevant(candidates, embedder.embed(query));
    } else {
      chosen = candidates.subList(candidates.size() - shown, candidates.size());
    }
    return chosen.stream().map(Row::summary).toList();
  }

  /** The latest always, then the best of the rest; back in story order for the model. */
  private List<Row> mostRelevant(List<Row> candidates, Embedding query) {
    Row latest = candidates.getLast();
    List<Row> ranked =
        candidates.subList(0, candidates.size() - 1).stream()
            .sorted(Comparator.comparingDouble((Row row) -> row.similarity(query)).reversed())
            .limit(shown - 1L)
            .collect(ArrayList::new, List::add, List::addAll);
    ranked.add(latest);
    ranked.sort(Comparator.comparingInt(row -> row.episode().number()));
    return ranked;
  }

  private List<Row> rows(String sql, AgentId agentId) {
    return jdbc.sql(sql).params(agentType, key(agentId)).query(JdbcEpisodes::row).list();
  }

  private static Row row(ResultSet rs, int rowNumber) throws SQLException {
    long through = rs.getLong("through_turn");
    boolean open = rs.wasNull();
    Episode episode =
        new Episode(
            rs.getInt("episode_no"),
            new TurnId(rs.getLong("from_turn")),
            open ? null : new TurnId(through),
            rs.getString("title"),
            rs.getString("reason"),
            rs.getString("summary"));
    String model = rs.getString("embedding_model");
    Array array = rs.getArray("embedding");
    Embedding embedding = null;
    if (model != null && array != null) {
      Float[] boxed = (Float[]) array.getArray();
      float[] vector = new float[boxed.length];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = boxed[i];
      }
      embedding = new Embedding(model, vector);
    }
    return new Row(episode, embedding);
  }

  /** An episode as read, with the embedding its summary was written with, if any. */
  private record Row(Episode episode, Embedding embedding) {

    /** Not comparable -- no embedding, or another model's -- ranks below every real score. */
    double similarity(Embedding query) {
      return embedding != null && embedding.model().equals(query.model())
          ? embedding.similarity(query)
          : Double.NEGATIVE_INFINITY;
    }

    Summary summary() {
      return Summary.text(
          episode.from(),
          episode.through(),
          "Episode " + episode.number() + ", " + episode.title() + ": " + episode.summary());
    }
  }

  private static String required(String value, String what) {
    Objects.requireNonNull(value, what + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    return value.strip();
  }

  /** The id as the TEXT column holds it; a bare UUID is not text to PostgreSQL. */
  private static String key(AgentId agentId) {
    return agentId.value().toString();
  }
}
