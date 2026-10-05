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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

class ApprovalsPageTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
  private static final Pattern INPUT_NAME = Pattern.compile("<input[^>]*name=\"([^\"]+)\"");

  /**
   * What Nessy knows, as the page sees it: the approval requests an agent is waiting on, and an
   * answer that lands only while its request is still among them -- the one rule the real {@link
   * Replies} keeps that the page depends on.
   */
  private static final class Books implements AgentWork, Replies {

    private final List<ApprovalRequest> waiting = new ArrayList<>();
    private final List<IdempotencyKey> answered = new ArrayList<>();
    private ApprovalResult lastResult;
    private AgentType lastType;
    private AgentId lastId;

    @Override
    public AgentStatus status(AgentType type, AgentId id) {
      return new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0);
    }

    @Override
    public List<ApprovalRequest> waitingApprovals() {
      return List.copyOf(waiting);
    }

    @Override
    public List<ApprovalRequest> waitingApprovals(AgentType type) {
      return waiting.stream().filter(request -> request.agentType().equals(type)).toList();
    }

    @Override
    public ReplyOutcome approve(
        AgentType type, AgentId id, IdempotencyKey key, ApprovalResult result) {
      boolean removed =
          waiting.removeIf(
              request ->
                  request.agentType().equals(type)
                      && request.agentId().equals(id)
                      && request.idempotencyKey().equals(key));
      if (!removed) {
        return new ReplyOutcome.Ignored();
      }
      answered.add(key);
      lastResult = result;
      lastType = type;
      lastId = id;
      return new ReplyOutcome.Applied();
    }

    @Override
    public ReplyOutcome complete(
        AgentType type, AgentId id, IdempotencyKey key, ToolResult result) {
      throw new AssertionError("the page answers approvals, not tool calls");
    }
  }

  private final AgentId house = AgentId.random();
  private final IdempotencyKey first = IdempotencyKey.of(UUID.randomUUID());
  private final Books books = new Books();
  private MockMvc mvc;

  private ApprovalRequest request(IdempotencyKey key, AgentId agent) {
    return new ApprovalRequest(
            Watchman.TYPE,
            agent,
            TurnId.of(1),
            new CallId("call-1"),
            key,
            new ToolName("prune_images"),
            "{}",
            "docker image prune -af",
            NOW.minusSeconds(7200),
            NOW.plusSeconds(3600))
        .fact("risk.level", "MODERATE");
  }

  @BeforeEach
  void renderTheRealTemplates() {
    books.waiting.add(request(first, house));
    // Null for the transcript's history, which only the notes page reads.
    var controller = new ApprovalsController(books, books, null, Clock.fixed(NOW, ZoneOffset.UTC));
    mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(thymeleaf()).build();
  }

  private static ThymeleafViewResolver thymeleaf() {
    var templates = new SpringResourceTemplateResolver();
    templates.setPrefix("classpath:/templates/");
    templates.setSuffix(".html");
    templates.setApplicationContext(new StaticWebApplicationContext());
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(templates);
    var resolver = new ThymeleafViewResolver();
    resolver.setTemplateEngine(engine);
    return resolver;
  }

  @Test
  @DisplayName(
      "a waiting approval request draws with the tool, the action, the facts, the agent, when it"
          + " was asked, its deadline and how long it has waited")
  void the_page_draws_a_waiting_approval_request() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("prune_images")))
        .andExpect(content().string(containsString("docker image prune -af")))
        .andExpect(content().string(containsString("risk.level")))
        .andExpect(content().string(containsString("MODERATE")))
        .andExpect(content().string(containsString(house.value().toString())))
        .andExpect(content().string(containsString(NOW.minusSeconds(7200).toString())))
        .andExpect(content().string(containsString(NOW.plusSeconds(3600).toString())))
        .andExpect(content().string(containsString("2h 0m")));
  }

  @Test
  @DisplayName("with nothing waiting the page says so")
  void the_page_says_when_nothing_is_waiting() throws Exception {
    books.waiting.clear();
    mvc.perform(get("/")).andExpect(content().string(containsString("Nothing is waiting.")));
  }

  @Test
  @DisplayName("every field the page renders is one the handler actually reads")
  void the_form_fields_match_the_parameters_the_controller_binds() throws Exception {
    // The bug this exists for: the deny form sent "reason" and the handler read "note", so a
    // denial a person typed bound to nothing and was recorded as the literal "denied". Nothing
    // failed -- not the compiler, not a test, not the page. The reason simply vanished.
    String page = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
    for (Form form : formsIn(page)) {
      Set<String> bound = parametersOf(form.action());
      assertThat(form.fields())
          .as("fields the page posts to %s that no handler binds", form.action())
          .allSatisfy(field -> assertThat(bound).contains(field));
    }
  }

  private record Form(String action, Set<String> fields) {}

  private static List<Form> formsIn(String html) {
    List<Form> forms = new ArrayList<>();
    int from = 0;
    while (true) {
      int open = html.indexOf("<form", from);
      if (open < 0) {
        break;
      }
      int close = html.indexOf("</form>", open);
      if (close < 0) {
        break;
      }
      String block = html.substring(open, close);
      from = close + "</form>".length();
      Set<String> fields = new LinkedHashSet<>();
      Matcher input = INPUT_NAME.matcher(block);
      while (input.find()) {
        fields.add(input.group(1));
      }
      forms.add(new Form(actionOf(block), fields));
    }
    assertThat(forms).as("the page rendered no forms at all").isNotEmpty();
    return forms;
  }

  private static String actionOf(String block) {
    Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(block);
    return action.find() ? action.group(1) : "";
  }

  private static Set<String> parametersOf(String action) {
    String verb = action.startsWith("/deny") ? "deny" : "approve";
    for (Method method : ApprovalsController.class.getDeclaredMethods()) {
      if (!method.getName().equals(verb)) {
        continue;
      }
      Set<String> names = new LinkedHashSet<>();
      for (Parameter parameter : method.getParameters()) {
        RequestParam bound = parameter.getAnnotation(RequestParam.class);
        if (bound != null) {
          names.add(bound.name().isEmpty() ? parameter.getName() : bound.name());
        }
      }
      return names;
    }
    throw new AssertionError("no handler named " + verb);
  }

  @Test
  @DisplayName("an id that is not one is the caller's mistake, not a 500")
  void a_malformed_id_in_the_address_bar_is_a_bad_request() throws Exception {
    // An agent id is a UUID. Without a handler this leaves the controller as an
    // IllegalArgumentException and reaches the operator as "the watchman is broken".
    mvc.perform(post("/approve/not-a-uuid").param("agentId", house.value().toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the buttons post to a URL naming the call by its idempotency key")
  void the_decide_links_carry_the_calls_key() throws Exception {
    // A call id is unique only within one model reply; the key is unique across every call.
    mvc.perform(get("/"))
        .andExpect(content().string(containsString("/approve/" + first)))
        .andExpect(content().string(containsString("/deny/" + first)));
  }

  @Test
  @DisplayName("the form posts the agent type and the agent id that address the call")
  void the_form_names_the_agent() throws Exception {
    mvc.perform(get("/"))
        .andExpect(content().string(containsString("name=\"agentType\"")))
        .andExpect(content().string(containsString("value=\"" + Watchman.TYPE.value() + "\"")))
        .andExpect(content().string(containsString("name=\"agentId\"")))
        .andExpect(content().string(containsString("value=\"" + house.value() + "\"")));
  }

  @Nested
  @DisplayName("answering from the page")
  class Answering {

    private String approve(IdempotencyKey key) throws Exception {
      return mvc.perform(
              post("/approve/" + key)
                  .param("agentType", Watchman.TYPE.value())
                  .param("agentId", house.value().toString()))
          .andReturn()
          .getResponse()
          .getRedirectedUrl();
    }

    @Test
    @DisplayName("approving answers the call Nessy is waiting on, by agent and key")
    void approving_applies_the_answer() throws Exception {
      mvc.perform(
              post("/approve/" + first)
                  .param("agentType", Watchman.TYPE.value())
                  .param("agentId", house.value().toString()))
          .andExpect(redirectedUrl("/"))
          .andExpect(flash().attributeCount(0));

      assertThat(books.answered).containsExactly(first);
      assertThat(books.lastType).isEqualTo(Watchman.TYPE);
      assertThat(books.lastId).isEqualTo(house);
      assertThat(books.lastResult).isEqualTo(ApprovalResult.approved());
      mvc.perform(get("/")).andExpect(model().attribute("rows", List.of()));
    }

    @Test
    @DisplayName("a denial carries the reason the person typed")
    void denying_carries_the_reason() throws Exception {
      mvc.perform(
              post("/deny/" + first)
                  .param("agentType", Watchman.TYPE.value())
                  .param("agentId", house.value().toString())
                  .param("reason", "that seems dangerous"))
          .andExpect(redirectedUrl("/"));

      assertThat(books.lastResult).isEqualTo(ApprovalResult.denied("that seems dangerous"));
    }

    @Test
    @DisplayName("a second answer is told the approval was no longer waiting")
    void a_second_answer_is_told_it_was_not_waiting() throws Exception {
      assertThat(approve(first)).isEqualTo("/");

      mvc.perform(
              post("/approve/" + first)
                  .param("agentType", Watchman.TYPE.value())
                  .param("agentId", house.value().toString()))
          .andExpect(redirectedUrl("/"))
          .andExpect(flash().attribute("notice", "That approval was no longer waiting."));
      assertThat(books.answered).containsExactly(first);
    }

    @Test
    @DisplayName("an answer for an agent that is not the one asking is ignored, not applied")
    void an_answer_names_the_agent_that_asked() throws Exception {
      mvc.perform(
              post("/approve/" + first)
                  .param("agentType", Watchman.TYPE.value())
                  .param("agentId", UUID.randomUUID().toString()))
          .andExpect(flash().attribute("notice", "That approval was no longer waiting."));

      assertThat(books.answered).isEmpty();
      assertThat(books.waitingApprovals()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("telling two agents' approval requests apart")
  class Identity {

    @Test
    @DisplayName("two agents waiting on the same call id are two rows, answered separately")
    void a_call_id_is_only_unique_within_one_response() throws Exception {
      AgentId other = new AgentId(UUID.randomUUID());
      IdempotencyKey second = IdempotencyKey.of(UUID.randomUUID());
      books.waiting.add(request(second, other));

      mvc.perform(get("/"))
          .andExpect(content().string(containsString("/approve/" + first)))
          .andExpect(content().string(containsString("/approve/" + second)));

      mvc.perform(
          post("/approve/" + second)
              .param("agentType", Watchman.TYPE.value())
              .param("agentId", other.value().toString()));

      assertThat(books.answered).containsExactly(second);
      assertThat(books.waitingApprovals())
          .extracting(ApprovalRequest::idempotencyKey)
          .containsExactly(first);
    }
  }

  @Test
  @DisplayName("the page lists what Nessy lists for the watchman's type only")
  void the_page_lists_the_watchmans_own_approvals() throws Exception {
    AgentId stranger = AgentId.random();
    books.waiting.add(
        new ApprovalRequest(
            new AgentType("someone-else"),
            stranger,
            TurnId.of(1),
            new CallId("call-9"),
            IdempotencyKey.of(UUID.randomUUID()),
            new ToolName("other_tool"),
            "{}",
            "something else entirely",
            NOW,
            NOW.plusSeconds(60)));

    mvc.perform(get("/"))
        .andExpect(content().string(containsString("docker image prune -af")))
        .andExpect(content().string(not(containsString("something else entirely"))));
  }
}
