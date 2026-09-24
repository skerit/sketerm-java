package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;
import be.elevenways.sketerm.mcp.Content;
import be.elevenways.sketerm.mcp.ToolResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One web view, addressed by its handle: every call sends 'pane' explicitly rather than leaning on
 * the server's notion of the current view, so several pages can be driven from one session.
 *
 * <p>{@link #close()} ends the view; every later call on this object is refused locally, because
 * the handle is free to be handed to a view somebody else opens.</p>
 */
public final class Page {

    private final ToolCalls calls;
    private final int handle;

    private String backend;
    private String url;
    private String title;
    private boolean loading;
    private int document;
    private int revision;
    private String profile;
    private ProfileKind profileKind;
    private int context;
    private boolean policyActive;
    private PolicySource policySource;
    private int policySerial;
    private boolean policyExhausted;
    private DenialReason policyExhaustedReason;
    private boolean captureActive;
    private boolean openingSettled;
    private String openingSnapshotError;
    private boolean closed;
    private CloseResult closeResult;
    private Snapshot lastSnapshot;

    Page(ToolCalls calls, int handle, Map<String, Object> structured, Snapshot opening) {
        this.calls = calls;
        this.handle = handle;
        this.absorb(structured);
        this.openingSettled = Json.optBool(structured, "settled", opening != null);
        this.openingSnapshotError = Json.optStr(structured, "snapshot_error");
        this.lastSnapshot = opening;
    }

    /**
     * The most recent tree this page received, which for a freshly opened page is the one web_open
     * already sent.
     *
     * <p>Worth reaching for: a snapshot in {@link SnapshotMode#AUTO} right after opening answers
     * with an empty delta, because the server already sent that tree.</p>
     *
     * @return the last snapshot, or null when this page was attached or its opening snapshot timed
     *         out; {@link #openingSnapshotError()} preserves the latter reason
     */
    public Snapshot lastSnapshot() {
        return this.lastSnapshot;
    }

    /**
     * @return whether the first navigation settled inside {@link OpenOptions#timeout()}
     */
    public boolean wasOpeningSettled() {
        return this.openingSettled;
    }

    /**
     * @return why the first semantic snapshot failed, or null when it succeeded or none was named
     */
    public String openingSnapshotError() {
        return this.openingSnapshotError;
    }

    /**
     * @return the view handle, a pane id with a GUI and a view id headless
     */
    public int handle() {
        return this.handle;
    }

    /**
     * @return "gui" or "headless", as the last answer reported it
     */
    public String backend() {
        return this.backend;
    }

    /**
     * @return the url as of the last answer; call {@link #refresh()} to re-read it
     */
    public String url() {
        return this.url;
    }

    /**
     * @return the title as of the last answer; call {@link #refresh()} to re-read it
     */
    public String title() {
        return this.title;
    }

    public boolean isLoading() {
        return this.loading;
    }

    /**
     * @return the last known document counter, 0 when a navigation made it unknown
     */
    public int document() {
        return this.document;
    }

    /**
     * @return the last known tree revision, 0 when none has been seen
     */
    public int revision() {
        return this.revision;
    }

    /**
     * @return the named profile this view was opened in, null for the default jar, an ephemeral
     *         identity, or a GUI backend (which reports no identity at all)
     */
    public String profile() {
        return this.profile;
    }

    /**
     * @return which identity this view holds, null when the answer named none (a GUI backend)
     */
    public ProfileKind profileKind() {
        return this.profileKind;
    }

    /**
     * @return the engine identity-context id, 0 for the shared default jar; opaque, and retired by
     *         a profile reset, so never store it as the name of anything
     */
    public int context() {
        return this.context;
    }

    /**
     * @return whether an enforced network policy is installed on this view, as of the last answer
     *         that reported it (web_open and {@link #policy()} do; nothing else does)
     */
    public boolean isPolicyActive() {
        return this.policyActive;
    }

    /**
     * @return where the installed policy came from, null when no answer has said
     */
    public PolicySource policySource() {
        return this.policySource;
    }

    /**
     * @return the policy generation, which a {@link #tightenPolicy} moves; 0 when unpoliced
     */
    public int policySerial() {
        return this.policySerial;
    }

    /**
     * Whether a policy budget has LATCHED for this view.
     *
     * <p>Every answer this page decodes carries the fact when it is true, so this is up to date
     * without a round trip of its own. Latching is permanent per view: reads keep working, and
     * every tool that would cause traffic is refused with a {@link RefusedException} from here on.
     * {@link #policy()} has the accounting.</p>
     */
    public boolean isPolicyExhausted() {
        return this.policyExhausted;
    }

    /**
     * @return which budget latched, null while none has
     */
    public DenialReason policyExhaustedReason() {
        return this.policyExhaustedReason;
    }

    /**
     * This view's enforced policy and its live accounting: requests, bytes, navigations, time left,
     * refusals by reason, and whether a budget latched.
     *
     * <p>This is the machine-readable half of every policy refusal sentence, and it keeps answering
     * after the budgets are spent.</p>
     *
     * @throws UnavailableException with a GUI attached: policies are a headless-only feature
     */
    public PolicyStatus policy() {

        Map<String, Object> structured = this.calls.structured("web_policy", this.args());
        this.absorb(structured);

        return PolicyStatus.decode(structured);
    }

    /**
     * Narrow this view's live policy.
     *
     * <p>A live policy can only TIGHTEN - host lists shrink, budgets lower, blocked types grow,
     * allow_private only turns off - so what already ran under the old policy stays within the new
     * one's story. A single field that would loosen is named in {@link PolicyUpdate#ignored()}
     * rather than applied.</p>
     *
     * @throws RefusedException when EVERY requested change would loosen the live policy
     * @throws ConflictException when this view runs no policy at all: one is installed at open and
     *         never added to a live view, whose earlier requests would predate it
     */
    public PolicyUpdate tightenPolicy(NetworkPolicy policy) {

        Map<String, Object> arguments = this.args();
        arguments.put("policy", policy.toWire());

        Map<String, Object> structured = this.calls.structured("web_policy_set", arguments);
        this.absorb(structured);

        return PolicyUpdate.decode(structured);
    }

    /**
     * @return whether this view records response bodies, as of the last answer that said
     *         (web_open, web_tabs and the capture calls do); false after {@link #disableCapture()}
     */
    public boolean isCaptureActive() {
        return this.captureActive;
    }

    /**
     * @return whether {@link #close()} already ended this view
     */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Close this view.
     *
     * <p>Headless it destroys the helper view, and with it an ephemeral identity whose last view
     * this was; a named profile KEEPS its storage ({@link Browser#resetProfile} erases it). With a
     * GUI attached this closes the user's PANE and is destructive - the answer's backend says
     * which of the two happened.</p>
     *
     * <p>Closing twice is a no-op that answers with the first close's result.</p>
     */
    public CloseResult close() {

        if (this.closed) {
            return this.closeResult;
        }

        Map<String, Object> structured = this.calls.structured("web_close", this.args());

        this.closed = true;
        this.closeResult = CloseResult.decode(structured);

        return this.closeResult;
    }

    /**
     * Re-read this view's facts from web_tabs.
     *
     * @throws NotFoundException when the view is gone
     */
    public PageInfo refresh() {

        if (this.closed) {
            throw new PageClosedException(this.handle, "refresh()");
        }

        for (PageInfo info : Browser.listPages(this.calls)) {
            if (info.handle() == this.handle) {
                this.absorb(info.toFacts());
                return info;
            }
        }

        throw new NotFoundException("View " + this.handle + " is no longer open", "web_tabs", false, null);
    }

    /**
     * Navigate to a url and wait, bounded, for the nav state to settle.
     */
    public NavigationResult navigate(String url) {
        return this.navigate(url, null);
    }

    /**
     * @param timeout the settle budget, the server's default when null
     */
    public NavigationResult navigate(String url, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("url", url);
        ToolCalls.putTimeout(arguments, timeout);

        return this.navigated(arguments);
    }

    public NavigationResult back() {
        return this.navigate(NavigateAction.BACK, null);
    }

    public NavigationResult forward() {
        return this.navigate(NavigateAction.FORWARD, null);
    }

    public NavigationResult reload() {
        return this.navigate(NavigateAction.RELOAD, null);
    }

    public NavigationResult stop() {
        return this.navigate(NavigateAction.STOP, null);
    }

    /**
     * @param timeout the settle budget, the server's default when null
     */
    public NavigationResult navigate(NavigateAction action, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("action", action.wire());
        ToolCalls.putTimeout(arguments, timeout);

        return this.navigated(arguments);
    }

    /**
     * The accessibility-style tree as one coalesced delta since the last snapshot.
     */
    public Snapshot snapshot() {
        return this.snapshot(SnapshotMode.AUTO, null);
    }

    public Snapshot snapshot(SnapshotMode mode) {
        return this.snapshot(mode, null);
    }

    /**
     * @param timeout the per-call budget, the server's default when null
     */
    public Snapshot snapshot(SnapshotMode mode, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("mode", mode.wire());
        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_snapshot", arguments);
        this.absorb(structured);
        this.lastSnapshot = Snapshot.decode(this.handle, structured, "web_snapshot");

        return this.lastSnapshot;
    }

    /**
     * Reader-mode markdown of the main content, plus entities that can be acted on.
     */
    public Article read() {
        return this.read(null);
    }

    public Article read(Duration timeout) {

        Map<String, Object> arguments = this.args();
        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_read", arguments);
        this.absorb(structured);

        return Article.decode(structured);
    }

    /**
     * Evaluate JavaScript in the page.
     *
     * @return the decoded value, or the text form when the result was truncated or not JSON
     */
    public Object evaluate(String code) {
        return this.evaluate(code, false, null);
    }

    /**
     * @param awaitPromise resolve a returned promise before answering
     */
    public Object evaluate(String code, boolean awaitPromise) {
        return this.evaluate(code, awaitPromise, null);
    }

    /**
     * @param timeout the per-call budget, the server's default when null
     * @throws FailedException when the page threw, carrying the message and stack
     */
    public Object evaluate(String code, boolean awaitPromise, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("code", code);

        if (awaitPromise) {
            arguments.put("await", true);
        }

        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_eval", arguments);
        this.absorb(structured);

        if (structured.containsKey("value")) {
            return unwrapEvalValue(structured.get("value"));
        }

        return Json.optStr(structured, "value_text");
    }

    /**
     * Evaluate JavaScript and write the WHOLE result to a file instead of into the answer.
     *
     * <p>The bytes go from the page to disk without passing through this session, which is what
     * makes a big scrape cost nothing to read. A string value is written as itself and anything else
     * as JSON, which {@link EvaluatedFile#format()} states rather than leaves to be guessed.</p>
     *
     * @param destination an absolute path on the machine running the server
     * @throws InvalidArgsException when the destination is not absolute
     * @throws IoFailedException when the server could not write the file
     * @throws FailedException when the page threw, carrying the message and stack
     */
    public EvaluatedFile evaluateToFile(String code, Path destination) {
        return this.evaluateToFile(code, destination, false, null);
    }

    /**
     * @param awaitPromise resolve a returned promise before writing the file
     * @param timeout the per-call budget, the server's default when null
     */
    public EvaluatedFile evaluateToFile(String code, Path destination, boolean awaitPromise, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("code", code);
        arguments.put("out_file", absolute(destination, "out_file", "web_eval"));

        if (awaitPromise) {
            arguments.put("await", true);
        }

        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_eval", arguments);
        this.absorb(structured);

        return EvaluatedFile.decode(structured);
    }

    /**
     * Download a url to a file, fetched by THIS view's browser, so its cookies, session and route
     * carry and a file behind a login needs no token of its own.
     *
     * <p>A {@link DownloadState#TIMED_OUT} answer is data, not a failure: the call's budget ran out
     * while the transfer was still running, and {@link #downloads()} can say later what became of
     * it.</p>
     *
     * @param destination an absolute file path on the machine running the server
     * @throws InvalidArgsException when the destination is not absolute
     */
    public Download download(String url, Path destination) {
        return this.download(url, destination, null);
    }

    /**
     * @param timeout the budget for the whole download, the server's default and ceiling being two
     *                minutes
     */
    public Download download(String url, Path destination, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("url", url);
        arguments.put("path", absolute(destination, "path", "web_download"));
        ToolCalls.putTimeout(arguments, timeout);

        DownloadBatch batch = this.downloaded(arguments);

        if (batch.downloads().size() != 1) {
            throw new ProtocolMismatchException("web_download answered with "
                    + batch.downloads().size() + " entries for one url");
        }

        return batch.downloads().getFirst();
    }

    /**
     * Download several urls into one directory, each named from its url's last path segment.
     *
     * <p>They are fetched ONE AT A TIME, so the whole batch shares the call's budget: past it the
     * remaining entries come back {@link DownloadState#NOT_STARTED} and the call can be repeated
     * with what is left.</p>
     *
     * @param directory an absolute directory path on the machine running the server
     * @throws InvalidArgsException when the directory is not absolute, the list is empty, or it
     *         holds more than {@link DownloadBatch#MAX_URLS} urls
     */
    public DownloadBatch download(List<String> urls, Path directory) {
        return this.download(urls, directory, null);
    }

    /**
     * @param timeout the budget for the whole call, not per file
     */
    public DownloadBatch download(List<String> urls, Path directory, Duration timeout) {

        if (urls == null || urls.isEmpty()) {
            throw new InvalidArgsException("web_download needs at least one url",
                    "web_download", false, null);
        }

        if (urls.size() > DownloadBatch.MAX_URLS) {
            throw new InvalidArgsException("web_download takes at most " + DownloadBatch.MAX_URLS
                    + " urls per call and was given " + urls.size()
                    + "; they are fetched one at a time, so split the batch",
                    "web_download", false, null);
        }

        Map<String, Object> arguments = this.args();
        arguments.put("urls", List.copyOf(urls));
        arguments.put("dir", absolute(directory, "dir", "web_download"));
        ToolCalls.putTimeout(arguments, timeout);

        return this.downloaded(arguments);
    }

    /**
     * Every download this view knows about, including the ones the PAGE started - a save button, a
     * blob it created - which land in the user's XDG download directory.
     *
     * @return the listing, whose {@link DownloadBatch#listing()} is true and whose counts are zero
     */
    public DownloadBatch downloads() {
        return this.downloaded(this.args());
    }

    /**
     * The first page of this view's captured exchanges, as metadata.
     *
     * @throws ConflictException when this view was opened without a capture
     * @throws UnavailableException with a GUI attached: capture is a headless-only feature
     */
    public CapturedExchanges captured() {
        return this.captured(0, null);
    }

    /**
     * One page of this view's FINISHED exchanges, oldest cursor first.
     *
     * <p>The cursor follows the order exchanges FINISHED, not their request order - responses
     * finish out of order, and paging by request seq would skip a slow one. Each exchange still
     * carries its request's {@code seq}, the join key with {@link #network()}.</p>
     *
     * @param since only exchanges past this cursor; a previous page's {@link CapturedExchanges#nextSince()}
     * @param max at most this many, the server's 50 when null (cap 500)
     */
    public CapturedExchanges captured(long since, Integer max) {
        return this.listCaptured(since, max, false, null);
    }

    /**
     * As {@link #captured(long, Integer)}, followed by the exchanges still IN FLIGHT (cursor 0).
     *
     * <p>A body the page never reads keeps its load open in the engine, so such an exchange stays in
     * flight until the page reads it or navigates away; its bytes are readable meanwhile.</p>
     */
    public CapturedExchanges capturedWithInFlight(long since, Integer max) {
        return this.listCaptured(since, max, true, null);
    }

    /**
     * List a page of exchanges AND write each one's response body into a directory, named
     * {@code <seq>.<ext>}, each exchange then carrying its {@link CapturedExchange#path()} and
     * digest. The bodies never pass through this session.
     *
     * @param directory an absolute directory on the machine running the server
     */
    public CapturedExchanges capturedToDirectory(Path directory, long since, Integer max) {
        return this.listCaptured(since, max, false, absolute(directory, "out_dir", "web_capture"));
    }

    /**
     * The newest capture cursor so far. Read it BEFORE an action (a scroll) and wait from it with
     * {@link #waitForResponse(CaptureFilter, long, Duration)}, so a response that finishes before
     * the wait starts still counts.
     */
    public long captureMark() {
        return this.listCaptured(0xFFFF_FFFFL, 1, false, null).headCursor();
    }

    /**
     * One exchange's response body, inline: text as UTF-8, a binary as base64.
     *
     * @param seq the exchange's {@link CapturedExchange#seq()}
     * @throws NotFoundException when no held exchange has that seq (never captured, or cleared)
     */
    public CapturedBody responseBody(long seq) {
        return this.body(seq, BodyPart.RESPONSE, null);
    }

    /**
     * @param maxInlineBytes the most body text (or base64) the answer carries, the server's 64 KiB
     *                       when null (at most 1 MiB); a longer body arrives as a flagged prefix
     */
    public CapturedBody responseBody(long seq, Integer maxInlineBytes) {
        return this.body(seq, BodyPart.RESPONSE, maxInlineBytes);
    }

    /**
     * One exchange's REQUEST body - what the page sent, e.g. a GraphQL POST's operation and
     * variables.
     */
    public CapturedBody requestBody(long seq) {
        return this.body(seq, BodyPart.REQUEST, null);
    }

    /**
     * Write one exchange's whole response body to a file, the way many MB of JSON should travel:
     * straight to disk, reported by identity only.
     *
     * @param destination an absolute path on the machine running the server
     */
    public CapturedBodyFile responseBodyToFile(long seq, Path destination) {
        return this.bodyToFile(seq, BodyPart.RESPONSE, destination);
    }

    public CapturedBodyFile bodyToFile(long seq, BodyPart part, Path destination) {

        Map<String, Object> arguments = this.args();
        arguments.put("seq", seq);
        arguments.put("part", part.wire());
        arguments.put("out_file", absolute(destination, "out_file", "web_capture"));

        Map<String, Object> structured = this.calls.structured("web_capture", arguments);
        this.absorb(structured);

        return CapturedBodyFile.decode(structured);
    }

    /**
     * Wait for the next captured response matching {@code filter} that finishes after THIS call
     * starts.
     *
     * <p>Racy after an action: a response can finish between the action and this call. For scroll
     * then wait, take {@link #captureMark()} first and use
     * {@link #waitForResponse(CaptureFilter, long, Duration)}.</p>
     *
     * @param filter the clauses a response must match; its byte caps are ignored, and empty types
     *               mean ANY class here; null matches every captured exchange
     * @throws TimeoutException when no such response finished inside the budget
     * @throws ConflictException when this view was opened without a capture
     */
    public WaitedResponse waitForResponse(CaptureFilter filter, Duration timeout) {
        return this.awaitResponse(filter, null, null, timeout);
    }

    /**
     * Wait for a captured response matching {@code filter} that finished after cursor
     * {@code since} - from {@link #captureMark()}, or a previous {@link WaitedResponse#nextSince()}.
     */
    public WaitedResponse waitForResponse(CaptureFilter filter, long since, Duration timeout) {
        return this.awaitResponse(filter, since, null, timeout);
    }

    /**
     * Wait for a captured response matching {@code filter} whose REQUEST came after network-log seq
     * {@code afterSeq}; one that already finished counts too.
     */
    public WaitedResponse waitForResponseAfterSeq(CaptureFilter filter, long afterSeq, Duration timeout) {
        return this.awaitResponse(filter, null, afterSeq, timeout);
    }

    /**
     * Free every captured exchange, in-flight ones included, giving their bytes back to the cap.
     */
    public CaptureChange clearCaptured() {
        return this.narrowCapture("clear", null);
    }

    /**
     * Free the finished exchanges up to a cursor - the ones already read.
     */
    public CaptureChange clearCaptured(long upto) {
        return this.narrowCapture("clear", upto);
    }

    /**
     * Stop recording new exchanges; what is held stays readable. A capture can only ever be
     * narrowed, never widened or re-enabled: that needs a new view.
     */
    public CaptureChange disableCapture() {
        return this.narrowCapture("disable", null);
    }

    /**
     * A PNG of this view.
     */
    public Screenshot screenshot() {

        Map<String, Object> arguments = this.args();
        ToolResult result = this.calls.call("web_screenshot", arguments);
        Map<String, Object> structured = this.calls.structured("web_screenshot", arguments, result);
        this.absorb(structured);

        for (Content block : result.content()) {
            if (block instanceof Content.Image image) {

                Long width = Json.optLong(structured, "width");
                Long height = Json.optLong(structured, "height");
                Long bytes = Json.optLong(structured, "bytes");

                return new Screenshot(Base64.getDecoder().decode(image.data()),
                        image.mimeType(),
                        width == null ? 0 : width.intValue(),
                        height == null ? 0 : height.intValue(),
                        bytes == null ? 0 : bytes.intValue());
            }
        }

        throw new ProtocolMismatchException("web_screenshot answered without an image content block");
    }

    /**
     * Wait until the view reaches a state.
     *
     * @throws TimeoutException when the condition never held
     */
    public WaitResult waitFor(WaitFor condition) {
        return this.waitFor(condition, null, null);
    }

    public WaitResult waitFor(WaitFor condition, String argument) {
        return this.waitFor(condition, argument, null);
    }

    /**
     * @param argument the text or title fragment; ignored by conditions that take none
     * @param timeout the wait budget, the server's default when null
     */
    public WaitResult waitFor(WaitFor condition, String argument, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("for", condition.wire());

        if (argument != null && condition.acceptsArgument()) {
            arguments.put("arg", argument);
        }

        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_wait", arguments);
        this.absorb(structured);

        return WaitResult.decode(structured);
    }

    /**
     * Scroll by wheel deltas and report the settled position.
     */
    public ScrollResult scrollBy(int dx, int dy) {

        Map<String, Object> arguments = this.args();
        arguments.put("dx", dx);
        arguments.put("dy", dy);

        return this.scrolled(arguments);
    }

    public ScrollResult scrollTo(ScrollTo where) {

        Map<String, Object> arguments = this.args();
        arguments.put("to", where.wire());

        return this.scrolled(arguments);
    }

    /**
     * Scroll a node into view; unlike {@link Action#SCROLL_INTO_VIEW} this reports the position.
     */
    public ScrollResult scrollTo(Ref ref) {

        this.refuseStale(ref);

        Map<String, Object> arguments = this.args();
        arguments.put("to", ref.id());

        return this.scrolled(arguments);
    }

    /**
     * The blocking counters plus the most recent requests.
     */
    public NetworkLog network() {
        return this.network(null, null, null);
    }

    /**
     * @return only the requests this view logged, oldest first
     */
    public List<NetworkLog.NetworkRequest> networkRequests() {
        return this.network().requests();
    }

    /**
     * @param since only entries newer than a previous {@link NetworkLog#nextSeq()}
     * @param max the entry cap; the server's default is 50 and its ceiling 128
     */
    public NetworkLog network(Long since, Integer max) {
        return this.network(null, since, max);
    }

    /**
     * Flip content blocking for this view, or just read the counters.
     */
    public NetworkLog network(NetworkAction action) {
        return this.network(action, null, null);
    }

    public NetworkLog network(NetworkAction action, Long since, Integer max) {

        Map<String, Object> arguments = this.args();
        ToolCalls.put(arguments, "action", action == null ? null : action.wire());
        ToolCalls.put(arguments, "since", since);
        ToolCalls.put(arguments, "max", max);

        Map<String, Object> structured = this.calls.structured("web_network", arguments);
        this.absorb(structured);

        return NetworkLog.decode(structured);
    }

    /**
     * The full text of a node the snapshot truncated.
     */
    public ExpandedText expand(Ref ref) {
        return this.expand(ref, null, null);
    }

    /**
     * @param offset where in the node's text to start
     * @param length the page size; the server's default is 8000 and its ceiling 60000
     */
    public ExpandedText expand(Ref ref, Integer offset, Integer length) {

        this.refuseStale(ref);

        return this.expandId(ref.id(), offset, length);
    }

    /**
     * Page the last web_eval result on this view, which the wire addresses as id 0.
     */
    public ExpandedText expandEvalResult(Integer offset, Integer length) {
        return this.expandId(0, offset, length);
    }

    /**
     * Act on a semantic id.
     *
     * @throws StaleRefException when the ref predates the page's current document, or when the page
     *         itself refuses the id
     */
    public ActResult act(Ref ref, Action action) {
        return this.act(ref, action, null, null);
    }

    /**
     * @param value the text to type or the option to choose
     */
    public ActResult act(Ref ref, Action action, String value) {
        return this.act(ref, action, value, null);
    }

    public ActResult act(Ref ref, Action action, String value, Duration timeout) {

        this.refuseStale(ref);

        if (action.requiresValue() && value == null) {
            throw new InvalidArgsException("Action " + action + " needs a value", "web_act", false, null);
        }

        Map<String, Object> arguments = this.args();
        arguments.put("id", ref.id());
        arguments.put("action", action.wire());
        ToolCalls.put(arguments, "value", value);
        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_act", arguments);
        this.absorb(structured);

        ActResult result = ActResult.decode(structured);

        if (result.navigated()) {
            // A new document invalidates every id handed out for the old one, and the act reply
            // carries no new document counter to adopt.
            this.document = 0;
        }

        return result;
    }

    public ActResult click(Ref ref) {
        return this.act(ref, Action.CLICK);
    }

    /**
     * Type into a field, pick a native option, or open a custom dropdown and choose in it.
     */
    public ActResult fill(Ref ref, String value) {
        return this.act(ref, Action.SET_VALUE, value);
    }

    public ActResult focus(Ref ref) {
        return this.act(ref, Action.FOCUS);
    }

    public ActResult hover(Ref ref) {
        return this.act(ref, Action.HOVER);
    }

    public ActResult scrollIntoView(Ref ref) {
        return this.act(ref, Action.SCROLL_INTO_VIEW);
    }

    /**
     * A cheap spot-check against the tree as last sent, which may be stale.
     *
     * @return the matching nodes in the same notation {@link Snapshot} parses
     */
    public List<TreeNode> query(QueryKind kind, String argument) {

        Map<String, Object> arguments = this.args();
        arguments.put("kind", kind.wire());
        ToolCalls.put(arguments, "arg", argument);

        Map<String, Object> structured = this.calls.structured("web_query", arguments);
        this.absorb(structured);

        return TreeText.parse(Json.optStr(structured, "matches"));
    }

    /**
     * @return the first node whose name contains the text, addressable in this page's document
     */
    public Optional<Ref> findText(String text) {

        for (TreeNode node : this.query(QueryKind.FIND_TEXT, text)) {
            return Optional.of(new Ref(node.id(), this.document, this.revision));
        }

        return Optional.empty();
    }

    /**
     * Find a node by its text and click it, the one-liner for "press the button that says X".
     *
     * @throws NotFoundException when nothing in the last-sent tree carries that text
     */
    public ActResult clickText(String text) {

        Ref ref = this.findText(text).orElseThrow(() -> new NotFoundException(
                "No node in the last-sent tree has a name containing '" + text
                        + "'; take a snapshot if the page just changed",
                "web_query", false, null));

        return this.click(ref);
    }

    @Override
    public String toString() {
        return "Page[" + this.handle + " " + this.url + "]";
    }

    /**
     * Adopt every fact the answer carried; absent fields leave the previous value alone.
     */
    private void absorb(Map<String, Object> structured) {

        if (structured == null) {
            return;
        }

        String backend = Json.optStr(structured, "backend");
        String url = Json.optStr(structured, "url");
        String title = Json.optStr(structured, "title");

        if (backend != null) {
            this.backend = backend;
        }

        if (url != null) {
            this.url = url;
        }

        if (title != null) {
            this.title = title;
        }

        if (structured.containsKey("loading")) {
            this.loading = Json.optBool(structured, "loading", false);
        }

        // Only web_open and web_tabs carry the identity, and only headless; every other answer
        // leaves what this page already knows alone.
        ProfileKind kind = PageInfo.kindOf(structured);

        if (kind != null) {

            String profile = Json.optStr(structured, "profile");
            Long context = Json.optLong(structured, "context");

            this.profileKind = kind;
            this.profile = profile == null || profile.isEmpty() ? null : profile;
            this.context = context == null ? 0 : context.intValue();
        }

        this.absorbPolicy(structured);

        // web_open and web_tabs report capture_active, the capture calls report capture_state.
        if (structured.containsKey("capture_active")) {
            this.captureActive = Json.optBool(structured, "capture_active", false);
        }

        String captureState = Json.optStr(structured, "capture_state");

        if (captureState != null) {
            this.captureActive = CaptureState.require(captureState) == CaptureState.ACTIVE;
        }

        Long document = Json.optLong(structured, "document");
        Long revision = Json.optLong(structured, "revision");

        if (document != null) {
            this.document = document.intValue();
        }

        if (revision != null) {
            this.revision = revision.intValue();
        }
    }

    /**
     * AIDEV-NOTE: the exhaustion fact is emitted only when it is TRUE - an unpoliced view and a
     * view whose budgets still hold both simply omit it - so absence must never be read as "the
     * budgets came back". It cannot: latching is permanent per view, so once absorbed the flag
     * stays until the page is closed. The policy_* trio, by contrast, is carried only by web_open
     * and web_policy(_set), which is why each is adopted per key rather than per answer.
     */
    private void absorbPolicy(Map<String, Object> structured) {

        if (structured.containsKey("policy_active")) {
            this.policyActive = Json.optBool(structured, "policy_active", false);
        }

        PolicySource source = PolicySource.fromWire(Json.optStr(structured, "policy_source"));

        if (source != null) {
            this.policySource = source;
            this.policyActive = this.policyActive || source != PolicySource.NONE;
        }

        Long serial = Json.optLong(structured, "policy_serial");

        if (serial != null) {
            this.policySerial = serial.intValue();
        }

        if (Json.optBool(structured, "policy_exhausted", false)) {
            this.policyExhausted = true;
            this.policyExhaustedReason = DenialReason.requireExhaustion(
                    Json.optStr(structured, "policy_exhausted_reason"));
        }
    }

    /**
     * AIDEV-NOTE: every per-view call goes through here, so this is THE place the closed check
     * lives. The handle is not reserved after a close - the helper is free to hand it to the next
     * view - so sending the call anyway could quietly drive a page nobody asked for.
     */
    private Map<String, Object> args() {

        if (this.closed) {
            throw new PageClosedException(this.handle, "a web_* call");
        }

        Map<String, Object> arguments = ToolCalls.args();
        arguments.put("pane", this.handle);

        return arguments;
    }

    private NavigationResult navigated(Map<String, Object> arguments) {

        Map<String, Object> structured = this.calls.structured("web_navigate", arguments);
        String before = this.url;
        this.absorb(structured);

        NavigationResult result = NavigationResult.decode(structured);

        if (before != null && !before.equals(this.url)) {
            // web_navigate reports no document counter, so the ids of the page we left are simply
            // unknowable until the next snapshot; 0 means "let the server judge staleness".
            this.document = 0;
        }

        return result;
    }

    private ScrollResult scrolled(Map<String, Object> arguments) {

        Map<String, Object> structured = this.calls.structured("web_scroll", arguments);
        this.absorb(structured);

        return ScrollResult.decode(structured);
    }

    private CapturedExchanges listCaptured(long since, Integer max, boolean inFlight, String outDir) {

        Map<String, Object> arguments = this.args();

        if (since != 0) {
            arguments.put("since", since);
        }

        ToolCalls.put(arguments, "max", max);

        if (inFlight) {
            arguments.put("include_in_flight", true);
        }

        ToolCalls.put(arguments, "out_dir", outDir);

        Map<String, Object> structured = this.calls.structured("web_capture", arguments);
        this.absorb(structured);

        return CapturedExchanges.decode(structured);
    }

    private CapturedBody body(long seq, BodyPart part, Integer maxInlineBytes) {

        Map<String, Object> arguments = this.args();
        arguments.put("seq", seq);
        arguments.put("part", part.wire());
        ToolCalls.put(arguments, "max_bytes", maxInlineBytes);

        Map<String, Object> structured = this.calls.structured("web_capture", arguments);
        this.absorb(structured);

        return CapturedBody.decode(structured);
    }

    private WaitedResponse awaitResponse(CaptureFilter filter, Long since, Long afterSeq, Duration timeout) {

        Map<String, Object> arguments = this.args();
        arguments.put("for", WaitFor.RESPONSE.wire());

        if (filter != null) {

            Map<String, Object> match = filter.toMatchWire();

            if (!match.isEmpty()) {
                arguments.put("response", match);
            }
        }

        ToolCalls.put(arguments, "since", since);
        ToolCalls.put(arguments, "after_seq", afterSeq);
        ToolCalls.putTimeout(arguments, timeout);

        Map<String, Object> structured = this.calls.structured("web_wait", arguments);
        this.absorb(structured);

        return WaitedResponse.decode(structured);
    }

    private CaptureChange narrowCapture(String action, Long upto) {

        Map<String, Object> arguments = this.args();
        arguments.put("action", action);
        ToolCalls.put(arguments, "upto", upto);

        Map<String, Object> structured = this.calls.structured("web_capture_set", arguments);
        this.absorb(structured);

        CaptureChange change = CaptureChange.decode(structured);
        this.captureActive = change.state() == CaptureState.ACTIVE;

        return change;
    }

    private DownloadBatch downloaded(Map<String, Object> arguments) {

        Map<String, Object> structured = this.calls.structured("web_download", arguments);
        this.absorb(structured);

        return DownloadBatch.decode(structured);
    }

    /**
     * AIDEV-NOTE: the server refuses a relative path itself; refusing it here keeps a doomed call
     * from going out, the same rule ProfileNames applies to a profile name.
     */
    private static String absolute(Path path, String argument, String tool) {

        if (path == null || !path.isAbsolute()) {
            throw new InvalidArgsException("'" + argument + "' must be an absolute path on the"
                    + " machine running the server, not " + path, tool, false, null);
        }

        return path.toString();
    }

    private ExpandedText expandId(int id, Integer offset, Integer length) {

        Map<String, Object> arguments = this.args();
        arguments.put("id", id);
        ToolCalls.put(arguments, "offset", offset);
        ToolCalls.put(arguments, "len", length);

        Map<String, Object> structured = this.calls.structured("web_expand", arguments);
        this.absorb(structured);

        return ExpandedText.decode(structured);
    }

    /**
     * AIDEV-NOTE: staleness is judged per DOCUMENT, not per revision. A revision moves on every
     * change the page makes, including the one an act itself causes, so refusing a ref older than
     * the current revision would reject clicking the same button twice - which the server accepts.
     * A new document is what actually invalidates every id, and the server agrees ("unknown id").
     */
    private void refuseStale(Ref ref) {

        if (!ref.hasProvenance() || this.document <= 0 || ref.document() == this.document) {
            return;
        }

        throw new StaleRefException("Ref " + ref + " came from document " + ref.document()
                + " but this page is on document " + this.document
                + "; take a fresh snapshot and act on the new ref", ref);
    }

    private static Object unwrapEvalValue(Object value) {

        // The authenticated page bridge wraps its encoded result as {"value": ...}; MCP then
        // carries that object under structuredContent.value. Remove exactly the bridge envelope.
        // A page object {value: 2} consequently arrives as {value: {value: 2}} and remains intact.
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("value")) {
            return map.get("value");
        }

        return value;
    }

}
