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
| `api` | The Playwright-SHAPED synchronous face over the browser tool group: `Sketerm`, `Browser`, `Page`, `Snapshot`/`Ref`, and one typed exception per error code |

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

## The api layer

`Sketerm.launch` composes process, transport, connection and session into one `AutoCloseable`, and
its `browser()` opens views. It is shaped like Playwright, not compatible with it: everything is
synchronous, and a `Ref` is a semantic id from the server's own accessibility tree rather than a
CSS selector.

```java
SketermOptions options = SketermOptions.builder()
        .binaryPath("sketerm")
        .toolGroups("browser,core")
        .defaultTimeout(Duration.ofSeconds(30))
        .build();

try (Sketerm sketerm = Sketerm.launch(options)) {

    Page page = sketerm.browser().openPage("https://example.com/");

    // web_open already sent the first tree; asking again would only get a delta
    Snapshot snapshot = page.lastSnapshot();
    Ref moreInfo = snapshot.find("More information").orElseThrow();

    page.click(moreInfo);
    page.waitFor(WaitFor.LOAD);

    Article article = page.read();
    System.out.println(article.markdown());

    System.out.println(page.evaluate("document.querySelectorAll('a').length"));

    page.screenshot().writeTo(Path.of("page.png"));

    for (NetworkLog.NetworkRequest request : page.networkRequests()) {
        System.out.println(request.method() + " " + request.status() + " " + request.url());
    }
}
```

Every call names its view handle explicitly (the `pane` argument), so several pages can be driven
from one session without the server's "current view" ever deciding for you.

### Vocabularies and refusals

The tool schemas own the vocabularies; the enums mirror them one for one and carry their wire token
(`Action`, `NavigateAction`, `WaitFor`, `ScrollTo`, `SnapshotMode`, `QueryKind`, `NetworkAction`,
`ErrorCode`). An unknown token fails closed rather than being folded into a neighbour.

Failures are typed off `structuredContent.error.code` by one switch in `SketermApiException.from`:
`InvalidArgsException`, `NotFoundException`, `UnavailableException`, `TimeoutException`,
`RefusedException`, `ConflictException`, `IoFailedException`, `UnknownToolException`,
`FailedException`, plus `isRetryable()` on all of them. Two more are the api layer's own:

- `StaleRefException` - a `Ref` from a document the page has left, refused before the call goes out,
  and also what the server's own "stale reader id" / "unknown id" refusal decodes to.
- `ProtocolMismatchException` - the answer had no `structuredContent`, or no view handle in it. That
  is a Sketerm predating the result-shape migration, and it is named as such rather than guessed at.

`Page` has no `close()` yet: the tool table this client was written against publishes no `web_close`.

## Building

```
./gradlew build
```

Protoblast must be published to mavenLocal first (`zenit-dev build` in the javaweb workspace).
`SketermSessionIT` drives the real binary and skips itself when `sketerm` is not on PATH.
`PageApiIT` drives the whole api layer against a real headless view: it copies `sketerm` and
`sketerm-webengine` out of the sibling checkout once, so a rebuild mid-run cannot swap the binary
underneath it, and skips itself when either is missing.
