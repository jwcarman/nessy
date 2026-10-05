# Nessy Example: Watchman

A queued-door agent that watches a house: it wakes on a schedule, may ask a
person to approve something through a parked approval, and keeps its story in
Postgres across restarts.

## The approvals page

The page at `/` lists what the watchman is waiting on: for each approval
request, the tool, the action a person would be consenting to, the facts
recorded on it, when it was asked, its deadline and how long it has waited. The
watchman keeps no table of its own. Its approver defers, and the page reads the
waiting approval requests from Nessy's `AgentWork` bean on every load. Approve
and deny post the agent type, the agent id and the call's idempotency key to
`Replies`; if the approval was no longer waiting (answered from another tab, or
failed when its deadline passed), the page says so. The page's endpoint is what
decides who may answer: the example puts no login in front of it, so an
application that copies it must guard it.

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
