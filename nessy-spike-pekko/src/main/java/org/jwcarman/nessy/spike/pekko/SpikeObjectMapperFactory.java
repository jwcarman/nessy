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

import org.apache.pekko.serialization.jackson3.JacksonObjectMapperFactory;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * THROWAWAY SPIKE. The seam Pekko offers for owning its ObjectMapper.
 *
 * <p><b>Changed shape in Pekko 2.0, and for the better.</b> On 1.x this was {@code
 * newObjectMapper(String bindingName, JsonFactory)} returning a fully built {@code ObjectMapper} —
 * awkward, because Jackson 2 mappers are mutable and Pekko then reconfigured the instance you
 * handed back. Jackson 3 mappers are immutable, so 2.0's seam is {@code
 * newObjectMapperBuilder(JsonFactory)} returning a {@link JsonMapper.Builder} that Pekko finishes.
 * That is the correct shape for an immutable mapper.
 *
 * <p>What did NOT improve: the other eleven override points still take and return {@code
 * scala.collection.immutable.Seq} and remain effectively unusable from Java, and there is still no
 * config key for the factory — registration is {@code JacksonObjectMapperProviderSetup} inside an
 * {@code ActorSystemSetup}, i.e. Java code. See {@link SpikeCluster}.
 *
 * <p>This one changes nothing. It exists to prove the seam is reachable, and to mark the place a
 * real integration would hand its own mapper configuration over.
 */
public final class SpikeObjectMapperFactory extends JacksonObjectMapperFactory {

  @Override
  public JsonMapper.Builder newObjectMapperBuilder(JsonFactory jsonFactory) {
    return super.newObjectMapperBuilder(jsonFactory);
  }
}
