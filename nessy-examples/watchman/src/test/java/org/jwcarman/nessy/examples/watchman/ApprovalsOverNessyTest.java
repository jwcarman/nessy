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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "watchman.round-interval=PT1H",
      "nessy.provider=scripted",
      "watchman.approval-term=PT4S"
    })
@Import(PostgresBacked.Connection.class)
@DisplayName("Review: the page over the real Nessy")
class ApprovalsOverNessyTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class CannedCommands {
    @Bean
    @Primary
    CommandRunner fakeRunner() {
      return new FakeRunner();
    }
  }

  @Autowired private AgentWork work;
  @LocalServerPort private int port;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private List<ApprovalRequest> ours() {
    return work.waitingApprovals(Watchman.TYPE).stream()
        .filter(r -> r.agentId().equals(Watchman.AGENT))
        .toList();
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return http.send(builder.build(), BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String form, String cookie) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form));
    if (cookie != null) {
      b.header("Cookie", cookie);
    }
    return send(b);
  }

  private HttpResponse<String> get(String cookie) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"));
    if (cookie != null) {
      b.header("Cookie", cookie);
    }
    return send(b);
  }

  @Test
  void a_waiting_approval_is_listed_garbage_is_a_400_and_an_expired_one_leaves_the_list_unaided()
      throws Exception {
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(ours()).hasSize(1));
    ApprovalRequest waiting = ours().getFirst();
    String page = get(null).body();
    assertThat(page)
        .contains("docker image prune -af")
        .contains(waiting.idempotencyKey().toString());

    String type = "agentType=" + Watchman.TYPE.value();
    String agent = "&agentId=" + Watchman.AGENT.value();
    String key = waiting.idempotencyKey().toString();
    assertThat(post("/approve/not-a-uuid", type + agent, null).statusCode()).isEqualTo(400);
    assertThat(post("/approve/" + key, type + "&agentId=junk", null).statusCode()).isEqualTo(400);
    assertThat(post("/approve/" + key, "agentId=" + Watchman.AGENT.value(), null).statusCode())
        .isEqualTo(400);
    assertThat(
            post("/approve/" + key, "agentType=" + "&agentId=" + Watchman.AGENT.value(), null)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post("/deny/" + key, "agentType=x".repeat(1) + "y".repeat(80) + agent, null)
                .statusCode())
        .isEqualTo(400);
    HttpResponse<String> unknown = post("/approve/" + UUID.randomUUID(), type + agent, null);
    assertThat(unknown.statusCode()).isEqualTo(302);
    HttpResponse<String> unknownType = post("/approve/" + key, "agentType=nobody" + agent, null);
    assertThat(unknownType.statusCode()).isEqualTo(302);
    assertThat(ours()).hasSize(1);

    // nobody answers: the term runs out and the list empties with no listener of the page's own
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(ours()).isEmpty());
    assertThat(get(null).body()).doesNotContain("docker image prune -af");

    // a late answer is told it was no longer waiting, and the page shows the notice once
    AtomicReference<String> cookieRef = new AtomicReference<>();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              HttpResponse<String> late = post("/approve/" + key, type + agent, null);
              assertThat(late.statusCode()).isEqualTo(302);
              cookieRef.set(late.headers().firstValue("Set-Cookie").orElse(""));
              assertThat(cookieRef.get()).isNotEmpty();
            });
    String cookie = cookieRef.get().split(";")[0];
    assertThat(get(cookie).body()).contains("That approval was no longer waiting.");
    assertThat(get(cookie).body()).doesNotContain("no longer waiting");
  }
}
