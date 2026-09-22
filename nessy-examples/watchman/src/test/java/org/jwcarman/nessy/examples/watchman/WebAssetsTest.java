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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * The page's script actually resolves.
 *
 * <p>The version used to be written into the template: {@code
 * /webjars/htmx.org/2.0.4/dist/htmx.min.js}. Dependabot bumps the jar and cannot edit the template,
 * so the URL would go on naming a version that is no longer packaged -- a 404, htmx never loading,
 * and a board that renders but does nothing. Nothing caught it, because nothing here opens a
 * browser and a 404 on a script is not a failed request as far as the server is concerned.
 *
 * <p>The template now names no version and {@code webjars-locator-lite} supplies one, so this
 * checks the arrangement rather than the number: whatever version is on the classpath, the page's
 * own URL has to answer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"watchman.scripted=true", "watchman.round-interval=PT24H"})
@Import(PostgresBacked.Connection.class)
@DisplayName("The board's assets")
class WebAssetsTest {

  @LocalServerPort private int port;

  private RestClient http() {
    return RestClient.create("http://localhost:" + port);
  }

  @Test
  @DisplayName("include the htmx the page asks for, at the version-less path it asks for it by")
  void htmx_resolves_at_the_path_the_page_asks_for() {
    String script =
        http().get().uri("/webjars/htmx.org/dist/htmx.min.js").retrieve().body(String.class);

    assertThat(script).contains("htmx");
  }

  /**
   * And whatever URL the page ends up naming, that URL answers.
   *
   * <p>Not an assertion about the text. The template names no version and Spring puts the concrete
   * one back when it renders {@code @{...}}, so the page says {@code .../2.0.4/dist/...} even
   * though nothing wrote that. Pinning the string would pin the mechanism; what matters is that the
   * browser's next request succeeds, which is the thing that silently stopped being true.
   */
  @Test
  @DisplayName("are all fetchable at whatever URL the page names them by")
  void every_script_the_page_names_can_be_fetched() {
    String page = http().get().uri("/").retrieve().body(String.class);

    Matcher scripts = Pattern.compile("<script[^>]+src=\"([^\"]+)\"").matcher(page);
    List<String> found = new ArrayList<>();
    while (scripts.find()) {
      String src = scripts.group(1);
      found.add(src);
      assertThat(http().get().uri(src).retrieve().body(String.class))
          .as("the page asks for %s", src)
          .isNotBlank();
    }
    assertThat(found).isNotEmpty();
  }
}
