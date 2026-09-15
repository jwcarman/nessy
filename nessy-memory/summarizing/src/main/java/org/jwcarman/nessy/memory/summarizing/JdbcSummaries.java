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
import org.jwcarman.nessy.api.SummarySource;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The summaries of one agent type's stories, in {@code nessy_summary}.
 *
 * <p>As a {@link SummarySource} it shows every summary an agent has, oldest first, which the engine
 * places before the verbatim tail. Writing is the {@link HeadSummarizer}'s business, or an episode
 * tool's; either appends a range that begins where the last one ended.
 */
public class JdbcSummaries implements SummarySource {

  private static final String FOR_AGENT =
      "SELECT from_turn, through_turn, content FROM nessy_summary"
          + " WHERE agent_type = ? AND agent_id = ? ORDER BY from_turn";

  private static final String THROUGH =
      "SELECT MAX(through_turn) FROM nessy_summary WHERE agent_type = ? AND agent_id = ?";

  private static final String INSERT =
      "INSERT INTO nessy_summary"
          + " (agent_type, agent_id, from_turn, through_turn, content, created_at)"
          + " VALUES (?, ?, ?, ?, ?, ?)";

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
   * Appends a summary. Its range must begin after the last one's end: turn ids are positions in the
   * story rather than a count, so "after" is all that can be asked, not "at the next number".
   */
  public void write(AgentId agentId, Summary summary) {
    long through = summarizedThrough(agentId).map(TurnId::value).orElse(0L);
    if (summary.from().value() <= through) {
      throw new IllegalArgumentException(
          "a summary must begin after the last one ended (turn %d), got %s"
              .formatted(through, summary.from()));
    }
    jdbc.sql(INSERT)
        .params(
            agentType,
            key(agentId),
            summary.from().value(),
            summary.through().value(),
            textOf(summary),
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .update();
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
