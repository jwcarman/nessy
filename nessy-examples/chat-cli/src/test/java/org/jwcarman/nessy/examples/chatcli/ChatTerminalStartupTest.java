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
package org.jwcarman.nessy.examples.chatcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The terminal itself, started: its runner builds the chat harness from the starter's factory and
 * then reads a console that is already at end of input, so the loop ends at once. Starting is the
 * whole assertion -- a harness that refuses to build fails the context.
 */
@Tag("container")
@SpringBootTest(
    properties = {
      "chat.terminal=true",
      "nessy.provider=scriptedModel",
      "nessy.model=scripted",
      "spring.docker.compose.enabled=false"
    })
@Import(ChatTerminalStartupTest.Infrastructure.class)
class ChatTerminalStartupTest {

  static {
    // Before the context: the console reader is made on first use, over System.in.
    System.setIn(new ByteArrayInputStream(new byte[0]));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Infrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    InferenceProvider scriptedModel() {
      return (InferenceRequest request, InferenceNarrator narrator) ->
          new InferenceResult.Answer(List.of(new Block.Text("hello from the script")));
    }
  }

  @Autowired private CommandLineRunner terminal;

  @Test
  void the_terminal_builds_its_harness_on_the_starters_factory_and_starts() {
    assertThat(terminal).isNotNull();
  }
}
