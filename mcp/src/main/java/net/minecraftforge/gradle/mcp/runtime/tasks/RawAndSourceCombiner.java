package net.minecraftforge.gradle.mcp.runtime.tasks;

import com.google.common.io.Files;
import net.minecraftforge.gradle.common.tasks.ForgeGradleBaseTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;

public abstract class RawAndSourceCombiner extends ForgeGradleBaseTask {

    @TaskAction
    public void doCombine() {
        final File rawJarOutput = ensureFileWorkspaceReady(getRawJarOutput());
        final File sourceJarOutput = ensureFileWorkspaceReady(getSourceJarOutput());

        final File rawJarInput = getRawJarInput().getAsFile().get();
        final File sourceJarInput = getSourceJarInput().getAsFile().get();

        copy(rawJarInput, rawJarOutput);
        copy(sourceJarInput, sourceJarOutput);
    }

    private static void copy(final File input, final File output) {
        try {
            Files.copy(input, output);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy: %s to output: %s".formatted(input.getAbsolutePath(), output.getAbsolutePath()), e);
        }
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getRawJarInput();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceJarInput();

    @OutputFile
    public abstract RegularFileProperty getRawJarOutput();

    @OutputFile
    public abstract RegularFileProperty getSourceJarOutput();
}
