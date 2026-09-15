package org.jwcarman.nessy.examples.watchman;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/** The one agent this application runs: one watchman, for one host. */
public final class Watchman {

  public static final AgentType TYPE = new AgentType("watchman");

  // A NAME-based id, so it is the same watchman after every restart. An agent id is a UUID; this
  // is the UUID the word "watchman" always maps to.
  public static final AgentId AGENT =
      new AgentId(UUID.nameUUIDFromBytes("watchman".getBytes(StandardCharsets.UTF_8)));

  private Watchman() {}
}
