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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.StorageCodecConfigurer;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.planning.Plan;
import org.jwcarman.nessy.planning.Plans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The application declares one {@link StorageCodecConfigurer}, and the notebook and the plan are
 * built with the {@code CodecFactory} that carries it -- the one the backend is built from -- so
 * what they store is not readable without it.
 */
@SpringBootTest(properties = "nessy.provider=scriptedModels")
@Import(PostgresBacked.class)
@DisplayName("The application's storage codec")
class StorageCodecWiringTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Configuration {

    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.alwaysSaying("Noted.");
    }

    /** Not a cipher; enough to make a stored value visibly not what was written. */
    @Bean
    StorageCodecConfigurer storage() {
      return original ->
          original.andThen(
              new Codec<byte[]>() {
                @Override
                public byte[] encode(byte[] bytes) {
                  return flip(bytes);
                }

                @Override
                public byte[] decode(byte[] bytes) {
                  return flip(bytes);
                }
              });
    }

    private static byte[] flip(byte[] bytes) {
      byte[] out = new byte[bytes.length];
      for (int i = 0; i < bytes.length; i++) {
        out[i] = (byte) (bytes[i] ^ 0x5A);
      }
      return out;
    }
  }

  @Autowired private Plans plans;
  @Autowired private DataSource dataSource;
  @Autowired private CodecFactory codecs;

  private List<String> raw(String sql, AgentId agent) {
    return JdbcClient.create(dataSource)
        .sql(sql)
        .params(agent.value().toString())
        .query(byte[].class)
        .list()
        .stream()
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .toList();
  }

  @Test
  @DisplayName("a note's hook and body are stored through the codec")
  void the_notebook_is_built_with_the_applications_codec() {
    AgentId agent = AgentId.random();
    Notebook notebook = new JdbcNotebook(dataSource, ChatConfiguration.TYPE, codecs);

    Notebook.Entry written =
        notebook.write(agent, "Bluebird-4471 is the favourite", "Ms. Okonkwo-Reyes, 9087-1123");

    assertThat(notebook.find(agent, written.id())).contains(written);
    List<String> hooks = raw("SELECT hook FROM nessy_note WHERE agent_id = ?", agent);
    assertThat(hooks).hasSize(1).noneMatch(hook -> hook.contains("Bluebird"));
    List<String> bodies = raw("SELECT body FROM nessy_note WHERE agent_id = ?", agent);
    assertThat(bodies).hasSize(1).noneMatch(body -> body.contains("Okonkwo"));
  }

  @Test
  @DisplayName("a plan's task titles are stored through the codec")
  void the_plan_is_built_with_the_applications_codec() {
    AgentId agent = AgentId.random();
    Plan plan = new Plan(List.of(new Plan.Task("Email Ms. Okonkwo-Reyes", Plan.Status.PENDING)));

    plans.save(agent, plan);

    assertThat(plans.find(agent)).contains(plan);
    List<String> titles = raw("SELECT title FROM nessy_plan_task WHERE agent_id = ?", agent);
    assertThat(titles).hasSize(1).noneMatch(title -> title.contains("Okonkwo"));
  }
}
