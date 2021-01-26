/*
 * This file is part of VanillaGradle, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.vanilla.gradle;

import org.gradle.api.PathValidation;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.spongepowered.vanilla.gradle.model.Version;
import org.spongepowered.vanilla.gradle.model.VersionDescriptor;
import org.spongepowered.vanilla.gradle.model.VersionManifestV2;
import org.spongepowered.vanilla.gradle.task.AccessWidenJarTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import javax.inject.Inject;

public abstract class MinecraftExtension {

    private final Project project;
    private final Property<String> version;
    private final Property<MinecraftPlatform> platform;

    private VersionManifestV2 versionManifest;
    private VersionDescriptor versionDescriptor;
    private Version targetVersion;

    private final DirectoryProperty librariesDirectory;
    private final DirectoryProperty minecraftLibrariesDirectory;
    private final DirectoryProperty mappingsDirectory;
    private final DirectoryProperty remapDirectory;

    @Inject
    public MinecraftExtension(final Gradle gradle, final ObjectFactory factory, final Project project) {
        this.project = project;
        this.version = factory.property(String.class);
        this.platform = factory.property(MinecraftPlatform.class).convention(MinecraftPlatform.SERVER);
        this.librariesDirectory = factory.directoryProperty();
        this.minecraftLibrariesDirectory = factory.directoryProperty();
        this.mappingsDirectory = factory.directoryProperty();
        this.remapDirectory = factory.directoryProperty();

        final Path gradleHomeDirectory = gradle.getGradleUserHomeDir().toPath();
        final Path cacheDirectory = gradleHomeDirectory.resolve(Constants.CACHES);
        final Path rootDirectory = cacheDirectory.resolve(Constants.NAME);
        final Path librariesDirectory = rootDirectory.resolve(Constants.LIBRARIES);
        this.librariesDirectory.set(librariesDirectory.toFile());
        this.minecraftLibrariesDirectory.set(librariesDirectory.resolve("net").resolve("minecraft").toFile());
        this.mappingsDirectory.set(rootDirectory.resolve(Constants.MAPPINGS).toFile());
        this.remapDirectory.set(rootDirectory.resolve(Constants.REMAP).toFile());

        try {
            this.versionManifest = VersionManifestV2.load();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void version(final String version) {
        this.version.set(version);
    }

    protected Property<MinecraftPlatform> platform() {
        return this.platform;
    }

    public void platform(final MinecraftPlatform platform) {
        this.platform.set(platform);
    }

    /**
     * Apply an access widener to the project.
     *
     * @param file any file that can be passed to {@link Project#file(Object)}
     */
    public void accessWidener(final Object file) {
        final TaskProvider<AccessWidenJarTask> awTask;
        final TaskContainer tasks = this.project.getTasks();
        if (tasks.getNames().contains(Constants.ACCESS_WIDENER_TASK_NAME)) {
            awTask = tasks.named(Constants.ACCESS_WIDENER_TASK_NAME, AccessWidenJarTask.class);
        } else {
            awTask = tasks.register(Constants.ACCESS_WIDENER_TASK_NAME, AccessWidenJarTask.class, task -> {
                task.getExpectedNamespace().set("named");
                task.getDestinationDirectory().set(this.project.getLayout().getProjectDirectory().dir(".gradle").dir(Constants.NAME).dir("aw-minecraft"));
            });
        }

        awTask.configure(task -> {
            task.getAccessWideners().from(file);
        });
    }

    protected DirectoryProperty minecraftLibrariesDirectory() {
        return this.minecraftLibrariesDirectory;
    }

    protected DirectoryProperty mappingsDirectory() {
        return this.mappingsDirectory;
    }

    protected DirectoryProperty remapDirectory() {
        return this.remapDirectory;
    }

    protected VersionManifestV2 versionManifest() {
        return this.versionManifest;
    }

    protected VersionDescriptor versionDescriptor() {
        return this.versionDescriptor;
    }

    protected Version targetVersion() {
        return this.targetVersion;
    }

    protected void determineVersion() {
        this.versionDescriptor = this.versionManifest.findDescriptor(this.version.get()).orElseThrow(() -> new RuntimeException(String.format("No version "
                + "found for '%s' in the manifest!", this.version.get())));
    }

    protected void downloadManifest() {
        try {
            this.targetVersion = this.versionDescriptor.toVersion();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void createMinecraftClasspath(final Project project) {
        final Configuration minecraftClasspath = project.getConfigurations().create("minecraftClasspath");
        minecraftClasspath.withDependencies(a -> {
            for (final MinecraftSide side : this.platform.get().activeSides()) {
                side.applyLibraries(
                    name -> a.add(project.getDependencies().create(name.group() + ':' + name.artifact() + ':' + name.version())),
                    this.targetVersion.libraries()
                );
            }
        });

        project.getConfigurations().named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME).configure(path -> path.extendsFrom(minecraftClasspath));
        project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).configure(path -> path.extendsFrom(minecraftClasspath));
    }
}
