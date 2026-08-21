# sketerm-java

A typed Java SDK for driving the [Sketerm](https://github.com/skerit/sketerm) MCP server.

It speaks MCP over newline-delimited JSON to a `sketerm mcp` child process. Protoblast is the
only runtime dependency: `Dry` supplies the JSON codec and `JobRunner` the reader and stderr
threads. There is no Jackson, no Reactor and no SLF4J.

## Layering

Each layer knows only the one below it, so a future HTTP transport is a swap and not a rewrite.

| Package | Responsibility |
| --- | --- |
| `json` | `Json`, a facade over one shared `Dry`: `parseObject`, an NDJSON-safe `write`, and typed getters that fail with the offending key named |
| `process` | `SketermProcess`: argv/cwd/env, UTF-8 line streams, a 200-line stderr ring for diagnostics, `close()` = close stdin, destroy, wait, force-kill, plus a shutdown hook so a crashed JVM never orphans a browser |
| `rpc` | `SketermTransport` (the seam), `StdioTransport` (newline framing), `JsonRpcConnection` (id correlation, per-call timeouts, EOF drains every pending call with the child's exit code and stderr tail) |
| `mcp` | `McpSession` (initialize, tools/list, tools/call, ping), `ToolDescriptor`, `ToolResult`, `Content`, `ToolException` |
| `api` | Not built yet: the Playwright-shaped face (Browser, Page, Locator) over the browser tool group. It waits for the server-side result-shape migration to land |

## The result decode rule

Sketerm has two structured-result shapes in the wild, so `ToolResult.decode` applies one rule:

1. use `structuredContent` when the server sent it;
2. otherwise, if the first content block is text whose trimmed form starts with `{` and parses as
   a JSON object, adopt that (what the installed 0.1.3 build emits);
3. otherwise the result is prose only and `structured()` is null.

Tool failures arrive as a successful JSON-RPC result carrying `isError`, a channel distinct from
a JSON-RPC error object. `ToolResult.orThrow(name)` turns one into a `ToolException`, reading
`structuredContent.error`'s `code`, `message` and `retryable` when the newer server shape is present.

## Usage

```java
SketermProcess process = SketermProcess.start(
        List.of("sketerm", "mcp", "--tools", "browser,core"), null, null);

try (McpSession session = McpSession.initialize(
        new JsonRpcConnection(new StdioTransport(process)))) {

    for (ToolDescriptor tool : session.listTools()) {
        System.out.println(tool.name());
    }

    ToolResult result = session.callToolOrThrow("web_open", Map.of("url", "about:blank"));
    System.out.println(Json.str(result.structured(), "url"));
}
```

## Building

```
./gradlew build
```

Protoblast must be published to mavenLocal first (`zenit-dev build` in the javaweb workspace).
`SketermSessionIT` drives the real binary and skips itself when `sketerm` is not on PATH.
