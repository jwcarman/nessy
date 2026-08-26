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
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.spring.boot.PendingApproval;
import org.jwcarman.nessy.spring.boot.PendingApprovalsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A whole round, on a real Postgres, with a scripted model and a host that never existed — and then
 * a human, through the page, answering what the round proposed.
 *
 * <p>This is the soak in miniature. The scripted round reads the disk, proposes a {@code
 * restart_unit} the application will not perform on its own, and writes the note every round ends
 * with. Everything the spec promises about that arc is asserted here: the note is on disk, the
 * proposal is a row in the projection carrying the exact command line, and approving it through
 * {@code POST /approve/{id}} moves the row from pending to answered.
 */
@SpringBootTest(
    classes = {WatchmanApplication.class, RoundTest.Host.class},
    properties = {
      // ToolBeans is a user configuration, so its @ConditionalOnMissingBean CommandRunner is
      // registered before any @TestConfiguration is read. Overriding is how a test replaces a bean
      // a user configuration already named — and the fake below is named commandRunner on purpose.
      "spring.main.allow-bean-definition-overriding=true",
      "watchman.scheduling.enabled=false",
      "watchman.user=ops",
      "watchman.password=lan-only",
      // A pretend Debian box with systemd: enough for the round the script performs.
      "watchman.detected.df=true",
      "watchman.detected.systemctl=true"
    })
@ActiveProfiles("scripted")
@Tag("container")
@AutoConfigureMockMvc
class RoundTest {

  @TempDir static Path notesDir;

  @DynamicPropertySource
  static void notes(DynamicPropertyRegistry registry) {
    registry.add("watchman.notes-dir", () -> notesDir);
  }

  @Autowired private Rounds rounds;

  @Autowired private PendingApprovalsRepository approvals;

  @Autowired private MockMvc mvc;

  /** The pretend host, so the test can ask what was actually executed on it. */
  @Autowired private FakeRunner host;

  @Test
  void a_round_reads_the_disk_proposes_a_restart_and_writes_a_note() throws Exception {
    rounds.doRounds();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(approvals.pending()).hasSize(1));

    PendingApproval parked = approvals.pending().getFirst();
    assertThat(parked.agentId()).contains("watchman");
    assertThat(parked.action()).contains("systemctl restart -- nginx.service");
    assertThat(parked.requestJson()).isPresent();

    assertThat(notes()).isNotEmpty();
    assertThat(String.join("\n", notes())).contains("nginx.service");

    // The page shows it, with the very command line that will run.
    mvc.perform(get("/").with(httpBasic("ops", "lan-only")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("systemctl restart -- nginx.service")));

    // And a human answers it.
    mvc.perform(
            post("/approve/{id}", parked.computationId())
                .param("note", "go ahead")
                .with(httpBasic("ops", "lan-only"))
                .with(csrf()))
        .andExpect(redirectedUrl("/"));

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              assertThat(approvals.pending()).isEmpty();
              List<PendingApproval> recent = approvals.recent(50);
              assertThat(recent).isNotEmpty();
              assertThat(recent)
                  .singleElement()
                  .satisfies(
                      row -> {
                        assertThat(row.answer()).contains("approved");
                        assertThat(row.reference()).contains("desk:ops:go ahead");
                      });
            });

    // AND THE COMMAND ACTUALLY RAN (final review's named test gap). Everything above proves the row
    // moved from pending to answered — which is a claim about a table, not about the box. The
    // README promises "the tool runs, in a turn belonging to a process that did not exist when the
    // question was asked", and until now nothing joined the two halves: RoundTest watched the row,
    // RemediationGrantsTest ran the tool, and an approval that answered and did nothing would have
    // passed both. This is the join.
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              List<List<String>> ran = host.asked();
              assertThat(ran).isNotEmpty();
              assertThat(ran)
                  .as("the approved restart_unit must actually have been executed")
                  .contains(List.of("systemctl", "restart", "--", "nginx.service"));
            });
  }

  private static List<String> notes() throws IOException {
    if (!Files.isDirectory(notesDir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(notesDir)) {
      return entries.sorted().map(RoundTest::read).toList();
    }
  }

  private static String read(Path note) {
    try {
      return Files.readString(note);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + note, e);
    }
  }

  /** The database and the host: one real, one entirely invented. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Host {

    @Bean
    DataSource dataSource() {
      return WatchmanPostgres.dataSource();
    }

    @Bean
    FakeRunner commandRunner() {
      return new FakeRunner()
          .answering(
              "df",
              """
              Filesystem      Size  Used Avail Use% Mounted on
              /dev/sda1       234G  198G   24G  90% /
              """)
          .answering("systemctl", "");
    }
  }
}
