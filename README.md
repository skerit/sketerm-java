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
| `api` | The Playwright-SHAPED synchronous face over the browser tool group: `Sketerm`, `Browser`, `Page`, `Snapshot`/`Ref`, `NetworkPolicy`/`PolicyStatus`, `Evidence`, and one typed exception per error code |

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
`ErrorCode`, `ProfileKind`, `ResourceType`, `UrlScheme`, `PolicySource`, `DenialReason`). An unknown
token fails closed rather than being folded into a neighbour.

Failures are typed off `structuredContent.error.code` by one switch in `SketermApiException.from`:
`InvalidArgsException`, `NotFoundException`, `UnavailableException`, `TimeoutException`,
`RefusedException`, `ConflictException`, `IoFailedException`, `UnknownToolException`,
`FailedException`, plus `isRetryable()` on all of them. Two more are the api layer's own:

- `StaleRefException` - a `Ref` from a document the page has left, refused before the call goes out,
  and also what the server's own "stale reader id" / "unknown id" refusal decodes to.
- `ProtocolMismatchException` - the answer had no `structuredContent`, or no view handle in it. That
  is a Sketerm predating the result-shape migration, and it is named as such rather than guessed at.

### Lifecycle and browsing profiles

`page.close()` calls `web_close` and decodes a `CloseResult` (`closed`, `remaining`, `current`,
`profile`, `profileReleased`). What it destroys depends on the backend and the result says which:
headless it ends a helper view, but with a GUI attached it closes the USER'S pane, exactly as
`close_pane` does (`closedAPane()`). Every later call on that `Page` is refused locally with a
`PageClosedException`, because the handle is free to be handed to the next view somebody opens.

Headless views can hold an isolated browsing identity:

```java
Browser browser = sketerm.browser();

if (browser.supportsProfiles()) {

    // Its own cookie jar and cache; logins survive the close and a server restart
    Page work = browser.openPage("https://example.com/", OpenOptions.inProfile("work"));
    work.close();

    // A throwaway identity instead, destroyed with the view
    Page once = browser.openPage("https://example.com/", OpenOptions.ephemeralIdentity());
    once.close();

    for (BrowserProfile profile : browser.profiles().profiles()) {
        System.out.println(profile.name() + " " + profile.views() + " views");
    }

    browser.resetProfile("work");
}
```

The rules the wire contract insists on, mirrored here:

- profiles are HEADLESS ONLY; with a GUI attached the identity containers belong to the user and
  both the open and the profile tools refuse;
- the refusal is FAIL CLOSED - nothing is opened and no page is loaded, never a silent fall back to
  the shared jar - so `UnavailableException` from `openPage` means there is no view;
- `profile` and `ephemeral` are opposite answers to the same question and `OpenOptions` refuses both
  at build time, as the server does at call time;
- a name is `[a-z0-9_-]{1,64}` minus `default` and `none`, checked client-side by `ProfileNames` so a
  doomed call never goes out;
- SESSION cookies never persist: they die with the browser process, profile or not;
- a `context` id is opaque and is retired by a reset, so a profile is addressed by NAME everywhere;
- `supportsProfiles()` reads the `capabilities` report's `web_profiles` flag, the preflight before
  offering the feature at all.

### Enforced network policy

A headless view can be opened under a policy the browser engine enforces itself, deciding every
request before it leaves the process:

```java
NetworkPolicy policy = NetworkPolicy.builder()
        .allowHosts("example.com", "cdn.example.com")
        .allowSubresourceHosts("static.example.com")
        .blockTypes(ResourceType.MEDIA, ResourceType.FONT)
        .allowSchemes(UrlScheme.HTTPS)
        .maxRequests(60)
        .maxBytes(8_000_000L)
        .maxNavigations(4)
        .deadline(Duration.ofSeconds(30))
        .build();

Page page = browser.openPage("https://example.com/", OpenOptions.withNetworkPolicy(policy));

PolicyStatus status = page.policy();
System.out.println(status.requests() + " requests, " + status.bytes() + " bytes");
System.out.println(status.denied(DenialReason.SUB_HOST) + " subresource hosts refused");

// A live policy can only ever be narrowed; a field that would widen it is named, not applied
PolicyUpdate update = page.tightenPolicy(NetworkPolicy.builder()
        .blockTypes(ResourceType.IMAGE)
        .maxRequests(20)
        .build());

System.out.println(update.tightened() + " narrowed, " + update.ignored() + " refused as loosenings");

// And a profile can carry a session default for every later open in it
browser.setProfilePolicy("work", policy);
```

The rules the wire contract insists on, mirrored here:

- policies are HEADLESS ONLY, and are installed at OPEN and never added to a live view, whose
  earlier requests would predate them;
- the refusal is FAIL CLOSED: a helper without the net-policy capability answers `unavailable` and
  NOTHING is opened, never a view running unpoliced, and past the helper's policied-view cap it is
  a `conflict` instead;
- budgets LATCH, permanently and per view. Past the first one that is hit, `web_navigate`,
  `web_act`, `web_eval` and a load wait all throw `RefusedException` (non-retryable), while read
  tools keep answering and carry the fact - so `page.isPolicyExhausted()` and
  `page.policyExhaustedReason()` are up to date after any call, and `page.policy()` has the
  accounting;
- `max_bytes` is accounted at response completion, so the response that CROSSES the cap completes
  and the NEXT request is refused; a redirect hop counts as a navigation;
- nothing is durable: `PolicyStatus.durable()` is always false, because a policy and a profile
  default live for this MCP server's lifetime by design;
- a host entry is a bare name or IP literal, so `*`, a scheme, a port or a path is refused by
  `PolicyHosts` before the call goes out - write no policy rather than an allow-all one.

The vocabularies mirror the schema enums one for one: `ResourceType` (11 names), `UrlScheme` (7),
`PolicySource` (3) and `DenialReason` (12), which is also the home of the 5-name `exhausted_reason`
- that shorter list is derived as the members whose `latches()` is true, never restated.

### Evidence capture

`Evidence` freezes one page at one moment out of the reads that already exist, so the pieces are
known to describe the same moment rather than three round trips apart:

```java
Evidence evidence = Evidence.capture(page);

System.out.println(evidence.finalUrl() + " at " + evidence.capturedAt());
System.out.println("screenshot sha256: " + evidence.screenshotSha256());

evidence.writeTo(Path.of("build/evidence/run-1"));
// -> evidence.json, screenshot.png, snapshot.txt, article.md
```

`capture(page, EnumSet.of(Part.SCREENSHOT, Part.NETWORK))` takes only the parts named. Every part is
a READ tool, so a capture still works on a view whose budgets have latched - which is exactly the
view whose evidence someone wants. A policy is attested only when one is actually installed, rather
than recorded as a row of zeroes that would read like enforcement.

## Building

```
./gradlew build
```

Protoblast must be published to mavenLocal first (`zenit-dev build` in the javaweb workspace).
`SketermSessionIT` drives the real binary and skips itself when `sketerm` is not on PATH.
`PageApiIT` drives the whole api layer against a real headless view: it copies `sketerm` and
`sketerm-webengine` out of the sibling checkout once, so a rebuild mid-run cannot swap the binary
underneath it, and skips itself when either is missing. Its second journey proves close and the
profile lifecycle over a loopback HTTP fixture (a `data:` document carries no cookies at all in
Chromium), under its own `XDG_STATE_HOME` and instance name so the profile store is per run; it
skips those steps when the copied binary predates the profile tools.
