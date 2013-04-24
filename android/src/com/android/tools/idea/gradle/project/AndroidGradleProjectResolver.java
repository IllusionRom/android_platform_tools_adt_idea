/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.gradle.project;

import com.android.build.gradle.internal.tasks.BaseTask;
import com.android.build.gradle.model.AndroidProject;
import com.android.builder.model.ProductFlavor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.intellij.execution.configurations.SimpleJavaParameters;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.gradle.tooling.ProjectConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelper;
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;

import java.util.List;

/**
 * Imports Android-Gradle projects into IDEA.
 */
public class AndroidGradleProjectResolver implements GradleProjectResolverExtension {
  @NotNull private final GradleExecutionHelper myHelper;
  @NotNull private final ProjectResolverFunctionFactory myFunctionFactory;

  // This constructor is called by the IDE. See this module's plugin.xml file, implementation of extension 'projectResolve'.
  public AndroidGradleProjectResolver() {
    myHelper = new GradleExecutionHelper();
    myFunctionFactory =
      new ProjectResolverFunctionFactory(new ProjectResolverStrategy(myHelper), new MultiProjectResolverStrategy(myHelper));
  }

  @VisibleForTesting
  AndroidGradleProjectResolver(@NotNull GradleExecutionHelper helper, @NotNull ProjectResolverFunctionFactory functionFactory) {
    myHelper = helper;
    myFunctionFactory = functionFactory;
  }

  /**
   * Imports an Android-Gradle project into IDEA.
   *
   * </p>Two types of projects are supported:
   * <ol>
   *   <li>A single {@link AndroidProject}</li>
   *   <li>A multi-project has at least one {@link AndroidProject} child</li>
   * </ol>
   *
   * @param id                id of the current 'resolve project info' task.
   * @param projectPath       absolute path of the build.gradle file. It includes the file name.
   * @param downloadLibraries a hint that specifies if third-party libraries that are not available locally should be resolved (downloaded.)
   * @param settings          settings to use for the project resolving; {@code null} as indication that no specific settings are required.
   * @return the imported project, or {@code null} if the project to import is not supported.
   */
  @Nullable
  @Override
  public DataNode<ProjectData> resolveProjectInfo(@NotNull ExternalSystemTaskId id,
                                                  @NotNull String projectPath,
                                                  boolean downloadLibraries,
                                                  @Nullable GradleExecutionSettings settings) {
    Function<ProjectConnection, DataNode<ProjectData>> function = myFunctionFactory.createFunction(id, projectPath, settings);
    return myHelper.execute(projectPath, settings, function);
  }

  /**
   * Adds the paths of the 'android' module and jar files of the Android-Gradle project to the classpath of the slave process that performs
   * the Gradle project import.
   *
   * @param parameters parameters to be applied to the slave process which will be used for external system communication.
   */
  @Override
  public void enhanceParameters(@NotNull SimpleJavaParameters parameters) {
    List<String> jarPaths = getJarPathsOf(getClass(), AndroidProject.class, BaseTask.class, ProductFlavor.class);
    for (String jarPath : jarPaths) {
      parameters.getClassPath().add(jarPath);
    }
  }

  @NotNull
  private static List<String> getJarPathsOf(@NotNull Class<?>... types) {
    List<String> jarPaths = Lists.newArrayList();
    for (Class<?> type : types) {
      ContainerUtil.addIfNotNull(PathUtil.getJarPathForClass(type), jarPaths);
    }
    return jarPaths;
  }

  static class ProjectResolverFunctionFactory {
    @NotNull private final List<ProjectResolverStrategy> myStrategies;

    ProjectResolverFunctionFactory(@NotNull ProjectResolverStrategy... strategies) {
      myStrategies = ImmutableList.copyOf(strategies);
    }

    @NotNull
    Function<ProjectConnection, DataNode<ProjectData>> createFunction(@NotNull final ExternalSystemTaskId id,
                                                                      @NotNull final String projectPath,
                                                                      @Nullable final GradleExecutionSettings settings) {
      return new Function<ProjectConnection, DataNode<ProjectData>>() {
        @Nullable
        @Override
        public DataNode<ProjectData> fun(ProjectConnection connection) {
          for (ProjectResolverStrategy strategy : myStrategies) {
            DataNode<ProjectData> projectInfo = strategy.resolveProjectInfo(id, projectPath, settings, connection);
            if (projectInfo != null) {
              return projectInfo;
            }
          }
          return null;
        }
      };
    }
  }
}