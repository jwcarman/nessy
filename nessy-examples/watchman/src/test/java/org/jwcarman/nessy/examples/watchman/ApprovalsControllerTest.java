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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The page (spec §2.2): what is waiting, what it will do, how long it has waited, and the two
 * buttons.
 *
 * <p>The projection is a hand-written stub — a subclass of the starter's repository over an empty
 * {@code JdbcTemplate} — so the rows on the page are stated rather than parked. No mocking library
 * (design of record); {@code RoundTest} is where the same page reads rows a real round produced.
 */
@SpringBootTest(
    classes = {WatchmanApplication.class, ApprovalsControllerTest.StubbedRows.class},
    properties = {
      // ToolBeans is a user configuration, so its @ConditionalOnMissingBean CommandRunner is
      // registered before any @TestConfiguration is read. Overriding is how a test replaces a bean
      // a user configuration already named — and the fake below is named commandRunner on purpose.
      "spring.main.allow-bean-definition-overriding=true",
      "watchman.scheduling.enabled=false",
      "watchman.user=ops",
      "watchman.password=lan-only",
      "watchman.notes-dir=target/controller-test-notes"
    })
@ActiveProfiles("scripted")
@AutoConfigureMockMvc
class ApprovalsControllerTest {

  // A real computation id, because the desk hands it to Continuum, which parses it as a UUID —
  // "computation-42" fails at the door rather than on the page.
  private static final String PARKED_ID = "0198f4f0-9c1e-7c5a-9a4e-2b6f1d3c5e70";

  @Autowired private MockMvc mvc;

  @Autowired private StubbedRepository rows;

  /** Every test starts from the same one waiting approval, whatever the previous one did to it. */
  @BeforeEach
  void oneApprovalIsWaiting() {
    rows.showing(List.of(parked()));
  }

  @Nested
  class The_door {

    @Test
    void is_shut_without_credentials() throws Exception {
      mvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @Test
    void is_shut_to_the_wrong_password() throws Exception {
      mvc.perform(get("/").with(httpBasic("ops", "guess"))).andExpect(status().isUnauthorized());
    }

    @Test
    void opens_to_the_configured_account() throws Exception {
      mvc.perform(get("/").with(httpBasic("ops", "lan-only"))).andExpect(status().isOk());
    }

    @Test
    void guards_the_two_buttons_too() throws Exception {
      mvc.perform(post("/approve/{id}", PARKED_ID).with(csrf()))
          .andExpect(status().isUnauthorized());
      mvc.perform(post("/deny/{id}", PARKED_ID).with(csrf())).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  class The_pending_page {

    @Test
    void shows_the_action_the_agent_and_how_long_it_has_waited() throws Exception {
      mvc.perform(get("/").with(httpBasic("ops", "lan-only")))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString("systemctl restart -- nginx.service")))
          .andExpect(content().string(containsString("watchman")))
          .andExpect(content().string(containsString("2d")));
    }

    @Test
    void offers_the_frozen_request_as_evidence() throws Exception {
      mvc.perform(get("/").with(httpBasic("ops", "lan-only")))
          .andExpect(content().string(containsString("nginx.service")))
          .andExpect(content().string(containsString("<details")));
    }

    @Test
    void says_so_plainly_when_nothing_is_waiting() throws Exception {
      rows.showing(List.of());

      mvc.perform(get("/").with(httpBasic("ops", "lan-only")))
          .andExpect(content().string(containsString("Nothing is waiting")));
    }
  }

  @Nested
  class The_recent_page {

    @Test
    void lists_what_was_already_answered() throws Exception {
      mvc.perform(get("/recent").with(httpBasic("ops", "lan-only")))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString("docker image prune -af")))
          .andExpect(content().string(containsString("denied")));
    }
  }

  /**
   * {@code ApprovalDesk} is final, so there is no stand-in to record against here: what this
   * asserts is that the form binds, the principal comes from basic auth, and the browser is sent
   * home. That the desk actually answers a real park — with that principal on the reference — is
   * {@code RoundTest}, over a real harness and a real row.
   */
  @Nested
  class The_two_buttons {

    @Test
    void an_approval_binds_its_note_and_redirects_home() throws Exception {
      mvc.perform(
              post("/approve/{id}", PARKED_ID)
                  .param("note", "go ahead")
                  .with(httpBasic("ops", "lan-only"))
                  .with(csrf()))
          .andExpect(redirectedUrl("/"));
    }

    @Test
    void a_denial_carries_its_reason_and_redirects_home() throws Exception {
      mvc.perform(
              post("/deny/{id}", PARKED_ID)
                  .param("reason", "not during the freeze")
                  .with(httpBasic("ops", "lan-only"))
                  .with(csrf()))
          .andExpect(redirectedUrl("/"));
    }
  }

  private static PendingApproval parked() {
    return new PendingApproval(
        PARKED_ID,
        Optional.of("watchman"),
        Optional.of("watchman"),
        Optional.of("c2"),
        Optional.of("systemctl restart -- nginx.service"),
        Optional.of(
            "{\"action\":\"systemctl restart -- nginx.service\",\"unit\":\"nginx.service\"}"),
        Optional.of(Instant.now().minus(Duration.ofDays(2))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static PendingApproval answered() {
    return new PendingApproval(
        "0198f4f0-9c1e-7c5a-9a4e-2b6f1d3c5e71",
        Optional.of("watchman"),
        Optional.of("watchman"),
        Optional.of("c9"),
        Optional.of("docker image prune -af"),
        Optional.of("{}"),
        Optional.of(Instant.now().minus(Duration.ofDays(4))),
        Optional.of("denied"),
        Optional.of("desk:ops"),
        Optional.of("not during the freeze"),
        Optional.of(Instant.now().minus(Duration.ofDays(3))));
  }

  /**
   * The rows the page reads, stated outright.
   *
   * <p>Its pending list is INSTANCE state with a per-test reset, not a static field. A static
   * fixture mutated by one test and restored in a {@code finally} is a shared global: it leaks
   * between the nested classes, it makes the order they run in matter, and the restore is one
   * thrown assertion away from never happening. The bean is a singleton, so the test holds the same
   * instance the controller does and simply says what it should return.
   */
  static class StubbedRepository extends PendingApprovalsRepository {

    private List<PendingApproval> pending = List.of(parked());

    StubbedRepository() {
      super(new JdbcTemplate());
    }

    /** What the next {@code GET /} should find waiting. */
    void showing(List<PendingApproval> rows) {
      this.pending = List.copyOf(rows);
    }

    @Override
    public List<PendingApproval> pending() {
      return pending;
    }

    @Override
    public List<PendingApproval> recent(int limit) {
      assertThat(limit).isPositive();
      return List.of(answered());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StubbedRows {

    @Bean
    DataSource dataSource() {
      return WatchmanPostgres.dataSource();
    }

    @Bean
    @Primary
    StubbedRepository stubbedApprovals() {
      return new StubbedRepository();
    }

    @Bean
    CommandRunner commandRunner() {
      return new FakeRunner();
    }
  }
}
