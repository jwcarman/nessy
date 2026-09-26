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
package org.jwcarman.nessy.spring.boot.inference;

import java.time.Duration;

/**
 * The transport timeout every inference auto-configuration sets on the provider it builds (design
 * record 2026-09-25-locks-as-plumbing-design.md §5b).
 *
 * <p>A margin above {@code InferenceConfig.timeout}'s five-minute default, never below it: if the
 * transport gave up first, an abandoned call would surface as the provider's own exception rather
 * than as the engine's own deadline -- correct either way, but the log would say two different
 * things. Not a Spring property: an application that wants a different engine deadline sets {@code
 * InferenceConfig.timeout} itself; the one thing this constant needs to be is comfortably above
 * whatever that default is.
 */
final class TransportTimeouts {

  /** One minute above the five-minute {@code InferenceConfig.timeout} default. */
  static final Duration PROVIDER_TRANSPORT = Duration.ofMinutes(6);

  private TransportTimeouts() {}
}
