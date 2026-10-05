# Nessy Example: Watchman

A queued-door agent that watches a house: it wakes on a schedule, may ask a
person to approve something through a parked approval, and keeps its story in
Postgres across restarts.

## Run it

Start its database with the compose file beside this README, then the
example from the repository root:

```bash
docker compose -f nessy-examples/watchman/docker-compose.yml up -d
./mvnw -q -pl :nessy-example-watchman -am install -DskipTests
./mvnw -q -pl :nessy-example-watchman spring-boot:run
```

The `install` builds the example and everything it depends on; `spring-boot:run`
then runs the example alone. Run the `install` again after changing any module.

By default it answers with the keyless `lmstudio` preset against
[LM Studio](https://lmstudio.ai) on `localhost:1234`
(`WATCHMAN_MODEL_URL` points it elsewhere, `WATCHMAN_MODEL_ID` names the
model).

### Soaking against OpenAI

Point the soak at OpenAI itself instead of a local model by naming the
`openai` preset, with `OPENAI_API_KEY` exported:

```bash
WATCHMAN_PROVIDER=openai WATCHMAN_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-watchman spring-boot:run
```

`WATCHMAN_PROVIDER` selects which registered provider answers (`nessy.provider`);
`OPENAI_API_KEY` lights the `openai` preset the same way it does for any other
Nessy application; `WATCHMAN_MODEL_ID` is the model name.
