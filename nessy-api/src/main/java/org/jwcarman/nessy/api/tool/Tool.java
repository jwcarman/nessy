package org.jwcarman.nessy.api.tool;

import org.jwcarman.nessy.api.Awaited;

public interface Tool<I> {

  Class<I> inputType();

  ToolName name();

  String description();

  Awaited<ToolResult> call(ToolCallRequest<I> request);

  /**
   * The shape of this tool's arguments, as the model is offered them.
   *
   * <p>Generated from {@link #inputType()} by default, because a tool that has already declared a
   * Java type has already said what its arguments are, and writing the same thing again in JSON is
   * how the two drift apart. Annotate the input's components with {@code @JsonPropertyDescription}
   * to tell the model what each one means -- that is the only part a generator cannot infer, and
   * the part a model actually reads.
   *
   * <p>Override when the schema is not derivable from a Java type: an MCP tool's shape is known
   * only to the server it came from, so it returns what that server gave it and ignores the
   * generator. {@link #inputType()} stays honest either way -- for such a tool it is the tree its
   * arguments bind to, which is still what decodes a call.
   *
   * <p>The generator is handed in rather than reached for, so a tool cannot generate against
   * different rules than the harness advertises. It is called once, when the tool is bound.
   */
  default InputSchema inputSchema(InputSchemaGenerator generator) {
    return generator.generate(inputType());
  }
}
