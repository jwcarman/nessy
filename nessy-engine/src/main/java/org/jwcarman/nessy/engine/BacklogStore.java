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
package org.jwcarman.nessy.engine;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What is waiting to become a turn.
 *
 * <p><b>Where the observation type ends.</b> The codec, the renderer and the coalescer all live
 * here, so nothing above this is generic: an agent deals in a turn id and a claim id, and this is
 * the last place that knows what an observation actually is.
 *
 * <p><b>Why the coalescer still sees observations.</b> Rendering at the door would force a policy
 * to compare rendered messages — string-matching its way back to a question it already had a direct
 * answer to. Rendering happens once, at {@link #take}, so an observation coalesced away is never
 * rendered at all.
 *
 * @param <O> the observation type
 */
public final class BacklogStore<O> {

  /**
   * The key an observation is held under, alongside a turn's {@code asked} and {@code result-*}.
   */
  static final String OBSERVATION_KEY = "observation";

  /**
   * One agent's rows, LOCKED for the duration of the enclosing transaction.
   *
   * <p>{@code FOR UPDATE} is not belt and braces. Two takes are routinely in flight at once — an
   * agent asks for work on activation and again when told the backlog changed — and without the
   * lock both read the same untaken row, both render it, and both write its claim. Measured: the
   * second insert loses on the claim's primary key, the turn is reported failed, and an agent that
   * was working perfectly well goes to sleep. With the lock the second take waits, sees the row
   * already taken, and hands back the claim the first one wrote, which is the idempotent path this
   * was designed around.
   *
   * <p>Portable: PostgreSQL and H2 both take a row lock here, and it is scoped to one agent's rows.
   */
  private static final String WAITING =
      "SELECT item_id, received_at, observation, taken_claim FROM nessy_backlog"
          + " WHERE agent_id = ? ORDER BY ordinal FOR UPDATE";

  private static final String INSERT =
      "INSERT INTO nessy_backlog (agent_id, item_id, ordinal, received_at, observation)"
          + " VALUES (?, ?, ?, ?, ?)";
  private static final String DELETE_ROW =
      "DELETE FROM nessy_backlog WHERE agent_id = ? AND item_id = ?";
  private static final String DELETE_AGENT = "DELETE FROM nessy_backlog WHERE agent_id = ?";
  private static final String DELETE_UNTAKEN =
      "DELETE FROM nessy_backlog WHERE agent_id = ? AND taken_claim IS NULL";
  private static final String MARK_TAKEN =
      "UPDATE nessy_backlog SET taken_claim = ? WHERE agent_id = ? AND item_id = ?";

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Claims claims;
  private final Codec<O> codec;
  private final Codec<UserMessage> messages;
  private final ObservationRenderer<O> renderer;
  private final BacklogCoalescer<O> coalescer;
  private final Clock clock;

  /** What a take hands back: the row's id, which is the turn id, and where its input is held. */
  public record Taken(TurnId turnId, String observationClaim) {}

  /**
   * One row as the table holds it.
   *
   * <p>The generated equality a record gives you compares {@code observation} by IDENTITY, so two
   * rows read from the same database would differ. Nothing here relies on that today; it is written
   * out because the day something does, the failure is silent.
   */
  private record Row(TurnId itemId, Instant receivedAt, byte[] observation, String takenClaim) {

    boolean untaken() {
      return takenClaim == null;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Row row
          && Objects.equals(itemId, row.itemId)
          && Objects.equals(receivedAt, row.receivedAt)
          && Arrays.equals(observation, row.observation)
          && Objects.equals(takenClaim, row.takenClaim);
    }

    @Override
    public int hashCode() {
      return Objects.hash(itemId, receivedAt, Arrays.hashCode(observation), takenClaim);
    }

    /** The observation is a payload, so it is measured rather than printed. */
    @Override
    public String toString() {
      return "Row[itemId=%s, receivedAt=%s, observation=%d bytes, takenClaim=%s]"
          .formatted(itemId, receivedAt, observation == null ? 0 : observation.length, takenClaim);
    }
  }

  BacklogStore(
      DataSource dataSource,
      Claims claims,
      Codec<O> codec,
      Codec<UserMessage> messages,
      ObservationRenderer<O> renderer,
      BacklogCoalescer<O> coalescer,
      Clock clock) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.claims = Objects.requireNonNull(claims, "claims must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    this.messages = Objects.requireNonNull(messages, "messages must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.coalescer = Objects.requireNonNull(coalescer, "coalescer must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Takes an observation in, letting the coalescer decide what the waiting list becomes.
   *
   * <p>The caller MUST tell the agent afterwards and never before: an agent that takes before this
   * commits finds nothing and goes back to sleep with work sitting in the table.
   *
   * <p>The coalescer sees only what is WAITING. A row already taken is a turn in progress, and a
   * policy that supersedes would otherwise merge away the very observation being worked on.
   */
  public void offer(AgentId agentId, O observation) {
    transactions.executeWithoutResult(
        status -> {
          List<Row> rows = rows(agentId);
          List<BacklogItem<O>> waiting =
              rows.stream().filter(Row::untaken).map(this::itemOf).toList();
          BacklogItem<O> arrival =
              new BacklogItem<>(TurnId.of(Identifiers.next()), observation, clock.instant());
          rewrite(agentId, coalescer.coalesce(waiting, arrival));
        });
  }

  /**
   * Every row waiting for this agent, taken or not.
   *
   * <p>Only forgetting goes this wide. An observation offered AFTER this returns lands in an empty
   * table under an id nobody is listening to — harmless unless that id is used again, which is why
   * an id worth forgetting is worth not reusing.
   */
  public void deleteAgent(AgentId agentId) {
    jdbc.sql(DELETE_AGENT).param(agentId.value()).update();
  }

  /**
   * Finishes the row named by {@code lastCompleted} and hands over the next.
   *
   * <p>One transaction, so a crash leaves the row either untaken (retry, clean) or taken with its
   * claim already written (handed back unchanged). Never neither.
   *
   * <p><b>The sweep names an id; it never infers one from the agent's phase.</b> An idle agent
   * holding a taken row either finished that turn or died between this committing and the agent
   * recording it, and those two histories are indistinguishable from both sides. Naming the
   * completed claim removes the guess: the unrecorded case is the one nobody names.
   *
   * @param lastCompleted the turn id this agent has finished, or {@code null} if it has finished
   *     none
   */
  public Optional<Taken> take(AgentId agentId, TurnId lastCompleted) {
    return Optional.ofNullable(
        transactions.execute(
            status -> {
              if (lastCompleted != null) {
                jdbc.sql(DELETE_ROW).params(agentId.value(), lastCompleted.value()).update();
                claims.deleteTurn(agentId, lastCompleted);
              }
              List<Row> rows = rows(agentId);
              Optional<Row> stranded = rows.stream().filter(row -> !row.untaken()).findFirst();
              if (stranded.isPresent()) {
                Row row = stranded.get();
                return new Taken(row.itemId(), row.takenClaim());
              }
              if (rows.isEmpty()) {
                return null;
              }
              Row head = rows.getFirst();
              claims.put(
                  agentId,
                  head.itemId(),
                  OBSERVATION_KEY,
                  messages.encode(renderer.render(codec.decode(head.observation()))));
              jdbc.sql(MARK_TAKEN)
                  .params(OBSERVATION_KEY, agentId.value(), head.itemId().value())
                  .update();
              return new Taken(head.itemId(), OBSERVATION_KEY);
            }));
  }

  private List<Row> rows(AgentId agentId) {
    return jdbc.sql(WAITING)
        .param(agentId.value())
        .query(
            (rs, index) ->
                new Row(
                    TurnId.of(rs.getString("item_id")),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getBytes(OBSERVATION_KEY),
                    rs.getString("taken_claim")))
        .list();
  }

  private BacklogItem<O> itemOf(Row row) {
    return new BacklogItem<>(row.itemId(), codec.decode(row.observation()), row.receivedAt());
  }

  /**
   * Replaces the waiting rows with what the coalescer returned, in the order it returned them.
   *
   * <p>Written out wholesale rather than diffed, because the coalescer's contract is that its
   * RETURN VALUE is the backlog — it may drop, reorder or merge, and a diff would have to infer
   * which of those it did. Position in that list becomes {@code ordinal}, which is how a reordering
   * coalescer actually gets obeyed: what comes next is its answer, not a timestamp comparison.
   *
   * <p>A taken row is left alone. It is not waiting.
   */
  private void rewrite(AgentId agentId, List<BacklogItem<O>> kept) {
    jdbc.sql(DELETE_UNTAKEN).param(agentId.value()).update();
    List<BacklogItem<O>> items = new ArrayList<>(kept);
    for (int ordinal = 0; ordinal < items.size(); ordinal++) {
      BacklogItem<O> item = items.get(ordinal);
      jdbc.sql(INSERT)
          .params(
              agentId.value(),
              item.id().value(),
              ordinal,
              Timestamp.from(item.receivedAt()),
              codec.encode(item.observation()))
          .update();
    }
  }
}
