package org.jwcarman.nessy.narration.odyssey;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.odyssey.core.OdysseyStream;
import org.jwcarman.odyssey.core.TtlPolicy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

/**
 * One Odyssey stream per agent instance, named {@code nessy/<type>/<id>}.
 *
 * <p>This is the door for everyone: the narrator publishes through it, a page subscribes or resumes
 * through it, and an application with something of its own to say about an agent -- a question for
 * a person, say -- publishes that on the same stream, so a page has one connection to hold.
 *
 * <p>The element type is JSON rather than {@link org.jwcarman.nessy.api.AgentEvent}: an event is a
 * sealed hierarchy with no type information of its own, a stream is read back by whoever resumes
 * it, and the wire should not be the api's records anyway. See {@link OdysseyNarrator} for what the
 * narrator writes.
 */
public class AgentStreams {

  private final Odyssey odyssey;
  private final TtlPolicy ttl;

  public AgentStreams(Odyssey odyssey, TtlPolicy ttl) {
    this.odyssey = Objects.requireNonNull(odyssey, "odyssey must not be null");
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
  }

  /** The stream's name: the type and the id, because an id names an agent only within its type. */
  public static String nameOf(AgentType agentType, AgentId agentId) {
    return "nessy/" + agentType.value() + "/" + agentId.value();
  }

  public OdysseyStream<JsonNode> stream(AgentType agentType, AgentId agentId) {
    return odyssey.stream(nameOf(agentType, agentId), JsonNode.class, ttl);
  }

  /** Says something about an agent on its stream; returns the event's id. */
  public String publish(AgentType agentType, AgentId agentId, String eventName, JsonNode payload) {
    return stream(agentType, agentId).publish(eventName, payload);
  }

  /** From now on. */
  public SseEmitter subscribe(AgentType agentType, AgentId agentId) {
    return stream(agentType, agentId).subscribe();
  }

  /**
   * From after the event a browser last saw, which it hands back as {@code Last-Event-ID} when it
   * reconnects; from now on when it has seen nothing.
   */
  public SseEmitter resume(AgentType agentType, AgentId agentId, String lastEventId) {
    OdysseyStream<JsonNode> stream = stream(agentType, agentId);
    return lastEventId == null || lastEventId.isBlank()
        ? stream.subscribe()
        : stream.resume(lastEventId);
  }
}
