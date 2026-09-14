package org.jwcarman.nessy.model.lmstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Seq;

/**
 * The wire, which is the one thing about tool calling that cannot be reasoned out.
 *
 * <p>Field names here are the provider's, not ours, and getting one wrong fails silently: a
 * misspelled {@code tool_calls} is simply an assistant message with no calls, and a misspelled
 * {@code tool_call_id} is a result matched to nothing. Both produce a plausible-looking request
 * that the server rejects or, worse, quietly answers as though nothing was asked.
 */
class LmStudioToolCallingTest {

    private static final String URL = "http://localhost:1234/v1/chat/completions";

    private MockRestServiceServer server;
    private InferenceProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:1234");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new LmStudioInferenceProvider(builder.build());
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    private static Block.ToolCall call() {
        return new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}");
    }

    private static Turn openTurn() {
        return new Turn(new TurnId(1), new Observation(
                new Seq(1), HistoryEntry.ObservationReceived.text("what lake?")), List.of(), null, 0);
    }

    private InferenceResult infer(List<Turn> turns, List<ToolOffer> tools) {
        return provider.infer(new InferenceRequest(
                new SystemPrompt("You are a test assistant."),
                InferenceContext.of(turns), tools, InferenceOptions.of("a-model")));
    }

    private void respondWith(String messageJson) {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":" + messageJson + "}]}",
                        MediaType.APPLICATION_JSON));
    }

    // ---- offering ------------------------------------------------------------------------

    /**
     * The schema goes in as a document, not as a string containing one. It is already JSON text,
     * so anything that escaped it would offer the model a parameter object that is a quoted blob.
     */
    @Test
    void aToolIsOfferedWithItsSchemaInlineAsJson() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("lookup"))
                .andExpect(jsonPath("$.tools[0].function.description").value("looks things up"))
                .andExpect(jsonPath("$.tools[0].function.parameters.type").value("object"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        infer(List.of(openTurn()), List.of(new ToolOffer(
                new ToolName("lookup"), "looks things up",
                new InputSchema("{\"type\":\"object\",\"properties\":{}}"))));
    }

    /** Several servers reject an empty array, so nothing on offer means the key is absent. */
    @Test
    void noToolsMeansNoToolsKeyAtAll() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        infer(List.of(openTurn()), List.of());
    }

    /**
     * Pre-existing and fixed in passing: this was serialised as {@code maxTokens}, which this API
     * does not read, so every ceiling an application configured was silently ignored.
     */
    @Test
    void theTokenCeilingIsSentUnderTheNameTheApiReads() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.max_tokens").value(64))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        provider.infer(new InferenceRequest(
                new SystemPrompt("You are a test assistant."),
                InferenceContext.of(List.of(openTurn())), List.of(),
                new InferenceOptions("a-model", 64)));
    }

    // ---- reading the answer --------------------------------------------------------------

    @Test
    void aMessageCarryingCallsIsARequestRatherThanAnAnswer() {
        respondWith("""
                {"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"lookup","arguments":"{\\"q\\":\\"loch ness\\"}"}}]}""");

        InferenceResult result = infer(List.of(openTurn()), List.of());

        assertThat(result).isInstanceOf(InferenceResult.Actions.class);
        assertThat(((InferenceResult.Actions) result).blocks()).containsExactly(call());
    }

    /** Models narrate what they are about to do, and dropping that re-sends an odd transcript. */
    @Test
    void theProseBesideTheCallsIsKept() {
        respondWith("""
                {"role":"assistant","content":"Let me look that up.","tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"lookup","arguments":"{\\"q\\":\\"loch ness\\"}"}}]}""");

        assertThat(((InferenceResult.Actions) infer(List.of(openTurn()), List.of())).blocks())
                .containsExactly(new Block.Commentary("Let me look that up."), call());
    }

    @Test
    void aMessageWithNoCallsIsStillAnAnswer() {
        respondWith("{\"role\":\"assistant\",\"content\":\"It is Loch Ness.\"}");

        assertThat(infer(List.of(openTurn()), List.of()))
                .isInstanceOf(InferenceResult.Answer.class);
    }

    // ---- re-sending the round ------------------------------------------------------------

    /**
     * This wire rejects an assistant message carrying calls that is not immediately followed by a
     * result for each of them, so the ordering asserted here is a requirement rather than a style.
     */
    @Test
    void anExchangeIsResentAsAnAssistantMessageAndAToolMessagePerResult() {
        Turn turn = new Turn(new TurnId(1),
                new Observation(new Seq(1), HistoryEntry.ObservationReceived.text("what lake?")),
                List.of(new Exchange(new Seq(2), List.of(new Block.Commentary("Looking."), call()),
                        List.of(new ToolOutcome.Succeeded(
                                new CallId("call_1"), List.of(new Block.Text("Loch Ness")))))),
                new TurnResult.Answered(HistoryEntry.InferenceAnswered.text("It is Loch Ness.")),
                0);

        server.expect(requestTo(URL))
                // 0 is the system prompt, 1 the observation.
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].content").value("Looking."))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("lookup"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.arguments")
                        .value("{\"q\":\"loch ness\"}"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[3].content").value("Loch Ness"))
                .andExpect(jsonPath("$.messages[4].role").value("assistant"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        infer(List.of(turn, openTurn()), List.of());
    }

    /**
     * A user message must not carry {@code tool_call_id}, even as null: several servers that
     * accept its absence reject an explicit null.
     */
    @Test
    void aMessageThatIsNotAResultCarriesNoResultFields() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[1].tool_call_id").doesNotExist())
                .andExpect(jsonPath("$.messages[1].tool_calls").doesNotExist())
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        infer(List.of(openTurn()), List.of());
    }

    /**
     * All three outcomes flatten to one shape, because this wire has no field for a denial and no
     * error flag on a result. The distinction is not lost -- it stays in the story -- only
     * unsendable here.
     */
    @Test
    void aFailureAndADenialBothBecomeResultText() {
        Turn turn = new Turn(new TurnId(1),
                new Observation(new Seq(1), HistoryEntry.ObservationReceived.text("do two things")),
                List.of(new Exchange(new Seq(2),
                        List.of(call(), new Block.ToolCall("call_2", "lookup", "{}")),
                        List.of(new ToolOutcome.Failed(new CallId("call_1"), "no network"),
                                new ToolOutcome.Denied(new CallId("call_2"), "not allowed")))),
                null, 0);

        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].content").value("Error: no network"))
                .andExpect(jsonPath("$.messages[4].tool_call_id").value("call_2"))
                .andExpect(jsonPath("$.messages[4].content")
                        .value("This call was not run because it was not permitted: not allowed"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        infer(List.of(turn), List.of());
    }

    // ---- background ------------------------------------------------------------------------

    /**
     * This wire has one place for anything nobody said, so background is folded into the system
     * message -- and labelled, because a model reading two blobs run together cannot tell the
     * standing instruction from today's note.
     */
    @Test
    void backgroundIsFoldedIntoTheSystemMessageUnderItsOwnLabel() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(
                        "You are a test assistant."
                                + "\n\n<notebook>\nthe deploy is frozen\n</notebook>"
                                + "\n\n<clock>\nit is Tuesday\n</clock>"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        provider.infer(new InferenceRequest(
                new SystemPrompt("You are a test assistant."),
                new InferenceContext(List.of(openTurn()), List.of(
                        Ambient.text("notebook", "the deploy is frozen"),
                        Ambient.text("clock", "it is Tuesday"))),
                List.of(), InferenceOptions.of("a-model")));
    }

    /**
     * No background means the prompt is exactly what it was before any of this existed. An empty
     * labelled section would tell the model its notebook is empty, which is a claim.
     */
    @Test
    void noBackgroundLeavesTheSystemPromptUntouched() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[0].content").value("You are a test assistant."))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        provider.infer(new InferenceRequest(
                new SystemPrompt("You are a test assistant."),
                new InferenceContext(List.of(openTurn()), List.of()),
                List.of(), InferenceOptions.of("a-model")));
    }

    /** Background is not a turn, and must never be mistaken for one thing anybody said. */
    @Test
    void backgroundIsNotAMessageOfItsOwn() {
        server.expect(requestTo(URL))
                // 0 system, 1 the observation, and nothing else.
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}",
                        MediaType.APPLICATION_JSON));

        provider.infer(new InferenceRequest(
                new SystemPrompt("You are a test assistant."),
                new InferenceContext(List.of(openTurn()),
                        List.of(Ambient.text("notebook", "the deploy is frozen"))),
                List.of(), InferenceOptions.of("a-model")));
    }

    /**
     * Whitespace beside a call is formatting, not the model talking.
     *
     * <p>This provider sends {@code "\n\n"} as the content of a message whose point is its calls.
     * A commentary block made of it is a dim empty line in every console and a wasted block in
     * every later request -- while an answer's leading newlines are kept, because there the
     * whitespace is part of what was said.
     */
    @Test
    void whitespaceBesideACallIsNotCommentary() {
        respondWith("""
                {"role":"assistant","content":"\\n\\n","tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"lookup","arguments":"{\\"q\\":\\"loch ness\\"}"}}]}""");

        assertThat(((InferenceResult.Actions) infer(List.of(openTurn()), List.of())).blocks())
                .containsExactly(call());
    }

    /** But an answer's own whitespace is content, and is kept. */
    @Test
    void whitespaceInAnAnswerIsKept() {
        respondWith("{\"role\":\"assistant\",\"content\":\"\\n\\nIt is deep.\"}");

        assertThat(((InferenceResult.Answer) infer(List.of(openTurn()), List.of())).blocks())
                .containsExactly(new Block.Text("\n\nIt is deep."));
    }
}
