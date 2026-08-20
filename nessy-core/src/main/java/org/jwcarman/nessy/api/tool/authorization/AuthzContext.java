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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Optional;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * One concrete, immutable, typed-key bag over the facts an authorization decision may need —
 * deliberately NOT generic over the tool's own effect type, so an {@code Enricher} or {@code
 * UsagePolicy} written against this interface composes into any grant regardless of what {@code E}
 * that grant welded.
 *
 * <p>The harness knows {@link #agentName()} and {@link #call()} before any application code runs;
 * everything else — including the well-known {@link #principal()} and {@link #declaredIntent()}
 * slots — starts empty and is filled in by enrichers via {@link #with}, functionally: each call
 * returns a new context, so an earlier enricher's view is never mutated out from under it (design
 * of record 2026-08-16-authorization §3).
 *
 * <p>A missing key is {@link Optional#empty()}, never an exception — a policy that cares about an
 * absent slot fails closed on its own terms and says so in its deny reason.
 */
public interface AuthzContext {

  /** The well-known slot a principal-resolving enricher deposits into. Empty until one does. */
  Key<Object> PRINCIPAL_KEY = new Key<>(Object.class, "principal");

  /**
   * The well-known slot {@code spi.intent} deposits the latest declaration recorded in the
   * harness's {@code IntentStore} into.
   */
  Key<Object> DECLARED_INTENT_KEY = new Key<>(Object.class, "declaredIntent");

  /** The agent that owns the grant being evaluated. */
  String agentName();

  /** The raw call: tool name and parsed arguments. */
  ToolCall call();

  /** Whatever an enricher deposited under {@code key}, or empty if nothing did. */
  <T> Optional<T> get(Key<T> key);

  /** A new context, functionally extended with {@code key} bound to {@code value}. */
  <T> AuthzContext with(Key<T> key, T value);

  /**
   * The nominal principal a conversation acts for — any type, nessy defines only the slot (design
   * §6). Empty until an enricher deposits one under {@link #PRINCIPAL_KEY}.
   */
  default Optional<Object> principal() {
    return get(PRINCIPAL_KEY);
  }

  /** {@link #principal()}, recovered by class token: empty on a miss as well as an absence. */
  default <P> Optional<P> principal(Class<P> type) {
    return principal().filter(type::isInstance).map(type::cast);
  }

  /**
   * The model's latest untrusted claim of intent (design §7) — empty unless {@code spi.intent} is
   * wired and has a declaration recorded in the harness's {@code IntentStore}.
   */
  default Optional<Object> declaredIntent() {
    return get(DECLARED_INTENT_KEY);
  }

  /** {@link #declaredIntent()}, recovered by class token: empty on a miss as well as an absence. */
  default <T> Optional<T> declaredIntent(Class<T> type) {
    return declaredIntent().filter(type::isInstance).map(type::cast);
  }

  /**
   * The harness-known facts, with no deposits yet — the chokepoint's own starting point before any
   * enricher runs.
   */
  static AuthzContext of(String agentName, ToolCall call) {
    return new AuthzContextImpl(agentName, call);
  }
}
