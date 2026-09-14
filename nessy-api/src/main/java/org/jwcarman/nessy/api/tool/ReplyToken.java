package org.jwcarman.nessy.api.tool;

import java.util.Objects;

/**
 * Where a deferred answer goes.
 *
 * <p>Handed to whatever will eventually answer -- a person, a queue, a webhook -- and handed back
 * with the answer so the engine can match it to the call still waiting. It is the only thing
 * connecting the two, which is why a blank one is refused: a token nobody can match is a call
 * nobody can finish, and the agent waits until its deadline for an answer that had nowhere to land.
 */
public record ReplyToken(String value) {

  public ReplyToken {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
