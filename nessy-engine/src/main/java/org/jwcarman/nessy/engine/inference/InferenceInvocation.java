package org.jwcarman.nessy.engine.inference;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/**
 * The ask, before any context exists: who is asking, and under what terms.
 *
 * <p>Identity rather than messages, because choosing what to send is the service's job. A caller
 * that had to assemble the context first would be doing the curation itself.
 */
public record InferenceInvocation(AgentType agentType, AgentId agentId, InferenceOptions options) {

  public InferenceInvocation {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(options, "options must not be null");
  }
}
