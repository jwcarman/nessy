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
package org.jwcarman.nessy.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.nessy.agent.codec.Codecs;

/**
 * Test-only mapper source: recipes built directly in a test (bypassing {@code Nessy}'s builders)
 * still need a pinned mapper (spec §7) rather than a bare {@link ObjectMapper} — this is that pin,
 * applied to a fresh mapper each call so tests never share mutable mapper state.
 */
public final class TestMappers {

  private TestMappers() {}

  public static ObjectMapper plainlyPinned() {
    return Codecs.copyAndPin(new ObjectMapper());
  }
}
