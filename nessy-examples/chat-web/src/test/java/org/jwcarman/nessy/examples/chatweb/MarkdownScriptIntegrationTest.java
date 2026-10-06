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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The page draws answers with markdown-it's browser build and colours their code with highlight.js,
 * both served from WebJars on the classpath. The paths are read from the page itself, so each
 * version is written only in the pom and the page, and this fails when the two disagree.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nessy.provider=scriptedModels")
@Import(PostgresBacked.class)
@DisplayName("The Markdown scripts")
class MarkdownScriptIntegrationTest {

  private static final Pattern WEBJAR = Pattern.compile("(?:src|href)=\"(/webjars/[^\"]+)\"");

  private static final Pattern SCRIPT =
      Pattern.compile("<script src=\"(/webjars/markdown-it/[^\"]+/dist/markdown-it\\.min\\.js)\"");

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.alwaysSaying("Noted.");
    }
  }

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void the_script_the_page_loads_is_served_as_javascript()
      throws IOException, InterruptedException {
    HttpResponse<String> page = get("/");
    assertThat(page.statusCode()).isEqualTo(200);
    Matcher script = SCRIPT.matcher(page.body());
    assertThat(script.find()).as("the page loads markdown-it from /webjars").isTrue();

    HttpResponse<String> served = get(script.group(1));

    assertThat(served.statusCode()).isEqualTo(200);
    assertThat(served.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).contains("javascript"));
    assertThat(served.body()).contains("markdownit");
  }

  @Test
  void everything_the_page_loads_from_a_webjar_is_served()
      throws IOException, InterruptedException {
    HttpResponse<String> page = get("/");
    List<String> paths =
        WEBJAR.matcher(page.body()).results().map(found -> found.group(1)).toList();
    assertThat(paths)
        .anyMatch(path -> path.endsWith("/highlight.min.js"))
        .anyMatch(path -> path.endsWith("/styles/github.min.css"))
        .anyMatch(path -> path.endsWith("/styles/github-dark.min.css"));

    List<Integer> statuses = new ArrayList<>();
    for (String path : paths) {
      statuses.add(get(path).statusCode());
    }

    assertThat(statuses).hasSameSizeAs(paths).containsOnly(200);
  }
}
