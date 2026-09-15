package org.jwcarman.nessy.examples.chatweb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The questions waiting for a person, in memory.
 *
 * <p>In memory because this is an example: a restart loses the cards, though not the questions --
 * the engine still holds each call open until its deadline, and a real desk would keep the token
 * somewhere durable. The reply token is held here and never rendered; it is the credential that
 * answers the question, not a fact about it.
 */
@Component
public class ApprovalDesk {

  private static final JsonMapper EVIDENCE = JsonMapper.builder().build();

  /** The arguments, pretty-printed for a person, or as they came if they will not parse. */
  private static String evidenceOf(String arguments) {
    try {
      return EVIDENCE
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(EVIDENCE.readTree(arguments));
    } catch (JacksonException notJson) {
      return arguments;
    }
  }

  public record Waiting(
      AgentId agentId,
      CallId callId,
      String tool,
      String arguments,
      String description,
      Instant askedAt,
      ReplyToken replyToken) {}

  private final ConcurrentMap<CallId, Waiting> waiting = new ConcurrentHashMap<>();

  public void expecting(ApprovalRequest request) {
    waiting.put(
        request.callId(),
        new Waiting(
            request.agentId(),
            request.callId(),
            request.toolName().value(),
            evidenceOf(request.arguments()),
            request.action(),
            request.askedAt(),
            request.replyToken()));
  }

  public List<Map<String, ?>> pending(AgentId agentId) {
    return waiting.values().stream()
        .filter(question -> question.agentId().equals(agentId))
        .sorted(java.util.Comparator.comparing(Waiting::askedAt))
        .map(ApprovalDesk::render)
        .toList();
  }

  public Map<String, ?> card(CallId callId) {
    Waiting question = waiting.get(callId);
    return question == null ? Map.of("id", callId.value()) : render(question);
  }

  public Optional<Waiting> take(CallId callId) {
    return Optional.ofNullable(waiting.remove(callId));
  }

  private static Map<String, ?> render(Waiting question) {
    return Map.of(
        "id", question.callId().value(),
        "tool", question.tool(),
        "args", question.arguments(),
        "what", question.description(),
        "askedAt", question.askedAt().toString());
  }
}
