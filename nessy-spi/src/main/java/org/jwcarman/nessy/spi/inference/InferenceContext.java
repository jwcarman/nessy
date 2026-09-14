package org.jwcarman.nessy.spi.inference;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.turn.Turn;

/**
 * What a provider is given to work from: the conversation, and whatever background stands behind
 * it.
 *
 * <p><b>Turns rather than a flat list of messages</b>, because a flat list is already a wire shape
 * and it is one particular provider's. The same fact is encoded differently by each of them -- a
 * tool result is a {@code user} message carrying {@code tool_result} blocks for one, a message with
 * {@code role: "tool"} for another, and a {@code functionResponse} part for a third. A sequence of
 * {@code (role, content)} pairs has to commit to one of those; a turn commits to none and lets each
 * adapter encode it.
 *
 * <p>So this is the anti-corruption boundary. Everything up to here is ours and portable -- the
 * story is folded into turns once, by logic no provider influences -- and everything past it is one
 * vendor's shape. An adapter translates; it never interprets. Deciding what a refused turn shows
 * the model, or what a failed one says in its place, is a decision taken here, once, rather than
 * reinvented in prose by every adapter that has to render it.
 *
 * <p>The system prompt is resolved per call too, which is what lets it vary per agent and know what
 * today is. Carried here rather than sent separately because it is part of the context: how an
 * adapter delivers it is a wire question -- a top-level field for some providers, a leading message
 * for others.
 *
 * <p><b>Ambient sits beside the turns, not among them.</b> Background is not something anybody
 * said, so it has no place in a sequence of things that were said -- and the two have opposite
 * lifetimes: a turn is written down once and re-sent verbatim forever, while background is
 * re-derived on every call and may say something different each time. Interleaving them would put a
 * view of the world into a record of a conversation, and no later reader could tell which was
 * which.
 *
 * <p>Derived per call and discarded. Nothing here is stored.
 *
 * @param turns the conversation, oldest first
 * @param ambient what stands behind it, in the order its sources were bound -- usually empty
 */
public record InferenceContext(List<Turn> turns, List<Ambient> ambient) {

  public InferenceContext {
    Objects.requireNonNull(turns, "turns must not be null");
    Objects.requireNonNull(ambient, "ambient must not be null");
    turns = List.copyOf(turns);
    ambient = List.copyOf(ambient);
  }

  /** A conversation with nothing standing behind it. */
  public static InferenceContext of(List<Turn> turns) {
    return new InferenceContext(turns, List.of());
  }

  /**
   * Whether there is any background at all.
   *
   * <p>Worth asking rather than rendering an empty section: an adapter that always writes a
   * background block would tell the model "here is what you know" and then say nothing, which is a
   * claim where absence is not.
   */
  public boolean hasAmbient() {
    return !ambient.isEmpty();
  }
}
