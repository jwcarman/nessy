# Prompts

A system prompt is a string, or something that decides the string per call.

```java
config.systemPrompt("You are a terse assistant.");
config.systemPrompt(source);      // a SystemPromptSource, asked on every call
```

```java
public interface SystemPromptSource {
  SystemPrompt forAgent(AgentId agentId);
}
```

It is asked on the dispatcher's thread, off the agent's row lock, so a
prompt that needs to look something up may. What it returned is written
down with every call in `nessy_inference_context`, so a prompt that varies
is never a mystery afterwards.

## Templates

`nessy-prompt` makes a prompt a template with holes, and separates the
template engine from where the values come from:

```java
SystemPromptSource prompt = TemplatedSystemPrompt.of(
        new SpringPromptTemplateFactory(),
        """
        You are a concise assistant in someone's terminal.
        Today is ${today}. Never assume the year.
        """,
        PromptVariableSource.supplied("today", () -> LocalDate.now(clock).toString()));
```

Three parts:

- **`PromptTemplateFactory`** compiles source into a `PromptTemplate`. Two
  engines ship: `nessy-prompt-spring`, Spring's placeholder syntax
  (`${name}`, `${name:default}`, `\${` to escape), and
  `nessy-prompt-mustache`, JMustache (`{{name}}`, sections that turn on a
  value being present and not empty).
- **`PromptVariableSource`** answers a variable by name for an agent:
  `of(map)`, `supplied(name, supplier)`, `firstOf(sources)`, or your own
  lambda. Several may be given; the first to answer wins.
- **`TemplatedSystemPrompt`** renders on every call and **refuses a hole
  nothing fills**. A model is never handed a placeholder.

Per agent, because the source is asked with the agent id: a prompt can name
the tenant, the user's preferences, or the plan the agent holds, and two
agents of one type see two prompts.

## In a Boot application

With an engine on the classpath, `nessy.system-prompt` and
`nessy.system-prompt-file` are templates. They are rendered from every
`PromptVariableSource` bean, in order, and then from the `Environment`, so
`${app.persona}` in the prompt is whatever the properties say.

```yaml
nessy:
  system-prompt-file: classpath:prompts/assistant.txt
  prompt:
    engine: spring        # or mustache
```

One thing to know: Boot resolves `${...}` inside an inline property when it
binds it, before any engine sees it. A prompt file reaches the engine
untouched, which is the form to use when a declared source should win over
the properties.

An application that declares its own `SystemPromptSource` bean keeps it,
and the free harness uses it.

## In the console

`ReplConfig.systemPrompt(...)` takes either form. `nessy-examples/chat-cli`
fills `${today}` per call, so the date is never stale across a session that
crosses midnight.

## Where next

- [The Harness](harness.md), where the prompt is set
- [Spring Boot](spring-boot.md), the properties
