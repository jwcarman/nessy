package org.jwcarman.nessy.engine.harness;

import java.time.Clock;
import java.util.function.Consumer;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.effect.ApprovalHandler;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.InferenceHandler;
import org.jwcarman.nessy.engine.effect.ToolCallHandler;
import org.jwcarman.nessy.engine.inference.DefaultInferenceService;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.inference.RecentTurnsContextAssembler;
import org.jwcarman.nessy.engine.store.AgentHistoryStore;
import org.jwcarman.nessy.engine.store.AgentStateRepository;
import org.jwcarman.nessy.engine.store.AgentStateStore;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.store.JdbcEffectStore;
import org.jwcarman.nessy.engine.store.JdbcHistoryStore;
import org.jwcarman.nessy.engine.store.ToolCallHistories;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.tool.DefaultReplies;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes harnesses, holding the infrastructure every agent type is built from.
 *
 * <p>The split is deliberate: a caller supplies what is theirs -- the agent type's name, its
 * observation type, how to render one, which model it calls, how often it polls -- and this
 * supplies the stores, the transaction template, the scheduler and the codec factory. Nobody
 * assembles a harness by hand, so nobody can assemble one wrongly.
 *
 * <p><b>Shared infrastructure, not shared machinery.</b> Two harnesses use the same tables and the
 * same scheduler the way they use the same JVM. Nothing else crosses between them: each gets its
 * own codec, its own model, its own dispatcher, its own schedule, and its own rows. There is no
 * registry here holding the harnesses this made, and nothing that iterates all of them.
 */
@Component
public class HarnessFactory implements org.jwcarman.nessy.api.HarnessFactory {

  private final CodecFactory codecs;
  private final AgentStateRepository states;
  private final JdbcHistoryStore appender;

  private final TurnHistories histories;
  private final ToolCallHistories toolCalls;
  private final InputSchemaGenerator schemas;
  private final ObjectMapper mapper;
  private final Narrator narrator;
  private final ReplyTokens replyTokens;
  private final DefaultReplies replies;
  private final JdbcEffectStore effectRows;
  private final TransactionTemplate transactions;
  private final TaskScheduler scheduler;
  private final Clock clock;
  private final HarnessConfig.Defaults defaults;

  public HarnessFactory(
      CodecFactory codecs,
      AgentStateRepository states,
      JdbcHistoryStore appender,
      TurnHistories histories,
      ToolCallHistories toolCalls,
      InputSchemaGenerator schemas,
      ObjectMapper mapper,
      Narrator narrator,
      ReplyTokens replyTokens,
      JdbcEffectStore effectRows,
      PlatformTransactionManager transactionManager,
      TaskScheduler scheduler,
      Clock clock,
      InferenceProvider provider,
      InferenceOptions options,
      RetryPolicy retryPolicy) {
    this.codecs = codecs;
    this.states = states;
    this.appender = appender;
    this.histories = histories;
    this.toolCalls = toolCalls;
    this.schemas = schemas;
    this.mapper = mapper;
    this.narrator = narrator;
    this.replyTokens = replyTokens;
    this.replies = new DefaultReplies(replyTokens);
    this.effectRows = effectRows;
    this.transactions = new TransactionTemplate(transactionManager);
    this.scheduler = scheduler;
    this.clock = clock;
    // What an agent type gets unless it says otherwise. Configured once, by the application,
    // where a provider and a model are an application-wide fact rather than an agent's.
    this.defaults = new HarnessConfig.Defaults(provider, options, retryPolicy);
  }

  /**
   * The general form.
   *
   * <p>{@link TypeRef#parameterized} is why this works at all: a {@code TypeRef} cannot be captured
   * for a type variable, so {@code new TypeRef<AgentState<O>>() {}} would throw here. Composing one
   * from the caller's own {@code TypeRef<O>} is what lets the document's codec know a type this
   * class never sees -- and it is why nothing above ever has to name {@code AgentState}.
   */
  @Override
  public <O> Harness<O> create(
      TypeRef<O> observationType, Consumer<org.jwcarman.nessy.api.HarnessConfig<O>> customizer) {
    HarnessConfig<O> config = new HarnessConfig<>(observationType, defaults, mapper, schemas);
    customizer.accept(config);

    TypeRef<AgentState<O>> stateType =
        TypeRef.parameterized(AgentState.class, config.observationType());

    AgentType agentType = config.requiredAgentType();
    Tools tools = config.tools();
    // Built here because it needs the store, which a caller has no handle on.
    HarnessConfig.Inference inference = config.inference();
    InferenceContextAssembler assembler =
        new RecentTurnsContextAssembler(histories, inference.recentTurns(), config.ambient());
    // One registry, held by both halves: the store asks it what an effect is worth while
    // writing the row, the dispatcher asks it who performs one after reading it back. Two
    // lookups keyed by the same thing could disagree; one cannot.
    EffectHandlers handlers =
        new EffectHandlers(
            new InferenceHandler(
                agentType,
                new DefaultInferenceService(
                    assembler,
                    inference.provider(),
                    config.requiredSystemPrompt(),
                    tools.offers(),
                    narrator),
                inference.options(),
                inference.timeout(),
                inference.retryPolicy()),
            new ApprovalHandler(
                agentType,
                tools,
                toolCalls.forAgentType(agentType),
                replyTokens,
                narrator,
                config.approvalTimeout(),
                config.toolRetryPolicy(),
                clock),
            new ToolCallHandler(
                agentType,
                tools,
                toolCalls.forAgentType(agentType),
                replyTokens,
                narrator,
                config.toolTimeout(),
                config.toolRetryPolicy(),
                clock));
    EffectStore effects = new EffectStore(agentType, handlers, effectRows);
    DefaultHarness<O> harness =
        new DefaultHarness<>(
            agentType,
            config.coalescer(),
            new AgentStateStore<>(agentType, codecs.create(stateType), states),
            new AgentHistoryStore<>(agentType, config.renderer(), appender),
            effects,
            transactions,
            narrator,
            clock);

    // The harness is the callback, so it has to exist before its dispatcher does -- and the
    // dispatcher must not be polling before the harness can be called back into. Three
    // statements rather than one constructor, because the cycle is real and hiding it would
    // mean leaking a half-built harness to something already able to reach it.
    // Registered before the dispatcher polls, so an answer can never arrive for an agent type
    // this process is serving but has not admitted to. The reverse -- a token for a type
    // nobody configured -- is answered as nothing awaiting, which is what it is.
    replies.register(agentType, effects, harness);

    EffectDispatcher dispatcher =
        new EffectDispatcher(
            agentType,
            effects,
            handlers,
            harness,
            clock,
            scheduler,
            config.effects().pollInterval(),
            config.effects().maxInFlight());
    // Lifecycle only: the harness holds it so that closing one closes the other. How often it
    // polls never reaches the harness.
    harness.dispatchWith(dispatcher);
    dispatcher.start();

    return harness;
  }

  /**
   * Where a late answer comes back in.
   *
   * <p>One for the whole factory rather than one per harness: a reply token is opaque, so whoever
   * holds one cannot say which kind of agent it belongs to and could never pick a harness. This
   * reads the agent type out of the token and routes on it.
   */
  public Replies replies() {
    return replies;
  }
}
