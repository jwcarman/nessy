package org.jwcarman.nessy.api.turn;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.Block;

/**
 * How a turn ended.
 *
 * <p>Three ways, and there is no fourth that leaves an agent free to act. A turn still in flight
 * has none of these -- {@link Turn#result()} is null, and that absence is the turn the model is
 * being called about.
 *
 * <p>Sealed, so a provider building a request switches over it exhaustively and cannot forget an
 * ending. It carries what each ending has rather than a discriminator plus a lookup: an answer has
 * content, and the other two have nothing to say beyond having happened.
 */
// Named on the wire so a Turn can be written down whole -- the recorded inference contexts are
// where that happens -- and read back as the type it was.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TurnResult.Answered.class, name = "answered"),
  @JsonSubTypes.Type(value = TurnResult.Failed.class, name = "failed"),
  @JsonSubTypes.Type(value = TurnResult.Refused.class, name = "refused")
})
public sealed interface TurnResult {

  /** The turn was answered. */
  record Answered(List<Block.AnswerContent> blocks) implements TurnResult {

    public Answered {
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.isEmpty()) {
        throw new IllegalArgumentException("an answer must have at least one block");
      }
      blocks = List.copyOf(blocks);
    }
  }

  /**
   * The turn ended without an answer, and might have gone otherwise.
   *
   * <p>"Not this time." Nothing about the conversation is wrong; a call did not complete.
   */
  record Failed() implements TurnResult {}

  /**
   * The turn was declined, and would be declined again.
   *
   * <p>"Not ever." Whether the observation that provoked it should still be sent is a provider's
   * decision, not this type's -- what one vendor refuses another answers.
   */
  record Refused() implements TurnResult {}
}
