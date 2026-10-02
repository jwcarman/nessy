# Prompts

A system prompt is a string, and it is fixed when a harness is built.

```java
config.systemPrompt("You are a terse assistant.");
config.instructions("When you use the notebook, keep headings short.");
```

`systemPrompt(String)` is the agent type's own statement of what it is for.
`instructions(String)` adds a section after it, for a module that has
something to tell every agent of the type, such as how to use the tools it
adds. The harness joins the prompt and then each section, in the order they
were added, separated by blank lines. A blank section is refused.

## Why it cannot change

The system prompt is the head of every request, and a provider caches a
request's leading text. A prompt that said something different on each call
would invalidate everything cached for every agent of the type. So it is
resolved once, and what must vary goes somewhere that does not sit at the
head:

- **What varies by agent** (a tenant, a user's preferences, a persona)
  belongs in a `StateSource`. It reaches the model at the head of the
  active turn, after the history, so the cache before it survives.
- **What varies by the moment** (the date, a plan the agent is editing)
  belongs in an `AmbientSource`. It reaches the model at the very end of
  the request.

See [Context](../concepts/context.md) for the six places a model's context is
built from.

## Templates

`nessy-prompt` makes a prompt a template with holes, and separates the
template engine from where the values come from:

```java
SystemPrompt prompt = TemplatedSystemPrompt.render(
        new SpringPromptTemplateFactory(),
        """
        You are a concise assistant for ${company}.
        Answer in ${language:English}.
        """,
        PromptVariables.of(Map.of("company", "Acme")));

config.systemPrompt(prompt.value());
```

Three parts:

- **`PromptTemplateFactory`** compiles source into a `PromptTemplate`. Two
  engines ship: `nessy-prompt-spring`, Spring's placeholder syntax
  (`${name}`, `${name:default}`, `\${` to escape), and
  `nessy-prompt-mustache`, JMustache (`{{name}}`, sections that turn on a
  value being present and not empty).
- **`PromptVariables`** answers a variable by name: `of(map)`,
  `supplied(name, supplier)`, `none()`, `firstOf(list)`, or your own lambda.
  Given several through `firstOf`, the first to answer wins.
- **`TemplatedSystemPrompt`** renders **once** and **refuses a hole nothing
  fills**. The harness fails to build rather than a model being handed a
  placeholder. `render(PromptTemplate, PromptVariables)` takes a compiled
  template; `render(PromptTemplateFactory, String, PromptVariables)` compiles
  the source first. Both return a `SystemPrompt`.

A supplier given to `PromptVariables.supplied` is asked once for each hole
that names it, at the moment the prompt is rendered. A value read late, such
as a date, is therefore the date the harness was built.

Outside Boot, `EnvironmentVariables.of(propertyResolver)` in
`nessy-prompt-spring` answers from any Spring `PropertyResolver`, as a
`PromptVariables`.

With the Mustache engine a section turns on a value being present and not
empty, which is how an optional line is written:

```java
SystemPrompt prompt = TemplatedSystemPrompt.render(
        new MustachePromptTemplateFactory(),
        "Be brief.{{#persona}} You are {{persona}}.{{/persona}}",
        PromptVariables.of(Map.of("persona", "a butler")));
```

## In a Boot application

With an engine on the classpath, `nessy.system-prompt` and
`nessy.system-prompt-file` are templates. The starter renders them once, at
startup, from every `PromptVariables` bean in order and then from the
`Environment`, so `${app.persona}` in the prompt is whatever the properties
say. The result is published as a `SystemPrompt` bean.

```yaml
nessy:
  system-prompt-file: classpath:prompts/assistant.txt
  prompt:
    engine: spring        # or mustache
```

A hole nothing fills fails the application's startup.

One thing to know: Boot resolves `${...}` inside an inline property when it
binds it, before any engine sees it. A prompt file reaches the engine
untouched, which is the form to use when a declared `PromptVariables` bean
should win over the properties.

The starter publishes the prompt; it does not apply it. Pass the text to the
harness you build:

```java
config.systemPrompt(prompt.value());
```

An application that declares its own `SystemPrompt` bean keeps it.

## In the console

`ReplConfig.systemPrompt(String)` takes the text. `nessy-examples/chat-cli`
keeps its prompt a plain constant and gives the date to the model as an
ambient source, so the date is never stale across a session that crosses
midnight.

## Where next

- [The Harness](harness.md), where the prompt is set
- [Context](../concepts/context.md), where what varies goes instead
- [Spring Boot](spring-boot.md), the properties
