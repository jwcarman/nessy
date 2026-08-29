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

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.ConfigFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.serialization.Serialization;
import org.apache.pekko.serialization.SerializationExtension;
import org.apache.pekko.serialization.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.spi.codec.CodecPipeline;

/**
 * Actor state must be transformed the same way everything through {@code Substrate} is. Pekko
 * builds a serializer reflectively from config, so the only question that matters here is whether
 * the configured pipeline actually reaches it — asserted through Pekko's own serialization rather
 * than by calling the serializer directly, because construction is the part under test.
 */
class StateSerializerPipelineTest {

  private static final String CONFIG =
      """
      pekko.actor {
        serializers { nessy-state = "org.jwcarman.nessy.engine.StateSerializer" }
        serialization-bindings { "org.jwcarman.nessy.engine.AgentState" = nessy-state }
      }
      """;

  /** Appends a sentinel, so its presence in stored bytes is unambiguous. */
  private static Codec<byte[]> tagging() {
    return new Codec<>() {
      @Override
      public byte[] encode(byte[] value) {
        byte[] out = new byte[value.length + 1];
        System.arraycopy(value, 0, out, 0, value.length);
        out[value.length] = (byte) '!';
        return out;
      }

      @Override
      public byte[] decode(byte[] value) {
        byte[] out = new byte[value.length - 1];
        System.arraycopy(value, 0, out, 0, out.length);
        return out;
      }
    };
  }

  private ActorTestKit kit;

  @BeforeEach
  void startSystem() {
    kit = ActorTestKit.create("pipeline-test", ConfigFactory.parseString(CONFIG));
  }

  @AfterEach
  void stopSystem() {
    kit.shutdownTestKit();
  }

  @Test
  void a_serializer_pekko_built_for_itself_uses_the_pipeline_the_harness_installed() {
    EngineCodecs.of(kit.system())
        .use(CodecPipeline.of(List.of(chain -> chain.append("tag", tagging()))));
    Serialization serialization = SerializationExtension.get(kit.system().classicSystem());
    AgentState state = AgentState.idle();

    Serializer serializer = serialization.findSerializerFor(state);
    byte[] stored = serializer.toBinary(state);

    assertThat(serializer).isInstanceOf(StateSerializer.class);
    assertThat(new String(stored, StandardCharsets.UTF_8)).contains("tag");
    assertThat(
            serialization
                .deserialize(stored, serializer.identifier(), StateSerializer.AGENT_STATE_V2)
                .get())
        .isEqualTo(state);
  }

  @Test
  void a_system_with_no_pipeline_installed_still_stores_and_reads_state() {
    Serialization serialization = SerializationExtension.get(kit.system().classicSystem());
    AgentState state = AgentState.idle();

    Serializer serializer = serialization.findSerializerFor(state);
    byte[] stored = serializer.toBinary(state);

    assertThat(
            serialization
                .deserialize(stored, serializer.identifier(), StateSerializer.AGENT_STATE_V2)
                .get())
        .isEqualTo(state);
  }
}
