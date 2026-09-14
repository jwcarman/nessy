package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Which agent this is.
 *
 * <p>Chosen by the application, not by the engine: an agent is usually the thing an application
 * already has an identity for -- a conversation, a device, a ticket -- and minting a second one
 * here would only create something to keep in step.
 *
 * <p>A value type rather than a raw {@link UUID} so it cannot be passed where some other id was
 * meant. Nothing in this system has one id.
 */
public record AgentId(UUID value) {

  public AgentId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /** For an agent whose identity is nobody else's business. */
  public static AgentId random() {
    return new AgentId(UUID.randomUUID());
  }
}
