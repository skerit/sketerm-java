package be.elevenways.sketerm.testing;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The Sketerm a real-browser test runs: the checkout build named by the sketerm.it.bin property or the SKETERM_IT_BIN
 * variable, else the installed one on PATH.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class SketermBuild {

    public static final String PROPERTY = "sketerm.it.bin";

    public static final String VARIABLE = "SKETERM_IT_BIN";

    /** Why a test that takes {@link #resolve} skips. */
    public static final String NO_SKETERM = "no sketerm: set " + PROPERTY + " or " + VARIABLE + ", or install sketerm";

    /** Why a test that takes {@link #copy} skips. */
    public static final String NO_CHECKOUT = "no checkout build: set " + PROPERTY + " or " + VARIABLE;

    /** The server and the helpers it finds beside itself. */
    public static final List<String> BINARIES = List.of("sketerm", "sketerm-webengine", "sketerm-mux");

    private SketermBuild() {
    }

    /**
     * Copies the checkout build into target, so a rebuild mid-run never swaps a binary under a running child.
     *
     * @return the copied sketerm, or null when no checkout build is named
     * @throws IllegalStateException when the named build lacks one of {@link #BINARIES}
     */
    public static @Nullable Path copy(@NonNull Path target) throws IOException {
        Path checkout = checkout(System.getProperty(PROPERTY), System.getenv(VARIABLE));
        return checkout == null ? null : copy(checkout, target);
    }

    /**
     * @return the copied checkout build, else the installed sketerm with its helpers beside it, else null
     */
    public static @Nullable Path resolve(@NonNull Path target) throws IOException {
        Path copied = copy(target);
        return copied != null ? copied : installed();
    }

    /**
     * @return the first sketerm on PATH with every helper beside it, or null
     */
    public static @Nullable Path installed() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            Path server = Path.of(entry, BINARIES.get(0)).toAbsolutePath();
            if (BINARIES.stream().allMatch(name -> Files.isExecutable(server.resolveSibling(name)))) return server;
        }
        return null;
    }

    static @Nullable Path checkout(@Nullable String property, @Nullable String variable) {
        if (property != null && !property.isBlank()) return Path.of(property);
        if (variable != null && !variable.isBlank()) return Path.of(variable);
        return null;
    }

    static @NonNull Path copy(@NonNull Path checkout, @NonNull Path target) throws IOException {
        Files.createDirectories(target);
        for (String name : BINARIES) {
            Path source = checkout.resolve(name);
            if (!Files.isExecutable(source)) {
                throw new IllegalStateException("the Sketerm build in " + checkout + " has no executable " + name);
            }
            Path copy = target.resolve(name);
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            if (!copy.toFile().setExecutable(true)) throw new IOException("cannot make " + copy + " executable");
        }
        return target.resolve(BINARIES.get(0)).toAbsolutePath();
    }
}
