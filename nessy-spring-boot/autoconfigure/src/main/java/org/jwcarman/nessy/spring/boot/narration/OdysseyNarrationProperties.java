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
package org.jwcarman.nessy.spring.boot.narration;

import java.time.Duration;
import org.jwcarman.odyssey.core.TtlPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an agent's stream is kept.
 *
 * <p>A day of inactivity and a day per entry by default, because a conversation is picked up the
 * next morning and a watchman's round is looked at after the weekend; an hour of retention once a
 * stream is completed. Odyssey fixes a policy at the stream's first use, so changing these affects
 * agents that have not narrated yet.
 */
@ConfigurationProperties("nessy.narration.odyssey")
public record OdysseyNarrationProperties(
    Duration inactivityTtl, Duration entryTtl, Duration retentionTtl) {

  public OdysseyNarrationProperties {
    inactivityTtl = inactivityTtl == null ? Duration.ofDays(1) : inactivityTtl;
    entryTtl = entryTtl == null ? Duration.ofDays(1) : entryTtl;
    retentionTtl = retentionTtl == null ? Duration.ofHours(1) : retentionTtl;
  }

  public TtlPolicy ttl() {
    return new TtlPolicy(inactivityTtl, entryTtl, retentionTtl);
  }
}
