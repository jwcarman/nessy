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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Capability;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * What an unconfigured harness does — the defaults live in the compact constructor, and this pins
 * every one of them so a reader of {@link NessyProperties} can trust the javadoc without
 * re-deriving it from Spring's binder.
 */
class NessyPropertiesTest {

  private static NessyProperties properties(String type, String provider, Integer maxTokens) {
    return new NessyProperties(type, null, null, null, provider, maxTokens, null, null);
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    void a_null_type_becomes_agent() {
      assertThat(properties(null, null, null).type()).isEqualTo("agent");
    }

    @Test
    void a_blank_type_becomes_agent() {
      assertThat(properties("   ", null, null).type()).isEqualTo("agent");
    }

    @Test
    void a_given_type_is_kept() {
      assertThat(properties("watchman", null, null).type()).isEqualTo("watchman");
    }

    @Test
    void a_null_provider_becomes_unknown() {
      assertThat(properties(null, null, null).provider()).isEqualTo("unknown");
    }

    @Test
    void a_blank_provider_becomes_unknown() {
      assertThat(properties(null, "  ", null).provider()).isEqualTo("unknown");
    }

    @Test
    void a_given_provider_is_kept() {
      assertThat(properties(null, "anthropic", null).provider()).isEqualTo("anthropic");
    }

    @Test
    void a_null_max_tokens_becomes_4096() {
      assertThat(properties(null, null, null).maxTokens()).isEqualTo(4096);
    }

    @Test
    void a_given_max_tokens_is_kept() {
      assertThat(properties(null, null, 512).maxTokens()).isEqualTo(512);
    }

    @Test
    void null_capabilities_becomes_an_empty_set() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, null);

      assertThat(properties.capabilities()).isEmpty();
    }

    @Test
    void an_empty_capability_set_stays_empty() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, Set.of(), null);

      assertThat(properties.capabilities()).isEmpty();
    }

    @Test
    void given_capabilities_are_kept_as_an_immutable_set() {
      NessyProperties properties =
          new NessyProperties(
              null,
              null,
              null,
              null,
              null,
              null,
              Set.of(Capability.THINKING, Capability.PARALLEL_TOOL_CALLS),
              null);

      assertThat(properties.capabilities())
          .containsExactlyInAnyOrder(Capability.THINKING, Capability.PARALLEL_TOOL_CALLS);
    }

    @Test
    void null_reply_token_encryption_keys_becomes_an_empty_list() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, null);

      assertThat(properties.replyTokenEncryptionKeys()).isEmpty();
    }

    @Test
    void given_reply_token_encryption_keys_are_kept_in_order() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, List.of("key-a", "key-b"));

      assertThat(properties.replyTokenEncryptionKeys()).containsExactly("key-a", "key-b");
    }
  }

  @Nested
  @DisplayName("resolveSystemPrompt")
  class ResolveSystemPrompt {

    @Test
    void returns_the_inline_prompt_when_one_was_given() {
      NessyProperties properties =
          new NessyProperties(null, "You watch the house.", null, null, null, null, null, null);

      assertThat(properties.resolveSystemPrompt()).isEqualTo("You watch the house.");
    }

    @Test
    void returns_an_empty_string_when_neither_source_was_given() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, null);

      assertThat(properties.resolveSystemPrompt()).isEmpty();
    }

    @Test
    void reads_the_file_when_only_the_file_was_given() {
      Resource file =
          new ByteArrayResource(
              "Watch the porch.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      NessyProperties properties =
          new NessyProperties(null, null, file, null, null, null, null, null);

      assertThat(properties.resolveSystemPrompt()).isEqualTo("Watch the porch.");
    }

    @Test
    @DisplayName("a resource that cannot be read fails as an UncheckedIOException naming it")
    void wraps_a_failure_to_read_the_resource() {
      Resource brokenFile = new BrokenResource();
      NessyProperties properties =
          new NessyProperties(null, null, brokenFile, null, null, null, null, null);

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
}
