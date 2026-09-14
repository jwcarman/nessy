package org.jwcarman.nessy.api.tool;

import java.time.Instant;

/**
 * One call of a tool: its arguments, how long the answer is worth having, and where a late one
 * goes.
 *
 * @param <I> the type this tool's arguments were bound to
 */
public interface ToolCallRequest<I> {

  /** The call's arguments, already bound to {@link Tool#inputType()}. */
  I input();

  /**
   * When this call stops being worth finishing.
   *
   * <p>An instant rather than a duration, because a duration is a fresh budget every time it is
   * handed over and a deadline is the same fact however many times a call is attempted. Under a
   * retrying policy that difference is the whole point: five attempts of thirty seconds is a
   * hundred and fifty seconds nobody agreed to, whereas five attempts before one deadline is the
   * number that was configured. An attempt late in that sequence correctly sees less time left than
   * the first one did.
   *
   * <p>Configured as a duration -- {@code ToolConfig.timeout} -- and turned into an instant when
   * the call is written down, so an application says "tools get thirty seconds" and a tool is told
   * when thirty seconds is up.
   *
   * <p><b>Not the lease.</b> How long a lost call may hold its row before the engine presumes the
   * worker died and makes it due again is the engine's own number, per attempt, and no business of
   * the tool.
   *
   * <p>For a deferred answer this is when the question stops standing: the tool has told the
   * outside world where to reply, and this is how long that reply is still wanted.
   */
  Instant deadline();

  /**
   * Where an answer goes when it does not come back from {@link Tool#call}.
   *
   * <p>Only meaningful to a tool that returns {@link org.jwcarman.nessy.api.Awaited .Deferred}: it
   * hands this to whatever will eventually answer, and the engine matches the reply to the call
   * that is waiting for it.
   */
  ReplyToken replyToken();
}
