package org.jwcarman.nessy.examples.watchman;

import java.time.Instant;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.CallId;

/** One question put to a person, as the board keeps it. */
public record PendingApproval(
    CallId callId,
    AgentType agentType,
    AgentId agentId,
    String tool,
    String action,
    Instant askedAt,
    Instant expiresAt,
    String replyToken,
    Optional<String> answer,
    Optional<String> note,
    Optional<Instant> answeredAt) {

  public boolean waiting() {
    return answer.isEmpty();
  }
}
