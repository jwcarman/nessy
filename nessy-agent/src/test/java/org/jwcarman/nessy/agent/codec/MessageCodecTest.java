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
package org.jwcarman.nessy.agent.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class MessageCodecTest {

  private static ToolCall lookupCall() {
    return new ToolCall("call-1", "lookup", JsonNodeFactory.instance.objectNode().put("q", "x"));
  }

  @Nested
  class ContentBlockRoundTrips {

    @Test
    void aTextBlockRoundTrips() {
      var block = new TextBlock("hello there");
      var message = new Message(Role.USER, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void anImageBlockRoundTrips() {
      var block = new ImageBlock("image/png", "aGVsbG8=");
      var message = new Message(Role.USER, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void aSignedThinkingBlockRoundTrips() {
      var block = new ThinkingBlock("hmm, let me think", "sig-abc");
      var message = new Message(Role.ASSISTANT, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void anUnsignedThinkingBlockRoundTrips() {
      var block = new ThinkingBlock("hmm, let me think", "");
      var message = new Message(Role.ASSISTANT, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void aRedactedThinkingBlockRoundTrips() {
      var block = new RedactedThinkingBlock("opaque-ciphertext");
      var message = new Message(Role.ASSISTANT, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void aSignedToolUseBlockRoundTrips() {
      var block = new ToolUseBlock(lookupCall(), "sig-xyz");
      var message = new Message(Role.ASSISTANT, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void anUnsignedToolUseBlockRoundTrips() {
      var block = new ToolUseBlock(lookupCall());
      var message = new Message(Role.ASSISTANT, List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void aToolResultBlockRoundTrips() {
      var block = new ToolResultBlock("call-1", "42", false);
      var message = Message.toolResults(List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void aFailedToolResultBlockRoundTrips() {
      var block = new ToolResultBlock("call-1", "boom", true);
      var message = Message.toolResults(List.of(block));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }
  }

  @Nested
  class Roles {

    @Test
    void aUserMessageRoundTrips() {
      var message = Message.user("hi");
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }

    @Test
    void anAssistantMessageRoundTrips() {
      var message = Message.assistant(List.of(new TextBlock("hi back")));
      assertThat(MessageCodec.message(MessageCodec.toJson(message))).isEqualTo(message);
    }
  }

  @Nested
  class ContextRoundTrips {

    @Test
    void aFullContextWithAToolExchangeRoundTrips() {
      var call = lookupCall();
      var context =
          Context.of(
              List.of(
                  Message.user("what's the weather"),
                  Message.assistant(List.of(new ToolUseBlock(call))),
                  Message.toolResults(List.of(new ToolResultBlock(call.id(), "sunny", false))),
                  Message.assistant(List.of(new TextBlock("it's sunny")))));
      assertThat(MessageCodec.context(MessageCodec.toJson(context))).isEqualTo(context);
    }

    @Test
    void anEmptyContextRoundTrips() {
      var context = Context.empty();
      assertThat(MessageCodec.context(MessageCodec.toJson(context))).isEqualTo(context);
    }
  }

  @Nested
  class ToleranceAndRejection {

    @Test
    void anUnknownFieldOnAMessageIsIgnored() {
      var json =
          """
          {"role":"user","content":[{"type":"text","text":"hi"}],"fromTheFuture":true}
          """;
      assertThat(MessageCodec.message(json)).isEqualTo(Message.user("hi"));
    }

    @Test
    void anUnknownFieldOnAContentBlockIsIgnored() {
      var json =
          """
          {"role":"user","content":[{"type":"text","text":"hi","futureField":42}]}
          """;
      assertThat(MessageCodec.message(json)).isEqualTo(Message.user("hi"));
    }

    @Test
    void anUnknownContentBlockDiscriminatorIsRejected() {
      var json =
          """
          {"role":"user","content":[{"type":"video","url":"x"}]}
          """;
      assertThatThrownBy(() -> MessageCodec.message(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("video");
    }

    @Test
    void anUnknownRoleIsRejected() {
      var json =
          """
          {"role":"referee","content":[{"type":"text","text":"hi"}]}
          """;
      assertThatThrownBy(() -> MessageCodec.message(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("referee");
    }

    @Test
    void malformedMessageJsonIsRejected() {
      assertThatThrownBy(() -> MessageCodec.message("not json at all"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedMessageJsonNeverLeaksAJacksonException() {
      assertThatThrownBy(() -> MessageCodec.message("not json at all"))
          .isInstanceOf(IllegalArgumentException.class)
          .isNotInstanceOf(JsonProcessingException.class);
    }

    @Test
    void malformedContextJsonIsRejected() {
      assertThatThrownBy(() -> MessageCodec.context("{"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageMissingItsRoleIsRejected() {
      assertThatThrownBy(() -> MessageCodec.message("{\"content\":[]}"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aContentBlockMissingItsTypeIsRejected() {
      var json =
          """
          {"role":"user","content":[{"text":"hi"}]}
          """;
      assertThatThrownBy(() -> MessageCodec.message(json))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageWhoseContentIsNotAnArrayIsRejected() {
      var json =
          """
          {"role":"user","content":42}
          """;
      assertThatThrownBy(() -> MessageCodec.message(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("content");
    }

    @Test
    void aContextWhoseMessagesIsNotAnArrayIsRejected() {
      var json =
          """
          {"messages":"oops"}
          """;
      assertThatThrownBy(() -> MessageCodec.context(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("messages");
    }

    @Test
    void aThinkingBlockWithNoSignatureKeyDecodesAsUnsigned() {
      var json =
          """
          {"role":"assistant","content":[{"type":"thinking","text":"x"}]}
          """;
      assertThat(MessageCodec.message(json))
          .isEqualTo(new Message(Role.ASSISTANT, List.of(new ThinkingBlock("x", ""))));
    }
  }
}
