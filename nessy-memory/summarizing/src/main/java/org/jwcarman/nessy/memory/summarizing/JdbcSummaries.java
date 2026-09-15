package org.jwcarman.nessy.memory.summarizing;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The summary each agent of one type has of the head of its story, in {@code nessy_summary}.
 *
 * <p>One per agent, folded forward: as a {@link Summarizer} it shows the summary, which the engine
 * places before the verbatim tail; the {@link HeadSummarizer} replaces it with one that covers more
 * of the story, and only ever with one that reaches further.
 */
public class JdbcSummaries implements Summarizer {

  private static final String FOR_AGENT =
      "SELECT from_turn, through_turn, content FROM nessy_summary"
          + " WHERE agent_type = ? AND agent_id = ? ORDER BY from_turn";

  private static final String THROUGH =
      "SELECT MAX(through_turn) FROM nessy_summary WHERE agent_type = ? AND agent_id = ?";

  // Replaces, and only ever with a summary that reaches further: two folds racing -- which the
  // lease prevents, but a row must hold on its own -- cannot move the story backwards.
  private static final String REPLACE =
      """
      INSERT INTO nessy_summary
             (agent_type, agent_id, from_turn, through_turn, content, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (agent_type, agent_id) DO UPDATE
             SET from_turn = EXCLUDED.from_turn,
                 through_turn = EXCLUDED.through_turn,
                 content = EXCLUDED.content,
                 updated_at = EXCLUDED.updated_at
           WHERE nessy_summary.through_turn < EXCLUDED.through_turn
      """;

  private final JdbcClient jdbc;
  private final String agentType;
  private final Clock clock;

  public JdbcSummaries(DataSource dataSource, AgentType agentType) {
    this(dataSource, agentType, Clock.systemUTC());
  }

  public JdbcSummaries(DataSource dataSource, AgentType agentType, Clock clock) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").value();
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public List<Summary> forAgent(AgentId agentId) {
    return jdbc.sql(FOR_AGENT)
        .params(agentType, key(agentId))
        .query(
            (rs, n) ->
                Summary.text(
                    new TurnId(rs.getLong("from_turn")),
                    new TurnId(rs.getLong("through_turn")),
                    rs.getString("content")))
        .list();
  }

  @Override
  public Optional<TurnId> summarizedThrough(AgentId agentId) {
    return jdbc.sql(THROUGH)
        .params(agentType, key(agentId))
        .query(Long.class)
        .optional()
        .map(TurnId::new);
  }

  /**
   * Replaces the agent's summary with one that reaches further into the story. Says whether it did:
   * a summary that reaches no further than the one there is left unwritten.
   */
  public boolean replace(AgentId agentId, Summary summary) {
    return jdbc.sql(REPLACE)
            .params(
                agentType,
                key(agentId),
                summary.from().value(),
                summary.through().value(),
                textOf(summary),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .update()
        > 0;
  }

  private static String textOf(Summary summary) {
    return summary.content().stream()
        .map(
            block ->
                switch (block) {
                  case Block.Text(String text) -> text;
                })
        .collect(Collectors.joining("\n"));
  }

  /** The id as the TEXT column holds it; a bare UUID is not text to PostgreSQL. */
  private static String key(AgentId agentId) {
    return agentId.value().toString();
  }
}
