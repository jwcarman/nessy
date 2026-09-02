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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.spi.store.Schemas;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * The page renders, with every property its template names.
 *
 * <p>Worth its own test because a Thymeleaf expression naming a property that does not exist fails
 * at RUNTIME: everything compiles, every other test passes, and the operator meets a stack trace on
 * the one screen that has to work. It caught exactly that — the template asked for {@code
 * row.agentType} while {@code Row} had no such component.
 *
 * <p>Standalone rather than {@code @WebMvcTest}: the two collaborators the GET never touches need
 * an actor system to exist, and the no-mocking-library promise holds here as everywhere. The
 * repository is the real one over H2, and the template engine is the real one reading the shipped
 * file.
 */
class ApprovalsPageTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

  private MockMvc mvc;
  private PendingApprovalsRepository approvals;
  private ApprovalsController controller;

  @BeforeEach
  void renderTheRealTemplates() {
    var database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    Schemas.initialize(database);
    approvals = new PendingApprovalsRepository(new JdbcTemplate(database));
    approvals.asked(
        new PendingApproval(
            CallId.of("call-1"),
            AgentType.of("watchman"),
            AgentId.of("house-12"),
            "prune_images",
            "docker image prune -af",
            NOW.minusSeconds(7200),
            NOW.plusSeconds(3600),
            "token-1",
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));

    // Null for the two the read path never reaches: answering is the POST handlers' business, and
    // both need a running engine. A null here fails loudly if that ever stops being true.
    controller = new ApprovalsController(approvals, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
    mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(thymeleaf()).build();
  }

  private static ThymeleafViewResolver thymeleaf() {
    var templates = new SpringResourceTemplateResolver();
    templates.setPrefix("classpath:/templates/");
    templates.setSuffix(".html");
    templates.setApplicationContext(
        new org.springframework.web.context.support.StaticWebApplicationContext());
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(templates);
    var resolver = new ThymeleafViewResolver();
    resolver.setTemplateEngine(engine);
    return resolver;
  }

  @Test
  @DisplayName("a waiting question draws with the agent, the action, and how long it has waited")
  void the_page_draws_a_waiting_question() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("docker image prune -af")))
        .andExpect(content().string(containsString("house-12")))
        .andExpect(content().string(containsString("2h 0m")));
  }

  @Test
  @DisplayName("every field the page renders is one the handler actually reads")
  void the_form_fields_match_the_parameters_the_controller_binds() throws Exception {
    // The bug this exists for: the deny form sent "reason" and the handler read "note", so a
    // denial a person typed bound to nothing and was recorded as the literal "denied". Nothing
    // failed — not the compiler, not a test, not the page. The reason simply vanished.
    String page = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

    for (Form form : formsIn(page)) {
      Set<String> bound = parametersOf(form.action());
      assertThat(form.fields())
          .as("fields the page posts to %s that no handler binds", form.action())
          .allSatisfy(field -> assertThat(bound).contains(field));
    }
  }

  /** One form's action path and the names it posts. */
  private record Form(String action, Set<String> fields) {}

  private static List<Form> formsIn(String html) {
    List<Form> forms = new ArrayList<>();
    Matcher form =
        Pattern.compile("<form[^>]*action=\"([^\"]+)\"(.*?)</form>", Pattern.DOTALL).matcher(html);
    while (form.find()) {
      Set<String> fields = new LinkedHashSet<>();
      Matcher input = Pattern.compile("<input[^>]*name=\"([^\"]+)\"").matcher(form.group(2));
      while (input.find()) {
        fields.add(input.group(1));
      }
      forms.add(new Form(form.group(1), fields));
    }
    assertThat(forms).as("the page rendered no forms at all").isNotEmpty();
    return forms;
  }

  /**
   * The request parameters the handler for {@code action} binds, read off the controller itself.
   *
   * <p>Reflection rather than a second list to keep in step: a list would be one more thing that
   * can drift from the code, which is the failure being tested.
   */
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
  @DisplayName("an id the identifier rule refuses is the caller's mistake, not a 500")
  void a_malformed_id_in_the_address_bar_is_a_bad_request() throws Exception {
    // AgentId refuses a space. Without a handler this leaves the controller as an
    // IllegalArgumentException and reaches the operator as "the watchman is broken".
    mvc.perform(post("/approve/watchman/has a space/call-1")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the buttons post to a URL naming the type, the agent AND the call")
  void the_decide_links_carry_the_whole_identity() throws Exception {
    // The id alone cannot find the row it came from: an id is unique only within its type.
    mvc.perform(get("/"))
        .andExpect(content().string(containsString("/approve/watchman/house-12/call-1")))
        .andExpect(content().string(containsString("/deny/watchman/house-12/call-1")));
  }

  @Nested
  @DisplayName("the page a decision redirects to")
  class ReadYourWrites {

    // The projection's writer is the listener, which records a decision when the agent narrates
    // it -- measured at 38ms after the engine accepted it on a live watchman. The redirect lands
    // inside that window, so the person who just clicked is the one guaranteed to see the question
    // they have already answered still sitting on the board.

    @Test
    @DisplayName("the row is gone from the board once the decision has been recorded")
    void answering_takes_the_question_off_the_board() {
      assertThat(approvals.pending()).hasSize(1);

      controller.recordLocally(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("call-1"),
          ApprovalResult.denied("that seems dangerous"));

      assertThat(approvals.pending()).isEmpty();
    }

    @Test
    @DisplayName("the reason survives, so the two writers agree rather than race")
    void the_recorded_answer_is_the_one_that_was_sent() {
      controller.recordLocally(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("call-1"),
          ApprovalResult.denied("that seems dangerous"));

      var row =
          approvals
              .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("call-1"))
              .orElseThrow();

      assertThat(row.answer()).contains("denied");
      assertThat(row.note()).contains("that seems dangerous");
    }

    @Test
    @DisplayName("the listener writing the same decision afterwards changes nothing")
    void whichever_writer_arrives_second_is_a_no_op() {
      controller.recordLocally(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("call-1"),
          ApprovalResult.denied("that seems dangerous"));

      // What the listener does when the agent narrates ApprovalDecided a few milliseconds later.
      approvals.answered(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("call-1"),
          "denied",
          "that seems dangerous",
          NOW.plusSeconds(1));

      var row =
          approvals
              .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("call-1"))
              .orElseThrow();
      assertThat(row.answeredAt()).contains(NOW);
      assertThat(approvals.pending()).isEmpty();
    }
  }
}
