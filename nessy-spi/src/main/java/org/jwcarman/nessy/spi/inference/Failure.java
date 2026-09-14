package org.jwcarman.nessy.spi.inference;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Why a piece of work produced no result.
 *
 * <p>The three arms are not degrees of severity -- they are statements about <em>what is
 * known</em>, which is what decides whether trying again could possibly help:
 *
 * <ul>
 *   <li>{@link Permanent} -- it failed, and the identical request will fail identically
 *   <li>{@link Transient} -- it failed, and it might not next time
 *   <li>{@link Unknown} -- nobody found out whether it failed
 * </ul>
 *
 * <p>Classifying is the job of whatever caught the failure, because only an adapter knows what a
 * 404 means on its wire. Deciding how many chances that is worth is the retry policy's job, and it
 * needs none of this vocabulary -- which is what lets a policy stay plain serializable data.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Failure.Permanent.class, name = "permanent"),
  @JsonSubTypes.Type(value = Failure.Rejected.class, name = "rejected"),
  @JsonSubTypes.Type(value = Failure.Transient.class, name = "transient"),
  @JsonSubTypes.Type(value = Failure.Unknown.class, name = "unknown")
})
public sealed interface Failure {

  /** What went wrong, in words, for the fact this becomes. */
  String reason();

  /**
   * The request was refused on its merits. Retrying spends a budget to receive the same answer, so
   * this never consults a policy at all.
   *
   * <p>Note that "permanent" is relative to <em>this request</em>, not to the world: a context that
   * is too large is permanent for these messages and would succeed for shorter ones. The useful
   * response there is a different request, which is a decision for the fold rather than for a
   * retry.
   */
  record Permanent(String reason) implements Failure {}

  /**
   * The provider rejected the input itself, and named it -- this content, not this much of it.
   *
   * <p>The only arm that authorises destroying anything. A message identified here is one that will
   * fail every time it is sent, so it is quarantined: kept in the record, removed from what is
   * sent, and the conversation carries on instead of being doomed by one bad message.
   *
   * <p><b>Never a fallback.</b> Quarantining deletes something a person said, on our judgement, so
   * it must be positively identified rather than inferred from an unrecognised failure. Anything
   * permanent that we cannot attribute to specific content is {@link Permanent}, which costs a slow
   * retry loop -- recoverable, visible, and cheap. Getting that backwards means deleting a user's
   * question to work around a bug of our own, and it would look like it worked.
   *
   * <p><b>Not a model refusing.</b> A model that declines a request answers it: a 200, tokens
   * generated, prose explaining why. That is content and it belongs in the story. This is the
   * provider declining to run the request at all.
   */
  record Rejected(String reason) implements Failure {}

  /** The attempt failed for a reason that may not hold next time. */
  record Transient(String reason) implements Failure {}

  /**
   * The work may or may not have happened; nobody observed the outcome. A read timeout after the
   * request was accepted is the fast version of this, and a deferral whose deadline lapsed is the
   * slow one -- both leave exactly the same question open.
   *
   * <p>Retrying is therefore only safe when repeating the work is safe, which is a property of the
   * work rather than of the failure. The policy already says so: a budget above one attempt
   * <em>is</em> the declaration that repeating does no harm.
   */
  record Unknown(String reason) implements Failure {}
}
