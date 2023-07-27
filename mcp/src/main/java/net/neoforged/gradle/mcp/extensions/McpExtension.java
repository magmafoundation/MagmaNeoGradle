/*
 * ForgeGradle
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.neoforged.gradle.mcp.extensions;

import net.minecraftforge.gdi.ConfigurableDSLElement;
import net.neoforged.gradle.dsl.common.util.Artifact;
import net.neoforged.gradle.dsl.mcp.extensions.Mcp;
import net.neoforged.gradle.mcp.runtime.extensions.McpRuntimeExtension;
import org.gradle.api.Project;

import javax.inject.Inject;

public abstract class McpExtension implements Mcp, ConfigurableDSLElement<Mcp> {

    protected final Project project;

    @Inject
    public McpExtension(final Project project) {
        this.project = project;

        getMcpConfigArtifact().convention(
                getMcpConfigVersion().map(version -> Artifact.from("de.oceanlabs.mcp", "mcp_config", version, null, "zip"))
        );
    }

    @Override
    public Project getProject() {
        return project;
    }

    public McpRuntimeExtension getRuntime() {
        return project.getExtensions().getByType(McpRuntimeExtension.class);
    }
}