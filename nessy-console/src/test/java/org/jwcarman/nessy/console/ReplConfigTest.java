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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBindingConfig;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * The settings a console application fills in.
 *
 * <p>These two — the substrate and the memory — exist so that {@link Repl} DEFAULTS them rather
 * than owning them: a notebook or a plan is opened over a substrate, and an application that cannot
 * name the one its agent writes to cannot give the agent either.
 */
@DisplayName("A REPL's configuration")
class ReplConfigTest {

  private final ReplConfig config = new ReplConfig();

  @Nested
  @DisplayName("the database")
  class TheDatabase {

    @Test
    @DisplayName("is there without being asked for, so the easy button stays easy")
    void defaults_to_a_database_that_works() {
      assertThat(config.dataSource()).isNotNull();
    }

    @Test
    @DisplayName("is the caller's own when they have one, which is the whole point")
    void a_supplied_database_is_the_one_used() {
      DataSource mine = TestDatabase.fresh();

      config.dataSource(mine);

      assertThat(config.dataSource()).isSameAs(mine);
    }

    @Test
    void two_configurations_do_not_share_a_default_database() {
      assertThat(config.dataSource()).isNotSameAs(new ReplConfig().dataSource());
    }

    @Test
    void null_is_refused_rather_than_silently_meaning_the_default() {
      assertThatThrownBy(() -> config.dataSource(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the memory")
  class TheMemory {

    /** A memory that does nothing, because what it does is not what this asserts. */
    private final Memory mine =
        new Memory() {
          @Override
          public Context recall(AgentId agentId) {
            return Context.of(List.of());
          }

          @Override
          public void remember(AgentId agentId, HistoryMessage message) {
            // nothing to keep
          }

          @Override
          public void forget(AgentId agentId) {
            // Nothing kept here.
          }
        };

    /**
     * Empty rather than a stand-in, so {@link Repl} says nothing to the harness and the harness
     * keeps its OWN default. A default invented here would be a second answer to a question the
     * engine has already answered.
     */
    @Test
    @DisplayName("is unset by default, leaving the choice to the harness")
    void is_absent_until_someone_says_otherwise() {
      assertThat(config.memory()).isEmpty();
    }

    @Test
    void a_supplied_memory_is_the_one_used() {
      config.memory(mine);

      assertThat(config.memory()).containsSame(mine);
    }

    @Test
    void null_is_refused_rather_than_silently_meaning_the_default() {
      assertThatThrownBy(() -> config.memory(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("what is printed")
  class WhatIsPrinted {

    @Test
    void a_supplied_prompt_replaces_the_default() {
      config.prompt("nessy> ");

      assertThat(config.prompt()).isEqualTo("nessy> ");
    }

    @Test
    void a_supplied_system_prompt_is_the_one_used() {
      config.systemPrompt("You are terse.");

      assertThat(config.systemPrompt()).isEqualTo("You are terse.");
    }

    @Test
    void null_prompt_is_refused() {
      assertThatThrownBy(() -> config.prompt(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_system_prompt_is_refused() {
      assertThatThrownBy(() -> config.systemPrompt(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("leaving")
  class Leaving {

    @Test
    @DisplayName("exitOn with no words at all is refused, since a loop with no way out is a trap")
    void an_empty_exit_word_list_is_refused() {
      assertThatThrownBy(config::exitOn).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("which agent is running")
  class WhichAgentIsRunning {

    @Test
    void a_supplied_agent_type_is_the_one_used() {
      AgentType mine = AgentType.of("watchman");

      config.agent(mine);

      assertThat(config.type()).isSameAs(mine);
    }

    @Test
    void a_supplied_agent_id_is_the_one_used() {
      AgentId mine = AgentId.of("second-terminal");

      config.id(mine);

      assertThat(config.agentId()).isSameAs(mine);
    }

    @Test
    void null_agent_type_is_refused() {
      assertThatThrownBy(() -> config.agent(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_agent_id_is_refused() {
      assertThatThrownBy(() -> config.id(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the token limit")
  class TheTokenLimit {

    @Test
    void defaults_to_4096() {
      assertThat(config.maxTokens()).isEqualTo(4096);
    }

    @Test
    void a_supplied_limit_is_the_one_used() {
      config.maxTokens(256);

      assertThat(config.maxTokens()).isEqualTo(256);
    }

    @Test
    @DisplayName("zero is refused, because a turn that may answer with nothing is not a limit")
    void zero_is_refused() {
      assertThatThrownBy(() -> config.maxTokens(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_negative_limit_is_refused() {
      assertThatThrownBy(() -> config.maxTokens(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("granted tools")
  class GrantedTools {

    /** Records what a harness was told, so a test can check what actually reached it. */
    private static final class RecordingHarnessConfig implements HarnessConfig<String> {

      private final List<Tool<?>> ungated = new ArrayList<>();
      private final List<Tool<?>> bound = new ArrayList<>();

      @Override
      public HarnessConfig<String> type(AgentType type) {
        return this;
      }

      @Override
      public HarnessConfig<String> coalescer(BacklogCoalescer<String> coalescer) {
        return this;
      }

      @Override
      public HarnessConfig<String> systemPrompt(String systemPrompt) {
        return this;
      }

      @Override
      public HarnessConfig<String> model(ModelId modelId) {
        return this;
      }

      @Override
      public <I> HarnessConfig<String> tool(Tool<I> tool) {
        ungated.add(tool);
        return this;
      }

      @Override
      public <I> HarnessConfig<String> tool(
          Tool<I> tool, Consumer<ToolBindingConfig<I>> customizer) {
        bound.add(tool);
        customizer.accept(
            new ToolBindingConfig<I>() {
              @Override
              public ToolBindingConfig<I> approver(Approver approver) {
                return this;
              }

              @Override
              public ToolBindingConfig<I> action(ActionRenderer<I> renderer) {
                return this;
              }
            });
        return this;
      }

      @Override
      public HarnessConfig<String> renderer(ObservationRenderer<String> renderer) {
        return this;
      }

      @Override
      public HarnessConfig<String> memory(Memory memory) {
        return this;
      }
    }

    /** Never executed here: these tests are about how a tool is WIRED, not what it does. */
    private static Tool<String> doNothingTool() {
      return new Tool<>() {
        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public String name() {
          return "noop";
        }

        @Override
        public String description() {
          return "does nothing";
        }

        @Override
        public Awaited<ToolResult> execute(ToolCallRequest<String> call) {
          throw new UnsupportedOperationException("never called in this test");
        }
      };
    }

    @Test
    @DisplayName("an ungated tool reaches the harness unchanged")
    void an_ungated_tool_reaches_the_harness() {
      Tool<String> tool = doNothingTool();
      config.tool(tool);
      RecordingHarnessConfig harnessConfig = new RecordingHarnessConfig();

      config.tools().forEach(grant -> grant.accept(harnessConfig));

      assertThat(harnessConfig.ungated).containsExactly(tool);
      assertThat(harnessConfig.bound).isEmpty();
    }

    @Test
    @DisplayName("a bound tool reaches the harness with its own binding customizer applied")
    void a_bound_tool_carries_its_customizer_to_the_harness() {
      Tool<String> tool = doNothingTool();
      List<String> customizations = new ArrayList<>();
      config.tool(tool, binding -> customizations.add("applied"));
      RecordingHarnessConfig harnessConfig = new RecordingHarnessConfig();

      config.tools().forEach(grant -> grant.accept(harnessConfig));

      assertThat(harnessConfig.bound).containsExactly(tool);
      assertThat(customizations).containsExactly("applied");
    }

    @Test
    void null_tool_is_refused() {
      assertThatThrownBy(() -> config.tool(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_customizer_is_refused() {
      Tool<String> tool = doNothingTool();

      assertThatThrownBy(() -> config.tool(tool, null)).isInstanceOf(NullPointerException.class);
    }
  }
}
