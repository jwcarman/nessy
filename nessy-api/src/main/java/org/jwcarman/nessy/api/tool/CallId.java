package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * What a provider called one tool call.
 *
 * <p>Not ours and never generated here: it is whatever the model wrote, and the only rule about it
 * is that the result must quote it back. That is also why it is a wrapper rather than something
 * with a format -- a value object over a string a vendor chose, so nothing here is tempted to
 * parse, validate or mint one.
 *
 * <p><b>Unique within one response, not within a conversation.</b> Two turns can each produce a
 * {@code "call_1"}, so anything identifying a call across turns pairs this with the turn it belongs
 * to. Inside one round -- which is the only place the engine matches results to calls -- it is
 * unique on its own.
 *
 * <p>A type rather than a string because it travels beside {@link ToolName} through the same
 * methods, and the two are indistinguishable to a compiler that only sees {@code String}.
 */
public record CallId(@JsonValue String value) {

  public CallId {
    Objects.requireNonNull(value, "call id must not be null");
    if (value.isBlank()) {
      // The id is how a result finds its way back to the call it answers. Without one the
      // call can never be discharged, and an undischargeable call wedges the conversation.
      throw new IllegalArgumentException("call id must not be blank");
    }
  }

  /**
   * Read back from the bare string it was written as.
   *
   * <p>{@code @JsonValue} and this together keep the stored shape exactly what it was before this
   * became a type: {@code "id":"call_1"} rather than {@code "id":{"value":"call_1"}}. Wrapping a
   * value that is already written down is a migration unless the wrapper is transparent, and there
   * is no reason for it not to be -- what a provider called something is a string, and it is stored
   * as one.
   */
  @JsonCreator
  public static CallId of(String value) {
    return new CallId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
