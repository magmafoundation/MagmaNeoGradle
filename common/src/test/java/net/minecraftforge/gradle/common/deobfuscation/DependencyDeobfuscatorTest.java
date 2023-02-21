package net.minecraftforge.gradle.common.deobfuscation;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import net.minecraftforge.gradle.base.file.FileTestingUtils;
import net.minecraftforge.gradle.base.file.TestFileTarget;
import net.minecraftforge.gradle.base.tasks.TaskMockingUtils;
import net.minecraftforge.gradle.dsl.common.extensions.Mappings;
import net.minecraftforge.gradle.dsl.common.extensions.dependency.replacement.Context;
import net.minecraftforge.gradle.dsl.common.extensions.dependency.replacement.DependencyReplacement;
import net.minecraftforge.gradle.dsl.common.extensions.dependency.replacement.DependencyReplacementHandler;
import net.minecraftforge.gradle.dsl.common.extensions.dependency.replacement.DependencyReplacementResult;
import net.minecraftforge.gradle.dsl.common.extensions.dependency.replacement.DependencyReplacer;
import net.minecraftforge.gradle.dsl.common.extensions.repository.RepositoryEntry;
import net.minecraftforge.gradle.dsl.common.extensions.repository.RepositoryReference;
import net.minecraftforge.gradle.dsl.common.runtime.naming.NamingChannel;
import net.minecraftforge.gradle.util.ModuleDependencyUtils;
import net.minecraftforge.gradle.util.ResolvedDependencyUtils;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
public class DependencyDeobfuscatorTest {

    @Test
    public void applyingTheExtensionCreatesADependencyReplacementHandler() {
        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);

        DependencyDeobfuscator.getInstance().apply(project);

        verify(handlers, times(1)).create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class));
    }

    @Test
    public void weDoNotDeobfuscateADependencyThatIsNotAnExternalModule() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ProjectDependency projectDependency = mock(ProjectDependency.class);

        when(context.getDependency()).thenReturn(projectDependency);

        //Now validate that the replacer returns an empty optional
        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyThatDoesNotExist() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.emptySet());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);

        //Now validate that the replacer returns an empty optional
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithoutFiles() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.emptySet());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);

        //Now validate that the replacer returns an empty optional
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithMultipleFiles() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Sets.newHashSet(mock(ResolvedDependency.class), mock(ResolvedDependency.class)));

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithoutAnArtifact() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.emptySet());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithMultipleArtifacts() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Sets.newHashSet(mock(ResolvedArtifact.class), mock(ResolvedArtifact.class)));

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithAMissingFile() {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("does-not-exist");

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithANoneJarFile() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("not-a-jar");

        Files.write(target.getPath(), "not a jar".getBytes(StandardCharsets.UTF_8));

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithAJarWithoutAManifest() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");

        final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target.getPath()));
        zip.flush();
        zip.close();

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDoNotDeobfuscateADependencyWithAJarWithoutTheProperFieldsInTheManifest() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any())).thenReturn(detachedConfiguration);
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        createSimpleJar(target);

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    public void weDeobfuscateADependencyWithASimpleJarWithoutDependencies() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");
        final TaskContainer taskContainer = mock(TaskContainer.class);
        final List<Task> tasks = new ArrayList<>();
        final Mappings mappings = mock(Mappings.class);
        final Property<NamingChannel> namingChannelProperty = mock(Property.class);
        final NamingChannel namingChannel = mock(NamingChannel.class);
        final Property<String> deobfuscationGroupSupplier = mock(Property.class);


        when(project.getExtensions()).thenReturn(extensionContainer);
        when(project.getTasks()).thenReturn(taskContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(extensionContainer.getByType(Mappings.class)).thenReturn(mappings);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any()))
                .thenReturn(detachedConfiguration)
                .then((Answer<Configuration>) invocation -> {
                    final Dependency[] dependenciesIn = invocation.getArguments().length > 0 ? invocation.getArgument(0) : new Dependency[0];
                    final Configuration configuration = mock(Configuration.class);
                    final DependencySet dependencies = mock(DependencySet.class);
                    when(configuration.getDependencies()).thenReturn(dependencies);
                    when(configuration.getFiles()).thenThrow(new RuntimeException("Tried to resolve a detached configuration that was mocked for a situation where that should not happen."));
                    when(dependencies.isEmpty()).thenReturn(dependenciesIn.length == 0);
                    return configuration;
                });
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());
        when(resolvedDependency.getChildren()).thenReturn(Collections.emptySet());
        when(resolvedDependency.getName()).thenReturn("Dummy");
        when(resolvedDependency.getModuleGroup()).thenReturn("group");
        when(resolvedDependency.getModuleName()).thenReturn("name");
        when(resolvedDependency.getModuleVersion()).thenReturn("version");
        when(resolvedArtifact.getClassifier()).thenReturn("classifier");
        when(resolvedArtifact.getExtension()).thenReturn("extension");
        when(mappings.getChannel()).thenReturn(namingChannelProperty);
        when(namingChannelProperty.get()).thenReturn(namingChannel);
        when(namingChannel.getDeobfuscationGroupSupplier()).thenReturn(deobfuscationGroupSupplier);
        when(namingChannel.getName()).thenReturn("naming_channel");
        when(deobfuscationGroupSupplier.get()).thenReturn("");

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        when(taskContainer.register(ArgumentMatchers.any(String.class), ArgumentMatchers.any(Class.class), ArgumentMatchers.any(Action.class))).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final Class<? extends Task> type = invocation.getArgument(1);
            final Action action = invocation.getArgument(2);
            final Task task = TaskMockingUtils.mockTask(type, project, name);

            tasks.add(task);
            action.execute(task);
            return TaskMockingUtils.mockTaskProvider(task);
        });

        createValidDeobfuscatableJar(target);

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertTrue(result.isPresent());

        final DependencyReplacementResult replacementResult = result.get();
        assertEquals(project, replacementResult.getProject());

        assertNotNull(replacementResult.getTaskNameBuilder());
        assertEquals("testDummy", replacementResult.getTaskNameBuilder().apply("test"));

        assertNotNull(replacementResult.getSourcesJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getSourcesJarTaskProvider().get()));

        assertNotNull(replacementResult.getRawJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getRawJarTaskProvider().get()));

        assertNotNull(replacementResult.getAdditionalDependenciesConfiguration());
        assertTrue(replacementResult.getAdditionalDependenciesConfiguration().getDependencies().isEmpty());

        assertNotNull(replacementResult.getDependencyMetadataConfigurator());
        final DummyRepositoryEntry entry = new DummyRepositoryEntry(project);
        replacementResult.getDependencyMetadataConfigurator().accept(entry);
        assertEquals("fg.deobf.naming_channel.group", entry.getGroup());
        assertEquals("name", entry.getName());
        assertEquals("version", entry.getVersion());
        assertEquals("classifier", entry.getClassifier());
        assertEquals("extension", entry.getExtension());
        assertTrue(entry.getDependencies().isEmpty());

        assertNotNull(replacementResult.getAdditionalReplacements());
        assertTrue(replacementResult.getAdditionalReplacements().isEmpty());

        assertNotNull(replacementResult.getDependencyBuilderConfigurator());
        final DummyRepositoryDependency dependencyBuilder = new DummyRepositoryDependency(project);
        replacementResult.getDependencyBuilderConfigurator().accept(dependencyBuilder);
        assertEquals("fg.deobf.naming_channel.group", dependencyBuilder.getGroup());
        assertEquals("name", dependencyBuilder.getName());
        assertEquals("version", dependencyBuilder.getVersion());
        assertEquals("classifier", dependencyBuilder.getClassifier());
        assertEquals("extension", dependencyBuilder.getExtension());

        assertNotNull(replacementResult.getAdditionalIdePostSyncTasks());
        assertTrue(replacementResult.getAdditionalIdePostSyncTasks().isEmpty());
    }

    @Test
    public void weDeobfuscateADependencyWithASimpleJarWithoutDependenciesUsingACustomDeobfuscationGroupFromTheNamingChannel() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");
        final TaskContainer taskContainer = mock(TaskContainer.class);
        final List<Task> tasks = new ArrayList<>();
        final Mappings mappings = mock(Mappings.class);
        final Property<NamingChannel> namingChannelProperty = mock(Property.class);
        final NamingChannel namingChannel = mock(NamingChannel.class);
        final Property<String> deobfuscationGroupSupplier = mock(Property.class);

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(project.getTasks()).thenReturn(taskContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(extensionContainer.getByType(Mappings.class)).thenReturn(mappings);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any()))
                .thenReturn(detachedConfiguration)
                .then((Answer<Configuration>) invocation -> {
                    final Dependency[] dependenciesIn = invocation.getArguments().length > 0 ? invocation.getArgument(0) : new Dependency[0];
                    final Configuration configuration = mock(Configuration.class);
                    final DependencySet dependencies = mock(DependencySet.class);
                    when(configuration.getDependencies()).thenReturn(dependencies);
                    when(configuration.getFiles()).thenThrow(new RuntimeException("Tried to resolve a detached configuration that was mocked for a situation where that should not happen."));
                    when(dependencies.isEmpty()).thenReturn(dependenciesIn.length == 0);
                    return configuration;
                });
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());
        when(resolvedDependency.getChildren()).thenReturn(Collections.emptySet());
        when(resolvedDependency.getName()).thenReturn("Dummy");
        when(resolvedDependency.getModuleGroup()).thenReturn("group");
        when(resolvedDependency.getModuleName()).thenReturn("name");
        when(resolvedDependency.getModuleVersion()).thenReturn("version");
        when(resolvedArtifact.getClassifier()).thenReturn("classifier");
        when(resolvedArtifact.getExtension()).thenReturn("extension");
        when(mappings.getChannel()).thenReturn(namingChannelProperty);
        when(namingChannelProperty.get()).thenReturn(namingChannel);
        when(namingChannel.getDeobfuscationGroupSupplier()).thenReturn(deobfuscationGroupSupplier);
        when(namingChannel.getName()).thenReturn("naming_channel");
        when(deobfuscationGroupSupplier.get()).thenReturn("some_group");

        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        when(taskContainer.register(ArgumentMatchers.any(String.class), ArgumentMatchers.any(Class.class), ArgumentMatchers.any(Action.class))).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final Class<? extends Task> type = invocation.getArgument(1);
            final Action action = invocation.getArgument(2);
            final Task task = TaskMockingUtils.mockTask(type, project, name);
            tasks.add(task);
            action.execute(task);
            return TaskMockingUtils.mockTaskProvider(task);
        });

        createValidDeobfuscatableJar(target);

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency dependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(dependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertTrue(result.isPresent());

        final DependencyReplacementResult replacementResult = result.get();
        assertEquals(project, replacementResult.getProject());

        assertNotNull(replacementResult.getTaskNameBuilder());
        assertEquals("testDummy", replacementResult.getTaskNameBuilder().apply("test"));

        assertNotNull(replacementResult.getSourcesJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getSourcesJarTaskProvider().get()));

        assertNotNull(replacementResult.getRawJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getRawJarTaskProvider().get()));

        assertNotNull(replacementResult.getAdditionalDependenciesConfiguration());
        assertTrue(replacementResult.getAdditionalDependenciesConfiguration().getDependencies().isEmpty());

        assertNotNull(replacementResult.getDependencyMetadataConfigurator());
        final DummyRepositoryEntry entry = new DummyRepositoryEntry(project);
        replacementResult.getDependencyMetadataConfigurator().accept(entry);
        assertEquals("fg.deobf.some_group.group", entry.getGroup());
        assertEquals("name", entry.getName());
        assertEquals("version", entry.getVersion());
        assertEquals("classifier", entry.getClassifier());
        assertEquals("extension", entry.getExtension());
        assertTrue(entry.getDependencies().isEmpty());

        assertNotNull(replacementResult.getAdditionalReplacements());
        assertTrue(replacementResult.getAdditionalReplacements().isEmpty());

        assertNotNull(replacementResult.getDependencyBuilderConfigurator());
        final DummyRepositoryDependency dependencyBuilder = new DummyRepositoryDependency(project);
        replacementResult.getDependencyBuilderConfigurator().accept(dependencyBuilder);
        assertEquals("fg.deobf.some_group.group", dependencyBuilder.getGroup());
        assertEquals("name", dependencyBuilder.getName());
        assertEquals("version", dependencyBuilder.getVersion());
        assertEquals("classifier", dependencyBuilder.getClassifier());
        assertEquals("extension", dependencyBuilder.getExtension());

        assertNotNull(replacementResult.getAdditionalIdePostSyncTasks());
        assertTrue(replacementResult.getAdditionalIdePostSyncTasks().isEmpty());
    }

    @Test
    public void weDeobfuscateADependencyWithASimpleJarWithNormalDependencies() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");
        final TaskContainer taskContainer = mock(TaskContainer.class);
        final List<Task> tasks = new ArrayList<>();
        final Mappings mappings = mock(Mappings.class);
        final Property<NamingChannel> namingChannelProperty = mock(Property.class);
        final NamingChannel namingChannel = mock(NamingChannel.class);
        final Property<String> deobfuscationGroupSupplier = mock(Property.class);
        final TestFileTarget dependentTarget = FileTestingUtils.newSimpleTestFileTarget("dependent.jar");
        final ResolvedDependency dependency = mock(ResolvedDependency.class);
        final ResolvedArtifact dependencyArtifact = mock(ResolvedArtifact.class);

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(project.getTasks()).thenReturn(taskContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(extensionContainer.getByType(Mappings.class)).thenReturn(mappings);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any()))
                .thenReturn(detachedConfiguration)
                .then((Answer<Configuration>) invocation -> {
                    final Dependency[] dependenciesIn = invocation.getArguments().length > 0 ? invocation.getArgument(0) : new Dependency[0];
                    final Configuration configuration = mock(Configuration.class);
                    final DependencySet dependencies = mock(DependencySet.class);
                    when(configuration.getDependencies()).thenReturn(dependencies);
                    when(configuration.getFiles()).thenThrow(new RuntimeException("Tried to resolve a detached configuration that was mocked for a situation where that should not happen."));
                    when(dependencies.isEmpty()).thenReturn(dependenciesIn.length == 0);
                    return configuration;
                });
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());
        when(resolvedDependency.getChildren()).thenReturn(Collections.singleton(dependency));
        when(resolvedDependency.getName()).thenReturn("Dummy");
        when(resolvedDependency.getModuleGroup()).thenReturn("group");
        when(resolvedDependency.getModuleName()).thenReturn("name");
        when(resolvedDependency.getModuleVersion()).thenReturn("version");
        when(resolvedArtifact.getClassifier()).thenReturn("classifier");
        when(resolvedArtifact.getExtension()).thenReturn("extension");
        when(mappings.getChannel()).thenReturn(namingChannelProperty);
        when(namingChannelProperty.get()).thenReturn(namingChannel);
        when(namingChannel.getDeobfuscationGroupSupplier()).thenReturn(deobfuscationGroupSupplier);
        when(namingChannel.getName()).thenReturn("naming_channel");
        when(deobfuscationGroupSupplier.get()).thenReturn("");

        when(dependency.getModuleArtifacts()).thenReturn(Collections.singleton(dependencyArtifact));
        when(dependencyArtifact.getFile()).thenReturn(dependentTarget.getFile());
        when(dependency.getChildren()).thenReturn(Collections.emptySet());
        when(dependency.getName()).thenReturn("Dependency");
        when(dependency.getModuleGroup()).thenReturn("dependent_group");
        when(dependency.getModuleName()).thenReturn("dependency");
        when(dependency.getModuleVersion()).thenReturn("some_classy_version");
        when(dependencyArtifact.getClassifier()).thenReturn("with_a_classifier");
        when(dependencyArtifact.getExtension()).thenReturn("and_an_extension");


        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        when(taskContainer.register(ArgumentMatchers.any(String.class), ArgumentMatchers.any(Class.class), ArgumentMatchers.any(Action.class))).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final Class<? extends Task> type = invocation.getArgument(1);
            final Action action = invocation.getArgument(2);
            final Task task = TaskMockingUtils.mockTask(type, project, name);
            tasks.add(task);
            action.execute(task);
            return TaskMockingUtils.mockTaskProvider(task);
        });

        createSimpleJar(dependentTarget);
        createValidDeobfuscatableJar(target);

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency externalModuleDependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(externalModuleDependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertTrue(result.isPresent());

        final DependencyReplacementResult replacementResult = result.get();
        assertEquals(project, replacementResult.getProject());

        assertNotNull(replacementResult.getTaskNameBuilder());
        assertEquals("testDummy", replacementResult.getTaskNameBuilder().apply("test"));

        assertNotNull(replacementResult.getSourcesJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getSourcesJarTaskProvider().get()));

        assertNotNull(replacementResult.getRawJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getRawJarTaskProvider().get()));

        assertNotNull(replacementResult.getAdditionalDependenciesConfiguration());
        assertTrue(replacementResult.getAdditionalDependenciesConfiguration().getDependencies().isEmpty());

        assertNotNull(replacementResult.getDependencyMetadataConfigurator());
        final DummyRepositoryEntry entry = new DummyRepositoryEntry(project);
        replacementResult.getDependencyMetadataConfigurator().accept(entry);
        assertEquals("fg.deobf.naming_channel.group", entry.getGroup());
        assertEquals("name", entry.getName());
        assertEquals("version", entry.getVersion());
        assertEquals("classifier", entry.getClassifier());
        assertEquals("extension", entry.getExtension());
        assertFalse(entry.getDependencies().isEmpty());
        assertEquals(1, entry.getDependencies().size());

        final DummyRepositoryDependency dependencyEntry = entry.getDependencies().iterator().next();
        assertEquals("dependent_group", dependencyEntry.getGroup());
        assertEquals("dependency", dependencyEntry.getName());
        assertEquals("some_classy_version", dependencyEntry.getVersion());
        assertEquals("with_a_classifier", dependencyEntry.getClassifier());
        assertEquals("and_an_extension", dependencyEntry.getExtension());

        assertNotNull(replacementResult.getAdditionalReplacements());
        assertTrue(replacementResult.getAdditionalReplacements().isEmpty());

        assertNotNull(replacementResult.getDependencyBuilderConfigurator());
        final DummyRepositoryDependency dependencyBuilder = new DummyRepositoryDependency(project);
        replacementResult.getDependencyBuilderConfigurator().accept(dependencyBuilder);
        assertEquals("fg.deobf.naming_channel.group", dependencyBuilder.getGroup());
        assertEquals("name", dependencyBuilder.getName());
        assertEquals("version", dependencyBuilder.getVersion());
        assertEquals("classifier", dependencyBuilder.getClassifier());
        assertEquals("extension", dependencyBuilder.getExtension());

        assertNotNull(replacementResult.getAdditionalIdePostSyncTasks());
        assertTrue(replacementResult.getAdditionalIdePostSyncTasks().isEmpty());
    }


    @Test
    public void weDeobfuscateADependencyWithASimpleJarWithObfuscatedDependencies() throws IOException {
        AtomicReference<DependencyReplacer> replacer = new AtomicReference<>();

        final Project project = mock(Project.class);
        final ExtensionContainer extensionContainer = mock(ExtensionContainer.class);
        final DependencyReplacement dependencyReplacement = mock(DependencyReplacement.class);
        final NamedDomainObjectContainer<DependencyReplacementHandler> handlers = mock();
        final ConfigurationContainer configurations = mock();
        final Configuration detachedConfiguration = mock();
        final ResolvedConfiguration resolvedConfiguration = mock();
        final LenientConfiguration lenientConfiguration = mock();
        final ResolvedDependency resolvedDependency = mock(ResolvedDependency.class);
        final ResolvedArtifact resolvedArtifact = mock(ResolvedArtifact.class);
        final TestFileTarget target = FileTestingUtils.newSimpleTestFileTarget("some.jar");
        final TaskContainer taskContainer = mock(TaskContainer.class);
        final List<Task> tasks = new ArrayList<>();
        final Mappings mappings = mock(Mappings.class);
        final Property<NamingChannel> namingChannelProperty = mock(Property.class);
        final NamingChannel namingChannel = mock(NamingChannel.class);
        final Property<String> deobfuscationGroupSupplier = mock(Property.class);
        final TestFileTarget dependentTarget = FileTestingUtils.newSimpleTestFileTarget("dependent.jar");
        final ResolvedDependency dependency = mock(ResolvedDependency.class);
        final ResolvedArtifact dependencyArtifact = mock(ResolvedArtifact.class);

        when(project.getExtensions()).thenReturn(extensionContainer);
        when(project.getTasks()).thenReturn(taskContainer);
        when(extensionContainer.getByType(DependencyReplacement.class)).thenReturn(dependencyReplacement);
        when(extensionContainer.getByType(Mappings.class)).thenReturn(mappings);
        when(dependencyReplacement.getReplacementHandlers()).thenReturn(handlers);
        when(project.getConfigurations()).thenReturn(configurations);
        when(configurations.detachedConfiguration(any()))
                .thenReturn(detachedConfiguration)
                .then((Answer<Configuration>) invocation -> {
                    final Dependency[] dependenciesIn = invocation.getArguments().length > 0 ? invocation.getArgument(0) : new Dependency[0];
                    final Configuration configuration = mock(Configuration.class);
                    final DependencySet dependencies = mock(DependencySet.class);
                    when(configuration.getDependencies()).thenReturn(dependencies);
                    when(configuration.getFiles()).thenThrow(new RuntimeException("Tried to resolve a detached configuration that was mocked for a situation where that should not happen."));
                    when(dependencies.isEmpty()).thenReturn(dependenciesIn.length == 0);
                    return configuration;
                });
        when(detachedConfiguration.getResolvedConfiguration()).thenReturn(resolvedConfiguration);
        when(resolvedConfiguration.getLenientConfiguration()).thenReturn(lenientConfiguration);
        when(lenientConfiguration.getFiles()).thenReturn(Collections.singleton(mock(File.class)));
        when(lenientConfiguration.getFirstLevelModuleDependencies()).thenReturn(Collections.singleton(resolvedDependency));
        when(resolvedDependency.getModuleArtifacts()).thenReturn(Collections.singleton(resolvedArtifact));
        when(resolvedArtifact.getFile()).thenReturn(target.getFile());
        when(resolvedDependency.getChildren()).thenReturn(Collections.singleton(dependency));
        when(resolvedDependency.getName()).thenReturn("Dummy");
        when(resolvedDependency.getModuleGroup()).thenReturn("group");
        when(resolvedDependency.getModuleName()).thenReturn("name");
        when(resolvedDependency.getModuleVersion()).thenReturn("version");
        when(resolvedArtifact.getClassifier()).thenReturn("classifier");
        when(resolvedArtifact.getExtension()).thenReturn("extension");
        when(mappings.getChannel()).thenReturn(namingChannelProperty);
        when(namingChannelProperty.get()).thenReturn(namingChannel);
        when(namingChannel.getDeobfuscationGroupSupplier()).thenReturn(deobfuscationGroupSupplier);
        when(namingChannel.getName()).thenReturn("naming_channel");
        when(deobfuscationGroupSupplier.get()).thenReturn("");

        when(dependency.getModuleArtifacts()).thenReturn(Collections.singleton(dependencyArtifact));
        when(dependencyArtifact.getFile()).thenReturn(dependentTarget.getFile());
        when(dependency.getChildren()).thenReturn(Collections.emptySet());
        when(dependency.getName()).thenReturn("Dependency");
        when(dependency.getModuleGroup()).thenReturn("dependent_group");
        when(dependency.getModuleName()).thenReturn("dependency");
        when(dependency.getModuleVersion()).thenReturn("some_classy_version");
        when(dependencyArtifact.getClassifier()).thenReturn("with_a_classifier");
        when(dependencyArtifact.getExtension()).thenReturn("and_an_extension");


        when(handlers.create(ArgumentMatchers.eq("obfuscatedDependencies"), any(Action.class)))
                .thenAnswer(invocation -> {
                    final Action<DependencyReplacementHandler> action = invocation.getArgument(1);
                    final DependencyReplacementHandler handler = mock(DependencyReplacementHandler.class);
                    final Property<DependencyReplacer> property = mock(Property.class);

                    doAnswer(invocation1 -> {
                        replacer.set(invocation1.getArgument(0));
                        return null;
                    }).when(property).set(ArgumentMatchers.<DependencyReplacer>any());
                    when(handler.getReplacer()).thenReturn(property);

                    action.execute(handler);
                    return handler;
                });

        when(taskContainer.register(ArgumentMatchers.any(String.class), ArgumentMatchers.any(Class.class), ArgumentMatchers.any(Action.class))).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final Class<? extends Task> type = invocation.getArgument(1);
            final Action action = invocation.getArgument(2);
            final Task task = TaskMockingUtils.mockTask(type, project, name);
            tasks.add(task);
            action.execute(task);
            return TaskMockingUtils.mockTaskProvider(task);
        });

        createValidDeobfuscatableJar(dependentTarget);
        createValidDeobfuscatableJar(target);

        DependencyDeobfuscator.getInstance().apply(project);

        final DependencyReplacer sut = replacer.get();

        //First validate that the was properly registered and retrieved
        assertNotNull(sut);

        final Context context = mock(Context.class);
        final ExternalModuleDependency externalModuleDependency = mock(ExternalModuleDependency.class);
        when(context.getDependency()).thenReturn(externalModuleDependency);
        when(context.getProject()).thenReturn(project);

        final Optional<DependencyReplacementResult> result = sut.get(context);
        assertNotNull(result);
        assertTrue(result.isPresent());

        final DependencyReplacementResult replacementResult = result.get();
        assertEquals(project, replacementResult.getProject());

        assertNotNull(replacementResult.getTaskNameBuilder());
        assertEquals("testDummy", replacementResult.getTaskNameBuilder().apply("test"));

        assertNotNull(replacementResult.getSourcesJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getSourcesJarTaskProvider().get()));

        assertNotNull(replacementResult.getRawJarTaskProvider());
        assertTrue(tasks.contains(replacementResult.getRawJarTaskProvider().get()));

        assertNotNull(replacementResult.getAdditionalDependenciesConfiguration());
        assertTrue(replacementResult.getAdditionalDependenciesConfiguration().getDependencies().isEmpty());

        assertNotNull(replacementResult.getDependencyMetadataConfigurator());
        final DummyRepositoryEntry entry = new DummyRepositoryEntry(project);
        replacementResult.getDependencyMetadataConfigurator().accept(entry);
        assertEquals("fg.deobf.naming_channel.group", entry.getGroup());
        assertEquals("name", entry.getName());
        assertEquals("version", entry.getVersion());
        assertEquals("classifier", entry.getClassifier());
        assertEquals("extension", entry.getExtension());
        assertFalse(entry.getDependencies().isEmpty());
        assertEquals(1, entry.getDependencies().size());

        final DummyRepositoryDependency dependencyEntry = entry.getDependencies().iterator().next();
        assertEquals("fg.deobf.naming_channel.dependent_group", dependencyEntry.getGroup());
        assertEquals("dependency", dependencyEntry.getName());
        assertEquals("some_classy_version", dependencyEntry.getVersion());
        assertEquals("with_a_classifier", dependencyEntry.getClassifier());
        assertEquals("and_an_extension", dependencyEntry.getExtension());

        assertNotNull(replacementResult.getAdditionalReplacements());
        assertFalse(replacementResult.getAdditionalReplacements().isEmpty());
        assertEquals(1, replacementResult.getAdditionalReplacements().size());
        DependencyReplacementResult additionalReplacement = replacementResult.getAdditionalReplacements().iterator().next();
        assertEquals(project, additionalReplacement.getProject());

        assertNotNull(additionalReplacement.getTaskNameBuilder());
        assertEquals("testDependency", additionalReplacement.getTaskNameBuilder().apply("test"));

        assertNotNull(additionalReplacement.getSourcesJarTaskProvider());
        assertTrue(tasks.contains(additionalReplacement.getSourcesJarTaskProvider().get()));

        assertNotNull(additionalReplacement.getRawJarTaskProvider());
        assertTrue(tasks.contains(additionalReplacement.getRawJarTaskProvider().get()));

        assertNotNull(additionalReplacement.getAdditionalDependenciesConfiguration());
        assertTrue(additionalReplacement.getAdditionalDependenciesConfiguration().getDependencies().isEmpty());

        assertNotNull(additionalReplacement.getDependencyMetadataConfigurator());
        final DummyRepositoryEntry dependencyRawEntry = new DummyRepositoryEntry(project);
        additionalReplacement.getDependencyMetadataConfigurator().accept(dependencyRawEntry);
        assertEquals("fg.deobf.naming_channel.dependent_group", dependencyRawEntry.getGroup());
        assertEquals("dependency", dependencyRawEntry.getName());
        assertEquals("some_classy_version", dependencyRawEntry.getVersion());
        assertEquals("with_a_classifier", dependencyRawEntry.getClassifier());
        assertEquals("and_an_extension", dependencyRawEntry.getExtension());
        assertTrue(dependencyRawEntry.getDependencies().isEmpty());

        assertNotNull(additionalReplacement.getDependencyBuilderConfigurator());
        final DummyRepositoryDependency dependencyEntryBuilder = new DummyRepositoryDependency(project);
        additionalReplacement.getDependencyBuilderConfigurator().accept(dependencyEntryBuilder);
        assertEquals("fg.deobf.naming_channel.dependent_group", dependencyEntryBuilder.getGroup());
        assertEquals("dependency", dependencyEntryBuilder.getName());
        assertEquals("some_classy_version", dependencyEntryBuilder.getVersion());
        assertEquals("with_a_classifier", dependencyEntryBuilder.getClassifier());
        assertEquals("and_an_extension", dependencyEntryBuilder.getExtension());

        assertNotNull(additionalReplacement.getAdditionalIdePostSyncTasks());
        assertTrue(additionalReplacement.getAdditionalIdePostSyncTasks().isEmpty());

        assertNotNull(replacementResult.getDependencyBuilderConfigurator());
        final DummyRepositoryDependency dependencyBuilder = new DummyRepositoryDependency(project);
        replacementResult.getDependencyBuilderConfigurator().accept(dependencyBuilder);
        assertEquals("fg.deobf.naming_channel.group", dependencyBuilder.getGroup());
        assertEquals("name", dependencyBuilder.getName());
        assertEquals("version", dependencyBuilder.getVersion());
        assertEquals("classifier", dependencyBuilder.getClassifier());
        assertEquals("extension", dependencyBuilder.getExtension());

        assertNotNull(replacementResult.getAdditionalIdePostSyncTasks());
        assertTrue(replacementResult.getAdditionalIdePostSyncTasks().isEmpty());
    }

    private static void createSimpleJar(TestFileTarget target) throws IOException {
        final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target.getPath()));
        final ZipEntry manifest = new ZipEntry("META-INF/MANIFEST.MF");
        zip.putNextEntry(manifest);
        zip.write("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.flush();
        zip.close();
    }

    private static void createValidDeobfuscatableJar(TestFileTarget target) throws IOException {
        final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target.getPath()));
        final ZipEntry manifest = new ZipEntry("META-INF/MANIFEST.MF");
        zip.putNextEntry(manifest);
        zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        zip.write("Obfuscated: true\n".getBytes(StandardCharsets.UTF_8));
        zip.write("Obfuscated-By: ForgeGradle\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.flush();
        zip.close();
    }

    private final class DummyRepositoryEntry implements RepositoryEntry<DummyRepositoryEntry, DummyRepositoryDependency>, RepositoryEntry.Builder<DummyRepositoryEntry, DummyRepositoryDependency, DummyRepositoryDependency> {
        private Project project;
        private String group;
        private String name;
        private String version;
        private String classifier;
        private String extension;
        private Set<DummyRepositoryDependency> dependencies = new HashSet<>();

        public DummyRepositoryEntry(Project project) {
            this.project = project;
        }

        public DummyRepositoryEntry(Project project, String group, String name, String version, String classifier, String extension, Set<DummyRepositoryDependency> dependencies) {
            this.project = project;
            this.group = group;
            this.name = name;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
            this.dependencies = new HashSet<>(dependencies);
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public boolean matches(ModuleComponentIdentifier id) {
            return getFullGroup().equals(id.getGroup()) &&
                    getName().equals(id.getModule()) &&
                    getVersion().equals(id.getVersion());
        }

        @NotNull
        @Override
        public Dependency toGradle(Project project) {
            final ModuleDependency moduleDependency = mock(ModuleDependency.class);
            final DependencyArtifact artifact = mock(DependencyArtifact.class);
            when(moduleDependency.getGroup()).thenReturn(getFullGroup());
            when(moduleDependency.getName()).thenReturn(getName());
            when(moduleDependency.getVersion()).thenReturn(getVersion());
            when(moduleDependency.getArtifacts()).thenReturn(Collections.singleton(artifact));
            when(artifact.getName()).thenReturn(getName());
            when(artifact.getClassifier()).thenReturn(getClassifier());
            when(artifact.getExtension()).thenReturn(getExtension());
            return moduleDependency;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry asSources() {
            return but().setClassifier("sources");
        }

        @NotNull
        @Override
        public Path buildArtifactPath(Path baseDir) throws IOException {
            final Path artifactPath = baseDir.resolve(buildArtifactPath());
            Files.createDirectories(artifactPath.getParent());
            return artifactPath;
        }

        @NotNull
        @Override
        public String buildArtifactPath() {
            final String fileName = getClassifier() == null || getClassifier().equals("") ?
                    String.format("%s-%s.%s", getName(), getVersion(), getExtension()) :
                    String.format("%s-%s-%s.%s", getName(), getVersion(), getClassifier(), getExtension());

            final String groupPath = getFullGroup().replace('.', '/') + '/';

            return String.format("%s%s/%s/%s", groupPath, getName(), getVersion(), fileName);
        }

        @NotNull
        @Override
        public String getFullGroup() {
            if (getGroup() == null) {
                return "dummy";
            }

            return String.format("%s.%s", "dummy", getGroup());
        }

        @NotNull
        @Override
        public String getGroup() {
            return group;
        }

        @NotNull
        @Override
        public String getName() {
            return name;
        }

        @NotNull
        @Override
        public String getVersion() {
            return version;
        }

        @Nullable
        @Override
        public String getClassifier() {
            return classifier;
        }

        @Nullable
        @Override
        public String getExtension() {
            return extension;
        }

        @NotNull
        @Override
        public ImmutableSet<DummyRepositoryDependency> getDependencies() {
            return ImmutableSet.copyOf(dependencies);
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setGroup(@NotNull String group) {
            this.group = group;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setName(@NotNull String name) {
            this.name = name;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setVersion(@NotNull String version) {
            this.version = version;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setClassifier(@Nullable String classifier) {
            this.classifier = classifier;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setExtension(@Nullable String extension) {
            this.extension = extension;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry from(@NotNull ModuleDependency dependency) {
            setGroup(dependency.getGroup());
            setName(dependency.getName());
            setVersion(dependency.getVersion());
            setClassifier(ModuleDependencyUtils.getClassifier(dependency));
            setExtension(ModuleDependencyUtils.getExtension(dependency));
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry from(@NotNull ResolvedDependency resolvedDependency) {
            setGroup(resolvedDependency.getModuleGroup());
            setName(resolvedDependency.getModuleName());
            setVersion(resolvedDependency.getModuleVersion());
            setClassifier(ResolvedDependencyUtils.getClassifier(resolvedDependency));
            setExtension(ResolvedDependencyUtils.getExtension(resolvedDependency));
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setDependencies(@NotNull Collection<DummyRepositoryDependency> dummyRepositoryDependencies) {
            this.dependencies = new HashSet<>(dummyRepositoryDependencies);
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry setDependencies(@NotNull DummyRepositoryDependency... dummyRepositoryDependencies) {
            this.dependencies = new HashSet<>(Arrays.asList(dummyRepositoryDependencies));
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry withDependency(@NotNull Consumer<DummyRepositoryDependency> consumer) {
            final DummyRepositoryDependency dependency = new DummyRepositoryDependency(project);
            consumer.accept(dependency);
            this.dependencies.add(dependency);
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryEntry but() {
            return new DummyRepositoryEntry(project, group, name, version, classifier, extension, dependencies);
        }

        @Override
        public ExtensionContainer getExtensions() {
            return mock(ExtensionContainer.class);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private final class DummyRepositoryDependency implements RepositoryReference, RepositoryReference.Builder<DummyRepositoryDependency, DummyRepositoryDependency> {

        private Project project;
        private String group;
        private String name;
        private String version;
        private String classifier;
        private String extension;

        public DummyRepositoryDependency(Project project) {
            this.project = project;
        }

        public DummyRepositoryDependency(Project project, String group, String name, String version, String classifier, String extension) {
            this.project = project;
            this.group = group;
            this.name = name;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
        }

        @Override
        public Project getProject() {
            return project;
        }

        @NotNull
        @Override
        public String getGroup() {
            return group;
        }

        @NotNull
        @Override
        public String getName() {
            return name;
        }

        @NotNull
        @Override
        public String getVersion() {
            return version;
        }

        @Nullable
        @Override
        public String getClassifier() {
            return classifier;
        }

        @Nullable
        @Override
        public String getExtension() {
            return extension;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency setGroup(@NotNull String group) {
            this.group = group;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency setName(@NotNull String name) {
            this.name = name;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency setVersion(@NotNull String version) {
            this.version = version;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency setClassifier(@Nullable String classifier) {
            this.classifier = classifier;
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency setExtension(@Nullable String extension) {
            this.extension = extension;
            return this;
        }

        @Override
        public DummyRepositoryDependency from(ModuleDependency externalModuleDependency) {
            setGroup(externalModuleDependency.getGroup());
            setName(externalModuleDependency.getName());
            setVersion(externalModuleDependency.getVersion());

            if (!externalModuleDependency.getArtifacts().isEmpty()) {
                final DependencyArtifact artifact = externalModuleDependency.getArtifacts().iterator().next();
                setClassifier(artifact.getClassifier());
                setExtension(artifact.getExtension());
            }
            return this;
        }

        @Override
        public DummyRepositoryDependency from(ResolvedDependency externalModuleDependency) {
            setGroup(externalModuleDependency.getModuleGroup());
            setName(externalModuleDependency.getModuleName());
            setVersion(externalModuleDependency.getModuleVersion());
            setExtension(ResolvedDependencyUtils.getExtension(externalModuleDependency));
            setClassifier(ResolvedDependencyUtils.getClassifier(externalModuleDependency));
            return this;
        }

        @NotNull
        @Override
        public DummyRepositoryDependency but() {
            return new DummyRepositoryDependency(project, group, name, version, classifier, extension);
        }

        @Override
        public ExtensionContainer getExtensions() {
            return mock(ExtensionContainer.class);
        }
    }

}