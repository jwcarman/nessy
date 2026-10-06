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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one {@link AgentWork} bean the starter offers, over whichever doors the application has.
 *
 * <p>Every test drives a real {@link ApplicationContextRunner}, with the in-memory backends the
 * starter falls back to, so the approvals parked here are real rows and the turns real events.
 */
@DisplayName("The starter's agent work")
class AgentWorkAutoConfigurationTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);

  record Query(String q) {}

  /**
   * A model that asks for one tool call and then answers. Told to hold, it waits for a release
   * first, so a test can look at an agent that is in the middle of a turn.
   */
  static class ScriptedModel implements InferenceProvider {

    final CountDownLatch inModel = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean hold;

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      if (hold) {
        inModel.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
        return new InferenceResult.Answer(List.of(new Block.Text("done")));
      }
      boolean asked = request.context().turns().stream().anyMatch(t -> !t.exchanges().isEmpty());
      return asked
          ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
          : new InferenceResult.Actions(
              List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AModel {

    @Bean
    ScriptedModel model() {
      return new ScriptedModel();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationsOwnWork {

    static final AgentWork INSTANCE =
        new AgentWork() {
          @Override
          public AgentStatus status(AgentType type, AgentId id) {
            return new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0);
          }

          @Override
          public List<ApprovalRequest> waitingApprovals() {
            return List.of();
          }

          @Override
          public List<ApprovalRequest> waitingApprovals(AgentType type) {
            return List.of();
          }
        };

    @Bean
    AgentWork myWork() {
      return INSTANCE;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ADirectBackendOnly {

    @Bean
    InMemoryDirectBackend directBackend() {
      return new InMemoryDirectBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
    }
  }

  private final ApplicationContextRunner runner =
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
          .withPropertyValues(
              "nessy.model=a-test-model",
              "nessy.provider=model",
              "nessy.system-prompt=you are a test assistant");

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

  @Nested
  @DisplayName("With a queued backend")
  class WithAQueuedBackend {

    @Test
    void the_bean_reports_status_and_lists_a_waiting_approval() {
      AgentType type = new AgentType("work-queued");
      runner.run(
          context -> {
            assertThat(context).hasSingleBean(AgentWork.class);
            AgentWork work = context.getBean(AgentWork.class);
            DefaultQueuedHarnessFactory factory =
                context.getBean(DefaultQueuedHarnessFactory.class);
            QueuedHarness<String> harness =
                factory.create(
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
                                            request -> Awaited.deferred(),
                                            a -> a.timeout(Duration.ofMinutes(30))))
                            .effects(e -> e.pollInterval(Duration.ofMillis(20))));
            AgentId agent = AgentId.random();
            assertThat(work.status(type, agent).activity()).isEqualTo(Activity.IDLE);

            harness.tell(agent, "go");

            await()
                .atMost(PATIENT)
                .untilAsserted(
                    () ->
                        assertThat(work.status(type, agent).activity())
                            .isEqualTo(Activity.WAITING));
            List<ApprovalRequest> waiting = work.waitingApprovals(type);
            assertThat(waiting).hasSize(1);
            assertThat(waiting.getFirst().agentId()).isEqualTo(agent);
            assertThat(work.waitingApprovals()).isEqualTo(waiting);
            assertThat(work.status(type, agent).waitingApprovals()).isEqualTo(waiting);
          });
    }

    @Test
    void an_agent_only_the_direct_store_holds_is_answered_from_it() {
      AgentType type = new AgentType("work-direct-held");
      runner.run(
          context -> {
            AgentWork work = context.getBean(AgentWork.class);
            ScriptedModel model = context.getBean(ScriptedModel.class);
            model.hold = true;
            DefaultDirectHarnessFactory factory =
                context.getBean(DefaultDirectHarnessFactory.class);
            DirectHarness<String, String> harness =
                factory.<String>create(
                    type, config -> config.inputRenderer(text -> List.of(new Block.Text(text))));
            AgentId agent = AgentId.random();
            Thread asking = Thread.ofVirtual().start(() -> harness.ask(agent, "go"));
            assertThat(model.inModel.await(20, TimeUnit.SECONDS)).isTrue();

            AgentStatus status = work.status(type, agent);
            model.release.countDown();
            asking.join();

            assertThat(status)
                .isEqualTo(
                    new AgentStatus(Activity.WORKING, 0, Optional.of(new TurnId(1)), List.of(), 0));
          });
    }
  }

  @Nested
  @DisplayName("With only a direct backend")
  class WithOnlyADirectBackend {

    @Test
    void status_works_and_nothing_is_waiting() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AgentWorkAutoConfiguration.class))
          .withUserConfiguration(ADirectBackendOnly.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(AgentWork.class);
                AgentWork work = context.getBean(AgentWork.class);
                AgentType type = new AgentType("work-direct-only");

                AgentStatus status = work.status(type, AgentId.random());

                assertThat(status)
                    .isEqualTo(new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0));
                assertThat(work.waitingApprovals()).isEmpty();
                assertThat(work.waitingApprovals(type)).isEmpty();
              });
    }
  }

  @Nested
  @DisplayName("Whatever the application has")
  class WhateverTheApplicationHas {

    @Test
    void an_applications_own_agent_work_is_used_in_its_place() {
      runner
          .withUserConfiguration(AnApplicationsOwnWork.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(AgentWork.class);
                assertThat(context.getBean(AgentWork.class))
                    .isSameAs(AnApplicationsOwnWork.INSTANCE);
              });
    }

    @Test
    void it_is_not_offered_when_there_is_no_backend() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AgentWorkAutoConfiguration.class))
          .run(context -> assertThat(context).doesNotHaveBean(AgentWork.class));
    }
  }
}
