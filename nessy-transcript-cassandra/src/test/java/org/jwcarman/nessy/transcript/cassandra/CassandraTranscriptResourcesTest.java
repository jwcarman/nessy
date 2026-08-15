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
package org.jwcarman.nessy.transcript.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * The offline floor {@code CassandraTranscriptTest} alone doesn't give the module: every test in
 * that class is {@code @Tag("container")}, so {@code ./mvnw verify} runs nothing here at all — the
 * exact gap that let the module's rename break a classpath-relative resource lookup without a
 * single red test. These run in the default build, no Docker, no {@code CqlSession}.
 */
class CassandraTranscriptResourcesTest {

  @Nested
  class The_bundled_schema_resource {

    @Test
    void resolves_relative_to_cassandra_transcript_next_to_its_class_file() throws IOException {
      try (InputStream in =
          CassandraTranscript.class.getResourceAsStream("transcript-schema.cql")) {
        assertThat(in).isNotNull();
        assertThat(new String(in.readAllBytes())).contains("nessy_transcript");
      }
    }
  }

  @Nested
  class The_message_codec {

    private final StateCodec codec = new StateCodec(new ObjectMapper());

    @Test
    void wraps_a_serialization_failure_as_an_illegal_argument_exception_naming_the_cause() {
      ObjectMapper poisoned = new ObjectMapper();
      SimpleModule poison = new SimpleModule();
      poison.addSerializer(
          TextBlock.class,
          new JsonSerializer<TextBlock>() {
            @Override
            public void serialize(
                TextBlock value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
              throw new JsonMappingException(gen, "poisoned for test");
            }
          });
      poisoned.registerModule(poison);
      StateCodec poisonedCodec = new StateCodec(poisoned);
      Message message = Message.user(List.of(new TextBlock("hi")));

      assertThatThrownBy(() -> poisonedCodec.writeMessage(message))
          .isInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void wraps_a_deserialization_failure_as_an_illegal_argument_exception_naming_the_cause() {
      String notJson = "{not valid json";

      assertThatThrownBy(() -> codec.readMessage(notJson))
          .isInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(JsonProcessingException.class);
    }
  }
}
