package net.minecraftforge.gradle.mcp.runtime.tasks;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class FileCacheProviding extends McpRuntime {

    @TaskAction
    public void provide() throws IOException {
        final Path output = ensureFileWorkspaceReady(getOutput()).toPath();
        final Path source = getFileCache().get().getAsFile().toPath().resolve(getSelector().get().getCacheFileName());

        if (!Files.exists(source)) {
            throw new IllegalStateException("Source file does not exist: " + source);
        }

        Files.copy(source, output);
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getFileCache();

    @Nested
    public abstract Property<ISelector> getSelector();

    public interface ISelector extends Serializable {

        static ISelector launcher() {
            return () -> "launcher_metadata.json";
        }

        static ISelector forVersion(final String version) {
            return () -> "versions/%s.json".formatted(version);
        }

        @Input
        String getCacheFileName();
    }
}
