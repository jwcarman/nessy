package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** A tool bound on the harness by hand is observed by the harness, approver included. */
@DisplayName("The harness observes its tools")
class HarnessObservesToolsTest {

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry observations = ObservationRegistry.create();

  private static final Tool<String> ECHO =
      new Tool<>() {
        @Override
        public ToolName name() {
          return new ToolName("echo");
        }

        @Override
        public String description() {
          return "says it back";
        }

        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public InputSchema inputSchema(InputSchemaGenerator generator) {
          return new InputSchema("{\"type\":\"string\"}");
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text(request.input())));
        }
      };

  private ToolBinding<?> named(String name) {
    return lastConfig.tools().find(new ToolName(name)).orElseThrow();
  }

  private DefaultHarnessConfig<String> lastConfig;

  private ToolBinding<?> bound() {
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    DefaultHarnessConfig<String> config =
        new DefaultHarnessConfig<>(
            new TypeRef<String>() {},
            new DefaultHarnessConfig.Defaults(
                (_, _) -> new InferenceResult.Answer(List.of(new Block.Text("ok"))),
                InferenceOptions.of("m")),
            JsonMapper.builder().build(),
            type -> new InputSchema("{}"),
            observations);
    config.tool(ECHO, t -> t.approver(_ -> Awaited.ready(ApprovalResult.approved()), a -> {}));
    config.tool(UNGATED, t -> {});
    lastConfig = config;
    return named("echo");
  }

  private static final Tool<String> UNGATED =
      new Tool<>() {
        @Override
        public ToolName name() {
          return new ToolName("ungated");
        }

        @Override
        public String description() {
          return "nobody is asked";
        }

        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public InputSchema inputSchema(InputSchemaGenerator generator) {
          return new InputSchema("{\"type\":\"string\"}");
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text(request.input())));
        }
      };

  private String tag(String name, String key) {
    return stopped.stream()
        .filter(c -> c.getName().equals(name))
        .map(c -> c.getLowCardinalityKeyValue(key))
        .filter(java.util.Objects::nonNull)
        .map(kv -> kv.getValue())
        .findFirst()
        .orElse(null);
  }

  @Test
  void a_call_through_the_binding_is_an_execute_tool_span() {
    ToolBinding<?> binding = bound();
    AgentType type = new AgentType("chat");
    AgentId agent = new AgentId(UUID.randomUUID());

    Awaited<ToolResult> answer =
        binding.call(
            type,
            agent,
            new TurnId(1),
            new CallId("c1"),
            new ToolName("echo"),
            "\"hi\"",
            Instant.now().plusSeconds(30),
            new ReplyToken("unused"));

    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    assertThat(tag("gen_ai.client.operation.duration", "gen_ai.operation.name"))
        .isEqualTo("execute_tool");
    assertThat(tag("gen_ai.client.operation.duration", "gen_ai.tool.name")).isEqualTo("echo");
    assertThat(tag("gen_ai.client.operation.duration", "nessy.tool.outcome")).isEqualTo("success");
  }

  @Test
  void an_approval_through_the_binding_is_a_nessy_approval_span() {
    ToolBinding<?> binding = bound();
    ApprovalRequest question =
        new ApprovalRequest(
            new AgentType("chat"),
            new AgentId(UUID.randomUUID()),
            new TurnId(1),
            new CallId("c1"),
            new ToolName("echo"),
            "{}",
            "echo hi",
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(3600),
            new ReplyToken("unused"));

    Awaited<ApprovalResult> answer = binding.approve(question);

    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    assertThat(tag("nessy.approval", "nessy.approval.answer")).isEqualTo("approved");
    assertThat(tag("nessy.approval", "gen_ai.agent.name")).isEqualTo("chat");
  }

  /** The default approver lets everything through and nobody was asked, so it is no span. */
  @Test
  void a_tool_with_no_approver_of_its_own_makes_no_approval_span() {
    bound();
    ToolBinding<?> ungated = named("ungated");
    ungated.approve(
        new ApprovalRequest(
            new AgentType("chat"),
            new AgentId(UUID.randomUUID()),
            new TurnId(1),
            new CallId("c2"),
            new ToolName("ungated"),
            "{}",
            "ungated hi",
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(3600),
            new ReplyToken("unused")));

    assertThat(stopped).noneMatch(c -> c.getName().equals("nessy.approval"));
  }
}
