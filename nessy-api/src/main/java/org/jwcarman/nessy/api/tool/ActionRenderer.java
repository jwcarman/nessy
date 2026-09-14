package org.jwcarman.nessy.api.tool;

/**
 * Says what a CALL would do, in a sentence a person can read.
 *
 * <p>Named for what it produces -- {@link ApprovalRequest#action()} -- and not for rendering,
 * because a {@link Tool} already has a description and it means something else: what the tool IS,
 * written for the model. This is what one call, with these arguments, would actually do.
 *
 * <p>Three readers, none of them the model: an approvals page, where a person cannot consent to
 * {@code {"customer_id":"cus_8823","op":"purge"}} but can consent to "permanently delete Acme
 * Corp's record"; a UI narrating tool use; and a log line.
 *
 * <p><b>It lives on the binding, never on the {@link Tool}.</b> If the sentence a person approves
 * against were authored by the tool being governed -- an MCP server, say -- it would not be a
 * control. The application states what a call means, per tool it offers.
 *
 * @param <I> the tool's bound input
 */
@FunctionalInterface
public interface ActionRenderer<I> {

  String render(I input);

  /**
   * The default: the input's own {@code toString()}.
   *
   * <p>Good enough for a record -- {@code RefundOrder[orderId=ord_88, amountCents=4200]} reads well
   * -- and null-safe. Two things it does not do: an input that is not a record renders as {@code
   * com.acme.PurgeRequest@1a2b3c}, and every component is printed, so a field holding a credential
   * or a customer's email reaches whoever is reading. Write a real one for anything a person will
   * be asked to approve.
   */
  static <I> ActionRenderer<I> byToString() {
    return String::valueOf;
  }
}
