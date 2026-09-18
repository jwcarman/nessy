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
package org.jwcarman.nessy.engine.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.inference.InferenceRecorder;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.Usage;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code nessy_inference_context} table: the whole request, through the storage codec like
 * every other row, so that at-rest encryption covers what the model was shown too.
 *
 * <p>Two writes per call, each in its own statement and neither in the fold's transaction: the
 * request before the provider is asked, so a call that never returns still has its context on
 * record, and the outcome afterwards.
 */
public class JdbcInferenceContexts implements InferenceContexts, InferenceRecorder {

  private static final String BEGIN =
      "INSERT INTO nessy_inference_context"
          + " (context_id, agent_type, agent_id, turn_id, requested_at, model, payload)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";
  private static final String END =
      "UPDATE nessy_inference_context SET outcome = ?, completed_at = ?, input_tokens = ?,"
          + " output_tokens = ? WHERE context_id = ?";
  private static final String COLUMNS =
      "context_id, agent_type, agent_id, turn_id, requested_at, payload, outcome, completed_at,"
          + " input_tokens, output_tokens";
  private static final String FOR_AGENT =
      "SELECT "
          + COLUMNS
          + " FROM nessy_inference_context WHERE agent_type = ? AND agent_id = ?"
          + " ORDER BY requested_at, context_id";
  private static final String FIND =
      "SELECT " + COLUMNS + " FROM nessy_inference_context WHERE context_id = ?";

  private final JdbcClient jdbc;
  private final Codec<InferenceRequest> codec;
  private final Clock clock;

  public JdbcInferenceContexts(JdbcClient jdbc, CodecFactory codecs, Clock clock) {
    this.jdbc = jdbc;
    this.codec = codecs.create(InferenceRequest.class);
    this.clock = clock;
  }

  @Override
  public UUID begin(AgentType agentType, AgentId agentId, InferenceRequest request) {
    UUID id = UUID.randomUUID();
    jdbc.sql(BEGIN)
        .params(
            id,
            agentType.value(),
            agentId.value(),
            openTurn(request).value(),
            OffsetDateTime.ofInstant(clock.instant(), clock.getZone()),
            request.options().modelName(),
            codec.encode(request))
        .update();
    return id;
  }

  @Override
  public void end(UUID id, InferenceResult result) {
    complete(id, outcomeOf(result), result.usage());
  }

  @Override
  public void failed(UUID id) {
    complete(id, "fault", Usage.unknown());
  }

  private void complete(UUID id, String outcome, Usage usage) {
    jdbc.sql(END)
        .params(
            outcome,
            OffsetDateTime.ofInstant(clock.instant(), clock.getZone()),
            usage.known() ? usage.inputTokens() : null,
            usage.known() ? usage.outputTokens() : null,
            id)
        .update();
  }

  @Override
  public List<RecordedInference> forAgent(AgentType agentType, AgentId agentId) {
    return jdbc.sql(FOR_AGENT).params(agentType.value(), agentId.value()).query(this::read).list();
  }

  @Override
  public Optional<RecordedInference> find(UUID id) {
    return jdbc.sql(FIND).param(id).query(this::read).optional();
  }

  private RecordedInference read(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
    long inputTokens = rs.getLong("input_tokens");
    boolean uncounted = rs.wasNull();
    long outputTokens = rs.getLong("output_tokens");
    Optional<Usage> usage =
        uncounted || rs.wasNull()
            ? Optional.empty()
            : Optional.of(new Usage(inputTokens, outputTokens));
    return new RecordedInference(
        rs.getObject("context_id", UUID.class),
        new AgentType(rs.getString("agent_type")),
        new AgentId(rs.getObject("agent_id", UUID.class)),
        new TurnId(rs.getLong("turn_id")),
        rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
        codec.decode(rs.getBytes("payload")),
        Optional.ofNullable(rs.getString("outcome")),
        Optional.ofNullable(completedAt).map(OffsetDateTime::toInstant),
        usage);
  }

  /** The last turn in the context is the one the call is answering; a first call has turn 0. */
  private static TurnId openTurn(InferenceRequest request) {
    var turns = request.context().turns();
    return turns.isEmpty() ? new TurnId(0) : turns.getLast().id();
  }

  static String outcomeOf(InferenceResult result) {
    return switch (result) {
      case InferenceResult.Answer _ -> "answer";
      case InferenceResult.Actions _ -> "actions";
      case InferenceResult.Refusal _ -> "refusal";
      case InferenceResult.Fault _ -> "fault";
    };
  }
}
