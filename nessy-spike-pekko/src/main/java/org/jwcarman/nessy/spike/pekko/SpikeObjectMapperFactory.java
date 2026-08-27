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
package org.jwcarman.nessy.spike.pekko;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.serialization.jackson.JacksonObjectMapperFactory;

/**
 * THROWAWAY SPIKE. The seam Pekko offers for owning its ObjectMapper.
 *
 * <p>Worth knowing what this seam actually is. {@link JacksonObjectMapperFactory} has a dozen
 * override points, but eleven of them take and return {@code scala.collection.immutable.Seq} and
 * are effectively unusable from Java. {@link #newObjectMapper} is the one with a Java-shaped
 * signature, and it is enough: Pekko applies its configured modules and features to whatever mapper
 * is returned here, so an application can hand over a pre-configured mapper and keep Pekko's
 * behaviour on top of it.
 *
 * <p>This one changes nothing — it exists to prove the seam is reachable, and to be the place a
 * real integration would hand Nessy's own pinned mapper over.
 */
public final class SpikeObjectMapperFactory extends JacksonObjectMapperFactory {

  @Override
  public ObjectMapper newObjectMapper(String bindingName, JsonFactory jsonFactory) {
    return super.newObjectMapper(bindingName, jsonFactory);
  }
}
