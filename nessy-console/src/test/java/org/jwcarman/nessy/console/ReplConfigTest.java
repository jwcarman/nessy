package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.EffectsConfig;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ApproverConfig;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("A REPL's configuration")
class ReplConfigTest {

  private final ReplConfig config = new ReplConfig();

  @Nested
  @DisplayName("the database")
  class TheDatabase {

    /**
     * There used to be a default -- an embedded H2 -- and the engine's schema does not load on H2
     * at all. Absent here means "use the Boot context's", and the REPL says so if there is none.
     */
    @Test
    @DisplayName("is absent until somebody supplies one, because there is no honest default")
    void has_no_default() {
      assertThat(config.dataSource()).isEmpty();
    }

    @Test
    @DisplayName("is the caller's own when they have one, which is the whole point")
    void a_supplied_database_is_the_one_used() {
      // Never queried here: any DataSource object proves the plumbing, and this one needs no
      // driver.
      DataSource mine = new DriverManagerDataSource();
      config.dataSource(mine);
      assertThat(config.dataSource()).containsSame(mine);
    }

    @Test
    void null_is_refused_rather_than_silently_meaning_the_default() {
      assertThatThrownBy(() -> config.dataSource(null)).isInstanceOf(NullPointerException.class);
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

    /** A random id would start a fresh conversation on every launch. */
    @Test
    void the_default_id_is_the_same_every_time_this_program_runs() {
      assertThat(config.agentId()).isEqualTo(new ReplConfig().agentId());
    }

    @Test
    void a_supplied_agent_type_is_the_one_used() {
      AgentType mine = new AgentType("watchman");
      config.agent(mine);
      assertThat(config.type()).isSameAs(mine);
    }

    @Test
    void a_supplied_agent_id_is_the_one_used() {
      AgentId mine = new AgentId(UUID.randomUUID());
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

    /** Records what reached it; every other method is a no-op that returns itself. */
    private static final class RecordingHarnessConfig implements HarnessConfig<String> {
      private final List<Tool<?>> ungated = new ArrayList<>();
      private final List<Tool<?>> bound = new ArrayList<>();

      @Override
      public HarnessConfig<String> agentType(AgentType agentType) {
        return this;
      }

      @Override
      public HarnessConfig<String> systemPrompt(String prompt) {
        return this;
      }

      @Override
      public HarnessConfig<String> systemPrompt(SystemPromptSource source) {
        return this;
      }

      @Override
      public HarnessConfig<String> observationRenderer(ObservationRenderer<String> renderer) {
        return this;
      }

      @Override
      public HarnessConfig<String> observationCoalescer(ObservationCoalescer<String> coalescer) {
        return this;
      }

      @Override
      public HarnessConfig<String> inference(Consumer<InferenceConfig> customizer) {
        return this;
      }

      @Override
      public HarnessConfig<String> effects(Consumer<EffectsConfig> customizer) {
        return this;
      }

      @Override
      public HarnessConfig<String> listener(AgentEventListener listener) {
        return this;
      }

      @Override
      public <I> HarnessConfig<String> tool(Tool<I> tool) {
        ungated.add(tool);
        return this;
      }

      @Override
      public <I> HarnessConfig<String> tool(Tool<I> tool, Consumer<ToolConfig<I>> customizer) {
        bound.add(tool);
        customizer.accept(
            new ToolConfig<I>() {
              @Override
              public ToolConfig<I> timeout(Duration timeout) {
                return this;
              }

              @Override
              public ToolConfig<I> retryPolicy(RetryPolicy retryPolicy) {
                return this;
              }

              @Override
              public ToolConfig<I> action(ActionRenderer<I> action) {
                return this;
              }

              @Override
              public ToolConfig<I> enrich(ApprovalEnricher enricher) {
                return this;
              }

              @Override
              public ToolConfig<I> approver(Approver approver, Consumer<ApproverConfig> c) {
                return this;
              }
            });
        return this;
      }
    }

    private static Tool<String> doNothingTool() {
      return new Tool<>() {
        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public ToolName name() {
          return new ToolName("noop");
        }

        @Override
        public String description() {
          return "does nothing";
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
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
