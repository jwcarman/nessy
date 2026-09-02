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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.spi.store.Schemas;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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

  @BeforeEach
  void renderTheRealTemplates() {
    var database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    Schemas.initialize(database);
    var approvals = new PendingApprovalsRepository(new JdbcTemplate(database));
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
    var controller =
        new ApprovalsController(approvals, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
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
  @DisplayName("the buttons post to a URL naming the type, the agent AND the call")
  void the_decide_links_carry_the_whole_identity() throws Exception {
    // The id alone cannot find the row it came from: an id is unique only within its type.
    mvc.perform(get("/"))
        .andExpect(content().string(containsString("/approve/watchman/house-12/call-1")))
        .andExpect(content().string(containsString("/deny/watchman/house-12/call-1")));
  }
}
