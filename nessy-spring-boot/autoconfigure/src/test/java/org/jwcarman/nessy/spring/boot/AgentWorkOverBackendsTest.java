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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Review proofs for the starter's agent work. */
@Tag("container")
@DisplayName("Review proofs: the starter's agent work")
class AgentWorkOverBackendsTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  record Query(String q) {}

  @Configuration(proxyBeanMethods = false)
  static class AModel {

    @Bean
    InferenceProvider model() {
      return (request, narrator) -> {
        boolean asked = request.context().turns().stream().anyMatch(t -> !t.exchanges().isEmpty());
        return asked
            ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
            : new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ADatabase {

    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AQueuedBackendOnly {

    @Bean
    QueuedBackend queuedBackend() {
      return new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
    }
  }

  private static final String[] PROPERTIES = {
    "nessy.model=a-test-model", "nessy.provider=model", "nessy.system-prompt=you are a test"
  };

  private static Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("found")));
      }
    };
  }

  private static QueuedHarness<String> parking(
      DefaultQueuedHarnessFactory factory,
      AgentType type,
      AtomicReference<ApprovalRequest> handed) {
    return factory.create(
        type,
        String.class,
        config ->
            config
                .systemPrompt("You are a test assistant.")
                .tool(
                    lookup(),
                    t ->
                        t.action(query -> "look up " + query.q())
                            .approver(
                                request -> {
                                  handed.set(request);
                                  return Awaited.deferred();
                                },
                                a -> a.timeout(Duration.ofMinutes(30))))
                .effects(e -> e.pollInterval(Duration.ofMillis(20))));
  }

  @Test
  void both_jdbc_backends_on_one_database_count_a_waiting_approval_once() {
    AgentType type = new AgentType("work-jdbc-both");
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class,
                DirectHarnessAutoConfiguration.class,
                AgentWorkAutoConfiguration.class))
        .withUserConfiguration(AModel.class, ADatabase.class)
        .withPropertyValues(PROPERTIES)
        .run(
            context -> {
              assertThat(context.getBean(QueuedBackend.class).getClass().getSimpleName())
                  .startsWith("Jdbc");
              assertThat(context.getBean(DirectBackend.class).getClass().getSimpleName())
                  .startsWith("Jdbc");
              AgentWork work = context.getBean(AgentWork.class);
              AtomicReference<ApprovalRequest> handed = new AtomicReference<>();
              QueuedHarness<String> harness =
                  parking(context.getBean(DefaultQueuedHarnessFactory.class), type, handed);
              AgentId agent = AgentId.random();

              harness.tell(agent, "go");

              await()
                  .atMost(Duration.ofSeconds(30))
                  .untilAsserted(
                      () ->
                          assertThat(work.status(type, agent).activity())
                              .isEqualTo(Activity.WAITING));
              assertThat(work.waitingApprovals(type)).hasSize(1);
              assertThat(work.waitingApprovals().stream().filter(r -> r.agentId().equals(agent)))
                  .hasSize(1);
              assertThat(work.status(type, agent).waitingApprovals()).hasSize(1);
            });
  }

  @Test
  void a_waiting_approval_reads_back_with_the_tool_the_call_and_the_key_the_approver_was_handed() {
    AgentType type = new AgentType("work-readback");
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class,
                DirectHarnessAutoConfiguration.class,
                AgentWorkAutoConfiguration.class))
        .withUserConfiguration(AModel.class)
        .withPropertyValues(PROPERTIES)
        .run(
            context -> {
              AgentWork work = context.getBean(AgentWork.class);
              AtomicReference<ApprovalRequest> handed = new AtomicReference<>();
              QueuedHarness<String> harness =
                  parking(context.getBean(DefaultQueuedHarnessFactory.class), type, handed);
              AgentId agent = AgentId.random();

              harness.tell(agent, "go");

              await()
                  .atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(work.waitingApprovals(type)).hasSize(1));
              ApprovalRequest read = work.waitingApprovals(type).getFirst();
              ApprovalRequest given = handed.get();
              assertThat(given).isNotNull();
              assertThat(read.toolName()).isEqualTo(new ToolName("lookup"));
              assertThat(read.toolName()).isEqualTo(given.toolName());
              assertThat(read.callId().value()).isEqualTo("call_1");
              assertThat(read.callId()).isEqualTo(given.callId());
              assertThat(read.idempotencyKey()).isEqualTo(given.idempotencyKey());
              assertThat(read.turn()).isEqualTo(given.turn());
              assertThat(read.agentType()).isEqualTo(type);
              assertThat(read.agentId()).isEqualTo(agent);
              assertThat(read.arguments()).isEqualTo(given.arguments());
              assertThat(read.action()).isEqualTo(given.action());
              assertThat(read.action()).isEqualTo("look up loch ness");
            });
  }

  @Test
  void with_only_a_queued_backend_the_bean_reports_status_and_lists_a_waiting_approval() {
    AgentType type = new AgentType("work-queued-only");
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class,
                AgentWorkAutoConfiguration.class))
        .withUserConfiguration(AModel.class, AQueuedBackendOnly.class)
        .withPropertyValues(PROPERTIES)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DirectBackend.class);
              assertThat(context).hasSingleBean(AgentWork.class);
              AgentWork work = context.getBean(AgentWork.class);
              AtomicReference<ApprovalRequest> handed = new AtomicReference<>();
              QueuedHarness<String> harness =
                  parking(context.getBean(DefaultQueuedHarnessFactory.class), type, handed);
              AgentId agent = AgentId.random();
              assertThat(work.status(type, agent).activity()).isEqualTo(Activity.IDLE);

              harness.tell(agent, "go");

              await()
                  .atMost(Duration.ofSeconds(30))
                  .untilAsserted(
                      () ->
                          assertThat(work.status(type, agent).activity())
                              .isEqualTo(Activity.WAITING));
              assertThat(work.waitingApprovals(type)).hasSize(1);
              assertThat(work.waitingApprovals().getFirst().agentId()).isEqualTo(agent);
            });
  }
}
