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
package org.jwcarman.nessy.api;

/**
 * Runs a turn on the calling thread and hands back what it came to.
 *
 * <p>The door for work somebody is waiting on, and the opposite bargain to a {@link QueuedHarness}:
 * that one answers only whether the agent took the input, this one answers and may decline to
 * start. Neither is a special case of the other. Everything a queued harness does because nobody is
 * waiting -- queueing, coalescing, deferral, resumption, one-turn-at-a-time -- is absent here, not
 * because it is forbidden but because nothing in this world can produce it: the caller is the only
 * thing that can start a turn, and it is standing right there holding the answer.
 *
 * <p><b>The answer's shape is a property of the harness, not of the call.</b> A {@link
 * DirectHarnessFactory} settles it when the harness is made, the same way {@link
 * QueuedHarnessFactory} settles what an agent takes: two harnesses of the same agent type may
 * answer in different shapes, but one harness always answers in one.
 *
 * <p><b>Whether it remembers is the store's business.</b> The same harness backed by memory forgets
 * and backed by a durable store resumes; neither changes a line of this interface.
 *
 * <p><b>One turn at a time per agent, and the second caller is told so.</b> A queued harness gets
 * that from its queue; this gets it from a lock, and being refused is an {@link AskOutcome.Busy}
 * rather than a wait -- the caller is standing right there and would rather know than block. What
 * kind of lock decides whether that holds across machines or only inside this one.
 *
 * @param <I> what a caller hands in
 * @param <O> what a caller gets back
 */
public interface DirectHarness<I, O> {

  /**
   * One turn, start to finish, before this returns.
   *
   * <p>Answers rather than throws for anything it understands: a model declining, a turn running
   * out of budget, a provider being unreachable and the agent already being busy are outcomes to
   * branch on, not faults.
   *
   * <p><b>Unconstrained rather than untyped is a distinction that lives on the factory now, not
   * here.</b> A harness made with no output shape asks nothing of the answer's form, so what comes
   * back off the wire is prose, and {@code O} is {@link String}. Asking for a shape -- even one as
   * bland as a record holding a string -- makes {@code O} that shape instead, and the schema goes
   * to the provider, which constrains the answer on its own wire -- natively on every vendor that
   * has it -- and what comes back is parsed into {@code O} before the caller sees it. A caller
   * never learns which mechanism the vendor used, which is the point.
   *
   * <p>Support for a shape is not universal and is sometimes per model: a vendor that will not
   * constrain an answer, or a model that answers around the shape, ends the turn {@link
   * AskOutcome.Failed} rather than handing back something that does not fit.
   *
   * <p><b>Never inside a caller's transaction.</b> A turn makes at least one model call, a network
   * call that can take seconds, and nothing should hold a transaction open across one; on a JDBC
   * store it cannot work either, because the model call runs on another connection and cannot see
   * what the turn wrote on the caller's. A transaction Spring manages is refused before anything is
   * written. To call this from inside one, suspend it first ({@code PROPAGATION_NOT_SUPPORTED}).
   *
   * @throws IllegalStateException if a transaction is open on the calling thread
   */
  AskOutcome<O> ask(AgentId agent, I input);

  /**
   * This agent is terminated, if it was in a position to be told.
   *
   * <p>It refuses everything afterwards, loudly, and there is no way back -- so this is a decision
   * about the agent rather than about this object. Letting a harness be collected leaves its agent
   * resumable, which is the right default: abandoning a conversation and terminating one are
   * different acts, and only one of them has a method.
   *
   * <p><b>An agent is only ever terminated from idle, and this says whether that happened.</b> A
   * turn in flight is owed its outcome -- abandoning it would leave effects with nobody to deliver
   * them to -- so termination is not delivered mid-turn. This door has nowhere to record that
   * somebody asked: unlike the queued door, which writes the termination down and honours it when
   * the agent next falls idle, here a refused termination is simply refused, and the answer is
   * {@link TerminationOutcome.Busy}. Returning an outcome rather than nothing is the difference
   * between a caller knowing that and a caller assuming.
   *
   * <p>A caller driving its own turns rarely sees {@code Busy}: {@link #ask} returns when the turn
   * is over, so an agent is idle by the time that caller asks to terminate it. {@code Busy} is what
   * another thread gets for terminating an agent somebody else is still asking. {@link
   * TerminationOutcome.Terminated} says this call terminated the agent, and {@link
   * TerminationOutcome.AlreadyTerminated} says it had been terminated before.
   *
   * @return which of the three arms happened; see {@link TerminationOutcome}
   */
  TerminationOutcome terminate(AgentId agent);
}
