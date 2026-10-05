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
package org.jwcarman.nessy.examples.chatweb;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The page's side of the conversation: the same requests app.js makes, and the status each gets.
 */
final class ChatClient {

  record Card(
      String id,
      Long turn,
      String tool,
      String args,
      String what,
      String askedAt,
      String deadline) {}

  record PageState(List<Map<String, Object>> transcript, List<Card> approvals, boolean working) {

    List<String> texts() {
      return transcript.stream().map(line -> String.valueOf(line.get("text"))).toList();
    }
  }

  private final RestClient http;

  ChatClient(int port) {
    // Timeouts, so a stalled request fails with a stack trace rather than hanging the build.
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    factory.setReadTimeout(Duration.ofSeconds(60));
    this.http =
        RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(factory).build();
  }

  int say(String agentId, String text) {
    return http.post()
        .uri("/api/agents/{id}/messages", agentId)
        .body(new ChatController.MessageRequest(text))
        .exchange((request, response) -> response.getStatusCode().value());
  }

  PageState state(String agentId) {
    return http.get().uri("/api/agents/{id}", agentId).retrieve().body(PageState.class);
  }

  int decide(String agentId, String key, String decision, String note) {
    return http.post()
        .uri("/api/agents/{id}/approvals/{key}", agentId, key)
        .body(new ChatController.Decision(decision, note))
        .exchange((request, response) -> response.getStatusCode().value());
  }

  int end(String agentId) {
    return http.delete()
        .uri("/api/agents/{id}", agentId)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  int get(String path) {
    return http.get().uri(path).exchange((request, response) -> response.getStatusCode().value());
  }
}
