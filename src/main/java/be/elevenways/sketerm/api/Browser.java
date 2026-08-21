package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The browser face of a session: it opens views and lists the ones that are open.
 *
 * <p>With a GUI attached these are the user's own tabs; headless they are views this server's own
 * browser engine hosts. Either way a view is a {@link Page} addressed by an integer handle.</p>
 */
public final class Browser {

    private final ToolCalls calls;

    Browser(ToolCalls calls) {
        this.calls = calls;
    }

    /**
     * Open a new view on a url and wait for that first navigation to settle.
     *
     * @throws ProtocolMismatchException when the answer carries no view handle
     */
    public Page openPage(String url) {
        return this.openPage(url, OpenOptions.defaults());
    }

    /**
     * @param options the viewport and settle budget; null means the server's defaults
     */
    public Page openPage(String url, OpenOptions options) {

        Map<String, Object> arguments = ToolCalls.args();
        ToolCalls.put(arguments, "url", url);

        if (options != null) {
            ToolCalls.put(arguments, "width", options.width());
            ToolCalls.put(arguments, "height", options.height());
            ToolCalls.putTimeout(arguments, options.timeout());
        }

        Map<String, Object> structured = this.calls.structured("web_open", arguments);
        int handle = Handles.of(structured, "web_open");

        return new Page(this.calls, handle, structured, Snapshot.decode(handle, structured, "web_open"));
    }

    /**
     * Open a blank view.
     */
    public Page openPage() {
        return this.openPage(null, OpenOptions.defaults());
    }

    /**
     * @return every open view, the current one flagged
     */
    public List<PageInfo> pages() {
        return listPages(this.calls);
    }

    /**
     * Address an already-open view.
     *
     * @throws NotFoundException when no view has that handle
     */
    public Page page(int handle) {

        for (PageInfo info : this.pages()) {
            if (info.handle() == handle) {
                return new Page(this.calls, handle, factsOf(info), null);
            }
        }

        throw new NotFoundException("No open view has handle " + handle, "web_tabs", false, null);
    }

    /**
     * @return the view a web_* call with no handle would address, or null when none is open
     */
    public Page currentPage() {

        for (PageInfo info : this.pages()) {
            if (info.current()) {
                return new Page(this.calls, info.handle(), factsOf(info), null);
            }
        }

        return null;
    }

    static List<PageInfo> listPages(ToolCalls calls) {

        Map<String, Object> structured = calls.structured("web_tabs", ToolCalls.args());
        List<Object> raw = Json.optList(structured, "views");
        List<PageInfo> pages = new ArrayList<>();

        if (raw != null) {
            for (Object element : raw) {
                pages.add(PageInfo.decode(Json.asMap(element, "a web_tabs entry")));
            }
        }

        return List.copyOf(pages);
    }

    private static Map<String, Object> factsOf(PageInfo info) {

        Map<String, Object> facts = ToolCalls.args();
        ToolCalls.put(facts, "url", info.url());
        ToolCalls.put(facts, "title", info.title());
        facts.put("loading", info.loading());

        return facts;
    }
}
