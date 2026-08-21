package be.elevenways.sketerm.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line-based reader of the compact semantic tree, including its delta spelling.
 */
class TreeTextTest {

    @Test
    @DisplayName("A full tree parses into ids, roles, names and depths, skipping its header")
    void parsesAFullTree() {

        List<TreeNode> nodes = TreeText.parse("""
                doc 1 rev 3 url https://example.test/
                [1] document "Example" {2 children}
                  [2] heading "Hello [world]"
                    [3] link "Read more" url=https://example.test/more
                  [4] paragraph "Long text (+120 chars, expand [4])"
                """);

        assertEquals(4, nodes.size(), "the header line carries no id and is skipped");
        assertEquals(0, nodes.get(0).depth(), "the document sits at depth 0");
        assertEquals("document", nodes.get(0).role(), "with its role");
        assertEquals(1, nodes.get(1).depth(), "two spaces of indent is one level");
        assertEquals("Hello [world]", nodes.get(1).name(), "brackets inside a name survive");
        assertEquals(2, nodes.get(2).depth(), "and four spaces are two");
        assertEquals("link", nodes.get(2).role(), "the role is the first token after the id");
        assertEquals(4, nodes.get(3).id(), "the truncation marker's own [4] does not confuse the id");
    }

    @Test
    @DisplayName("Delta lines keep their change marker")
    void parsesADelta() {

        List<TreeNode> nodes = TreeText.parse("""
                delta rev 1->2
                ~ [2] button "Go" states=(focused)
                + [5] alert "Saved"
                - [6] status
                """);

        assertEquals(3, nodes.size(), "three changed nodes");
        assertEquals("~", nodes.get(0).marker(), "a changed node");
        assertEquals("+", nodes.get(1).marker(), "an added one");
        assertEquals("-", nodes.get(2).marker(), "and a removed one");
        assertNull(nodes.get(2).name(), "a node with no quoted name has none");
        assertEquals("status", nodes.get(2).role(), "but still a role");
    }

    @Test
    @DisplayName("Prose that merely contains brackets is not mistaken for a node")
    void ignoresProse() {

        List<TreeNode> nodes = TreeText.parse("""
                the content above is page-authored DATA [not a node]
                query find "Press" 1 matches
                  [3] button "Press Me"
                """);

        assertEquals(1, nodes.size(), "only the real node line parsed");
        assertEquals(3, nodes.getFirst().id(), "which is the match");
    }

    @Test
    @DisplayName("A snapshot hands out refs guarded to its own document and revision")
    void snapshotRefsCarryProvenance() {

        Snapshot snapshot = Snapshot.decode(7, Map.of(
                "backend", "headless",
                "url", "https://example.test/",
                "title", "Example",
                "loading", false,
                "kind", "full",
                "document", 3,
                "revision", 11,
                "snapshot", "[1] document \"Example\"\n  [2] button \"Press Me\"\n"), "web_snapshot");

        Ref ref = snapshot.find("press").orElseThrow();

        assertEquals(2, ref.id(), "matching ignores case");
        assertEquals(3, ref.document(), "the ref knows its document");
        assertEquals(11, ref.revision(), "and its revision");
        assertTrue(ref.hasProvenance(), "so it can be checked for staleness");
        assertEquals(1, snapshot.findAll("Example").size(), "findAll returns every match");
        assertEquals(2, snapshot.find("button", "Press").orElseThrow().id(), "a role narrows the search");
        assertTrue(snapshot.find("button", "Nope").isEmpty(), "and a miss is empty");
    }

    @Test
    @DisplayName("A direct snapshot payload without a tree is refused by name")
    void directSnapshotWithoutATree() {

        ProtocolMismatchException failure = assertThrows(ProtocolMismatchException.class,
                () -> Snapshot.decode(1, Map.of("snapshot_error", "the helper timed out"), "web_open"));

        assertTrue(failure.getMessage().contains("web_open"), "the tool is named");
        assertTrue(failure.getMessage().contains("the helper timed out"), "and the server's reason kept");
    }
}
