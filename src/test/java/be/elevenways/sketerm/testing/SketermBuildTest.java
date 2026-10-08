package be.elevenways.sketerm.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which checkout build a real-browser test runs, and its private copy.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SketermBuildTest {

    @Test
    @DisplayName("The property names the checkout build, then the variable, else there is none")
    void checkoutOrder() {

        // 1. The property wins over the variable
        assertEquals(Path.of("/a"), SketermBuild.checkout("/a", "/b"), "step 1: the property");

        // 2. A blank property leaves the variable
        assertEquals(Path.of("/b"), SketermBuild.checkout(" ", "/b"), "step 2: the variable");

        // 3. Neither names a build, and no sibling directory is guessed
        assertNull(SketermBuild.checkout(null, ""), "step 3: no checkout build");
    }

    @Test
    @DisplayName("A complete build is copied whole and runnable; a partial one is refused by the binary it lacks")
    void copy(@TempDir Path root) throws IOException {

        Path build = Files.createDirectories(root.resolve("zig-out/bin"));
        for (String name : SketermBuild.BINARIES) {
            Files.writeString(build.resolve(name), name);
            assertTrue(build.resolve(name).toFile().setExecutable(true), "a runnable fake " + name);
        }

        // 1. Every binary lands in the target, executable, and the server is returned
        Path server = SketermBuild.copy(build, root.resolve("copy"));
        assertEquals(root.resolve("copy/sketerm").toAbsolutePath(), server, "step 1: the copied server");
        for (String name : SketermBuild.BINARIES) {
            assertEquals(name, Files.readString(server.resolveSibling(name)), "step 1: copied " + name);
            assertTrue(Files.isExecutable(server.resolveSibling(name)), "step 1: runnable " + name);
        }

        // 2. A build without its mux is refused, naming it
        Files.delete(build.resolve("sketerm-mux"));
        IllegalStateException partial = assertThrows(IllegalStateException.class,
                () -> SketermBuild.copy(build, root.resolve("partial")), "step 2: a partial build is an error");
        assertTrue(partial.getMessage().contains("sketerm-mux"), "step 2: naming " + partial.getMessage());
    }
}
