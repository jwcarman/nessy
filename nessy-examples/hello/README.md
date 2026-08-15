# Nessy Example: Hello

The first example, and the smallest: the root README's five-minute snippet,
made into a runnable module instead of a read-only listing. `Hello` builds a
harness over `ScriptedModelProvider` (from the `testing` package — see the
comment where it's built), grants one `add` tool, and asks "what is 2+2?",
printing the settled answer and the conversation's terminal status.

## Run it

```bash
./mvnw -q -pl nessy-examples/hello -am compile exec:java
```

Expected output:

```
The answer is 4. (COMPLETE)
```

No key, no network, no Docker: `ScriptedModelProvider` plays back a fixed
script instead of calling a real model, so this example runs the same way on
every machine, including CI, with nothing to configure first.
