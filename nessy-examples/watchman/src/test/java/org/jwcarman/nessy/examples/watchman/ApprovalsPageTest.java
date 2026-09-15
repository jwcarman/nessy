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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

class ApprovalsPageTest extends PostgresBacked {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
  private static final Pattern INPUT_NAME = Pattern.compile("<input[^>]*name=\"([^\"]+)\"");

  private final AgentId house = AgentId.random();
  private MockMvc mvc;
  private PendingApprovalsRepository approvals;
  private ApprovalsController controller;

  @BeforeEach
  void renderTheRealTemplates() {
    DataSource database = dataSource();
    PendingApprovalsRepository.initialize(database);
    approvals = new PendingApprovalsRepository(database);
    approvals.asked(
        new PendingApproval(
            new CallId("call-1"),
            Watchman.TYPE,
            house,
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
    templates.setApplicationContext(new StaticWebApplicationContext());
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
        .andExpect(content().string(containsString(house.value().toString())))
        .andExpect(content().string(containsString("2h 0m")));
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
    mvc.perform(post("/approve/watchman/not-a-uuid/call-1")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("the buttons post to a URL naming the type, the agent AND the call")
  void the_decide_links_carry_the_whole_identity() throws Exception {
    // The id alone cannot find the row it came from: an id is unique only within its type.
    String id = house.value().toString();
    mvc.perform(get("/"))
        .andExpect(content().string(containsString("/approve/watchman/" + id + "/call-1")))
        .andExpect(content().string(containsString("/deny/watchman/" + id + "/call-1")));
  }

  @Nested
  @DisplayName("the page a decision redirects to")
  class ReadYourWrites {

    // The board's other writer is the desk, which records a decision when the engine narrates it.
    // The redirect lands inside that window, so the person who just clicked is the one guaranteed
    // to see the question they have already answered still sitting on the board -- unless the
    // controller writes too.
    // The database is shared by every test in the class, so the board is read for THIS house.
    private List<PendingApproval> waitingOnHouse() {
      return approvals.pending().stream().filter(row -> row.agentId().equals(house)).toList();
    }

    @Test
    @DisplayName("the row is gone from the board once the decision has been recorded")
    void answering_takes_the_question_off_the_board() {
      assertThat(waitingOnHouse()).hasSize(1);
      controller.recordLocally(
          Watchman.TYPE,
          house,
          new CallId("call-1"),
          ApprovalResult.denied("that seems dangerous"));
      assertThat(waitingOnHouse()).isEmpty();
    }

    @Test
    @DisplayName("the reason survives, so the two writers agree rather than race")
    void the_recorded_answer_is_the_one_that_was_sent() {
      controller.recordLocally(
          Watchman.TYPE,
          house,
          new CallId("call-1"),
          ApprovalResult.denied("that seems dangerous"));
      var row = approvals.byCallId(Watchman.TYPE, house, new CallId("call-1")).orElseThrow();
      assertThat(row.answer()).contains("denied");
      assertThat(row.note()).contains("that seems dangerous");
    }

    @Test
    @DisplayName("the desk writing the same decision afterwards changes nothing")
    void whichever_writer_arrives_second_is_a_no_op() {
      controller.recordLocally(
          Watchman.TYPE,
          house,
          new CallId("call-1"),
          ApprovalResult.denied("that seems dangerous"));
      // What the desk does when the engine narrates the denial a few milliseconds later.
      approvals.answered(
          Watchman.TYPE,
          house,
          new CallId("call-1"),
          "denied",
          "that seems dangerous",
          NOW.plusSeconds(1));
      var row = approvals.byCallId(Watchman.TYPE, house, new CallId("call-1")).orElseThrow();
      assertThat(row.answeredAt()).contains(NOW);
      assertThat(waitingOnHouse()).isEmpty();
    }
  }

  @Nested
  @DisplayName("telling two agents' questions apart")
  class Identity {

    @Test
    @DisplayName("two agents waiting on the same call id are two questions, not one")
    void a_call_id_is_only_unique_within_one_response() {
      AgentId other = new AgentId(UUID.randomUUID());
      approvals.asked(
          new PendingApproval(
              new CallId("call-1"),
              Watchman.TYPE,
              other,
              "prune_images",
              "docker image prune -af",
              NOW,
              NOW.plusSeconds(3600),
              "token-2",
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
      assertThat(approvals.pending())
          .as("one row would mean one house's question silently replaced the other's")
          .extracting(PendingApproval::agentId)
          .contains(house, other);
    }
  }
}
