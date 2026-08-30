/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.api.tool;

import java.util.Objects;

/**
 * Where a deferred answer goes — an opaque token, handed to whatever will answer later.
 *
 * <p>A tool that defers reads this from its {@link ToolContext}, gives it to the vendor (in a
 * webhook URL, a message, a job record), and returns. Nothing else is needed: the address already
 * exists before the tool is dispatched, which is why there is no callback to run afterwards.
 *
 * <p><b>A token, not a handle.</b> It has no methods and nothing to operate — you are given it, you
 * hold it, you present it. That is why it is not called a handle: in Java a handle is a live
 * reference to something you can act on, and this is inert.
 *
 * <p><b>Opaque by contract.</b> Holders never parse it. It carries LOGICAL coordinates — which
 * agent, which call — and never a physical address: an answer may arrive hours later, after a
 * restart or a rebalancing, and only logical coordinates survive that.
 *
 * <p><b>Two properties the engine owes it, however it is built:</b> a holder must not be able to
 * READ the coordinates, and must not be able to FORGE a token for a call it was never given. How
 * that is achieved — encrypting the coordinates into the token, or minting a random id the engine
 * can resolve — is the engine's business, not this type's.
 *
 * <p><b>Authentic is not the same as open.</b> A token that verifies says only that the engine
 * issued it. Whether the call is still waiting is the call's own state, checked separately: a
 * settled call and an expired deferral both reject a perfectly valid token.
 *
 * <p><b>It is a bearer token.</b> Whoever holds it can complete that call. That is accepted
 * deliberately rather than by omission: a handle leaked into a log or a URL is an unauthenticated
 * door into one tool call.
 */
public record ReplyToken(String value) {

  public ReplyToken {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("reply handle must not be blank");
    }
  }

  public static ReplyToken of(String value) {
    return new ReplyToken(value);
  }
}
