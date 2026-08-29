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
package org.jwcarman.nessy.engine;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.agent.ObservationRenderer;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * Builds harnesses. The infrastructure — the actor system, the substrate, the model provider — is
 * given to an implementation once, at construction; each {@link #create} call adds only what makes
 * one kind of agent different from another (engine-extraction spec §2.1).
 *
 * <p>That is why nothing here mentions Pekko. An implementation holds an {@code ActorSystem}, which
 * is the one type identical whether the deployment is a single node or a cluster — so the
 * implementation decides between local spawning and cluster sharding by asking the system what it
 * has, and the caller never states which world they are in (spec §11).
 */
public interface HarnessFactory {

  /**
   * The common case: observations are text.
   *
   * <p>Supplies the text renderer BEFORE applying {@code customizer}, so a caller who wants a
   * different one can still say so.
   */
  default Harness<String> create(HarnessCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    return create(
        String.class,
        config -> {
          config.renderer(text -> List.of(new TextBlock(text)));
          customizer.customize(config);
        });
  }

  /**
   * The typed door: observations are {@code O}, and {@code customizer} must supply the {@link
   * ObservationRenderer} that turns one into inference content.
   *
   * <p>{@code observationType} is load-bearing rather than ceremony — erasure means Pekko's {@code
   * EntityTypeKey} and {@code ServiceKey} need a class literal, and the backlog needs the type to
   * encode what it stores.
   *
   * @param <O> the observation type these agents accept
   */
  <O> Harness<O> create(Class<O> observationType, HarnessCustomizer<O> customizer);
}
