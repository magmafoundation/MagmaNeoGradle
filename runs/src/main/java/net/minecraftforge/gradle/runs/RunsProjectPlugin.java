package net.minecraftforge.gradle.runs;

import net.minecraftforge.gradle.dsl.runs.run.Runs;
import net.minecraftforge.gradle.dsl.runs.type.Types;
import net.minecraftforge.gradle.runs.run.RunsImpl;
import net.minecraftforge.gradle.runs.type.TypesImpl;
import net.minecraftforge.gradle.runs.util.RunsConstants;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RunsProjectPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().add(
                Types.class,
                RunsConstants.Extensions.RUN_TYPES,
                project.getObjects().newInstance(TypesImpl.class, project)
        );
        project.getExtensions().add(
                Runs.class,
                RunsConstants.Extensions.RUNS,
                project.getObjects().newInstance(RunsImpl.class, project)
        );
    }
}