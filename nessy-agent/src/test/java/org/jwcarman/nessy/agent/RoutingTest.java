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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.ToolCall;

class RoutingTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Codec<Routing> codec = Routing.codec(mapper);

  @Test
  void aRoutingSurvivesTheRoundTrip() {
    JsonNode arguments = mapper.createObjectNode().put("q", 1);
    var original =
        new Routing("assistant", "scope-1", "resp-7", new ToolCall("call-3", "lookup", arguments));

    Routing decoded = codec.decode(codec.encode(original));

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void theCallArgumentsSurviveVerbatim() {
    ObjectNode deep = mapper.createObjectNode();
    deep.putObject("deep").put("n", 2);
    var original = new Routing("assistant", "scope-1", "resp-7", new ToolCall("c", "t", deep));

    Routing decoded = codec.decode(codec.encode(original));

    assertThat(decoded.call().arguments()).isEqualTo(deep);
  }
}
