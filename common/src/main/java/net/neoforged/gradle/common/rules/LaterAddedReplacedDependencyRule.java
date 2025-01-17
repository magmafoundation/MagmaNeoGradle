package net.neoforged.gradle.common.rules;

import net.neoforged.gradle.dsl.common.extensions.subsystems.Conventions;
import net.neoforged.gradle.dsl.common.extensions.subsystems.Subsystems;
import net.neoforged.gradle.dsl.common.runs.run.Run;
import net.neoforged.gradle.dsl.common.runs.run.RunManager;
import org.gradle.api.Project;
import org.gradle.api.Rule;

public class LaterAddedReplacedDependencyRule implements Rule {

    private final Project project;
    private final RunManager runs;

    public LaterAddedReplacedDependencyRule(Project project) {
        this.runs = project.getExtensions().getByType(RunManager.class);
        this.project = project;
    }

    @Override
    public String getDescription() {
        return "Pattern run<RunName>: Runs the specified run.";
    }

    @Override
    public void apply(String domainObjectName) {
        if (!domainObjectName.startsWith("run")) {
            return;
        }

        //This prevents things like runtime from triggering the task creation.
        if (domainObjectName.length() < 4 || !Character.isUpperCase(domainObjectName.charAt(3))) {
            return;
        }

        final Conventions conventions = project.getExtensions().getByType(Subsystems.class).getConventions();
        if (conventions.getIsEnabled().get() && conventions.getRuns().getIsEnabled().get() && conventions.getRuns().getShouldDefaultRunsBeCreated().get()) {
            final String runName = domainObjectName.substring(3);

            Run run = runs.findByName(runName);
            if (run == null) {
                final String decapitalizedRunName = runName.substring(0, 1).toLowerCase() + runName.substring(1);
                run = runs.findByName(decapitalizedRunName);
                if (run == null) {
                    runs.create(decapitalizedRunName);
                }
            }
        }
    }
}
