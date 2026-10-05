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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * What an unconfigured harness does — the defaults live in the compact constructor, and this pins
 * every one of them so a reader of {@link NessyProperties} can trust the javadoc without
 * re-deriving it from Spring's binder.
 */
class NessyPropertiesTest {

  private static NessyProperties properties(String type, Integer maxTokens) {
    return new NessyProperties(type, null, null, null, null, maxTokens, null, null, null, null);
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    void a_null_type_becomes_agent() {
      assertThat(properties(null, null).type()).isEqualTo("agent");
    }

    @Test
    void a_blank_type_becomes_agent() {
      assertThat(properties("   ", null).type()).isEqualTo("agent");
    }

    @Test
    void a_given_type_is_kept() {
      assertThat(properties("watchman", null).type()).isEqualTo("watchman");
    }

    @Test
    void a_null_max_tokens_becomes_4096() {
      assertThat(properties(null, null).maxTokens()).isEqualTo(4096);
    }

    @Test
    void a_given_max_tokens_is_kept() {
      assertThat(properties(null, 512).maxTokens()).isEqualTo(512);
    }
  }

  @Nested
  @DisplayName("resolveSystemPrompt")
  class ResolveSystemPrompt {

    @Test
    void returns_the_inline_prompt_when_one_was_given() {
      NessyProperties properties =
          new NessyProperties(
              null, null, "You watch the house.", null, null, null, null, null, null, null);

      assertThat(properties.resolveSystemPrompt()).isEqualTo("You watch the house.");
    }

    /**
     * An agent with no standing instruction is a chat box. The empty string used to come back here
     * only because nothing downstream objected; {@code SystemPrompt} now refuses one, so saying so
     * here turns a confusing constructor failure into a sentence naming the property.
     */
    @Test
    void refuses_when_neither_source_was_given() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, null, null, null);

      assertThatThrownBy(properties::resolveSystemPrompt)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("nessy.system-prompt");
    }

    @Test
    void reads_the_file_when_only_the_file_was_given() {
      Resource file =
          new ByteArrayResource(
              "Watch the porch.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      NessyProperties properties =
          new NessyProperties(null, null, null, file, null, null, null, null, null, null);

      assertThat(properties.resolveSystemPrompt()).isEqualTo("Watch the porch.");
    }

    @Test
    @DisplayName("a resource that cannot be read fails as an UncheckedIOException naming it")
    void wraps_a_failure_to_read_the_resource() {
      Resource brokenFile = new BrokenResource();
      NessyProperties properties =
          new NessyProperties(null, null, null, brokenFile, null, null, null, null, null, null);

      assertThatThrownBy(properties::resolveSystemPrompt)
          .isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("could not read");
    }

    /** A {@link Resource} whose stream always fails, standing in for an unreadable file. */
    private static final class BrokenResource extends ByteArrayResource {
      private BrokenResource() {
        super(new byte[0]);
      }

      @Override
      public InputStream getInputStream() throws IOException {
        throw new IOException("disk is gone");
      }
    }
  }

  @org.junit.jupiter.api.Test
  void every_default_and_every_override() {
    NessyProperties defaults =
        new NessyProperties(" ", null, null, null, "m", null, null, null, null, null);
    assertThat(defaults.type()).isEqualTo("agent");
    assertThat(defaults.maxTokens()).isEqualTo(4096);
    assertThat(defaults.initializeSchema()).isTrue();

    NessyProperties given =
        new NessyProperties("ops", null, null, null, "m", 512, false, null, null, null);
    assertThat(given.type()).isEqualTo("ops");
    assertThat(given.maxTokens()).isEqualTo(512);
    assertThat(given.initializeSchema()).isFalse();
  }

  @Nested
  class TheDefaultEmbedder {

    /** A blank placeholder, as ${CHAT_EMBEDDER:} yields, is a property nobody set. */
    @Test
    void a_blank_embedder_and_model_are_unset() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, " ", "", null);

      assertThat(properties.embedder()).isNull();
      assertThat(properties.embeddingModel()).isNull();
    }

    @Test
    void a_given_pair_and_width_are_kept() {
      NessyProperties properties =
          new NessyProperties(
              null, null, null, null, null, null, null, "voyage", "voyage-3.5", 1024);

      assertThat(properties.embedder()).isEqualTo("voyage");
      assertThat(properties.embeddingModel()).isEqualTo("voyage-3.5");
      assertThat(properties.embeddingDimension()).isEqualTo(1024);
    }
  }
}
