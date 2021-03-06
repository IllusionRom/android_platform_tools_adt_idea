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
package com.android.tools.idea.gradle.compiler;

import com.android.tools.idea.gradle.util.BuildMode;
import com.android.tools.idea.gradle.util.Projects;
import com.intellij.compiler.impl.BuildTargetScopeProvider;
import com.intellij.compiler.impl.CompositeScope;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.compiler.impl.ProjectCompileScope;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerFilter;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.api.CmdlineProtoUtil;
import org.jetbrains.jps.api.CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope;

import java.util.Collections;
import java.util.List;

/**
 * Instructs the JPS builder to use Gradle to build the project.
 */
public class AndroidGradleBuildTargetScopeProvider extends BuildTargetScopeProvider {
  public static final String TARGET_ID = "android_gradle_build_target";
  public static final String TARGET_TYPE_ID = "android_gradle_build_target_type";

  @Override
  @NotNull
  public List<TargetTypeBuildScope> getBuildTargetScopes(@NotNull CompileScope baseScope,
                                                         @NotNull CompilerFilter filter,
                                                         @NotNull Project project,
                                                         boolean forceBuild) {
    if (!Projects.isGradleProject(project)) {
      return Collections.emptyList();
    }
    if (baseScope instanceof ProjectCompileScope) {
      // Make or Rebuild project
      BuildMode buildMode = forceBuild ? BuildMode.REBUILD : BuildMode.MAKE;
      if (Projects.getBuildModeFrom(project) == null) {
        Projects.setProjectBuildMode(project, buildMode);
      }
    }
    else if (baseScope instanceof ModuleCompileScope) {
      String userDataString = ((ModuleCompileScope)baseScope).getUserDataString();
      Module[] modulesToBuild;
      if (userDataString.contains("RUN_CONFIGURATION")) {
        // Triggered by a "Run Configuration"
        modulesToBuild = baseScope.getAffectedModules();
      }
      else {
        // Triggered by menu item
        // Make selected modules
        modulesToBuild = Projects.getModulesToBuildFromSelection(project, null);
      }
      Projects.setModulesToBuild(project, modulesToBuild);
      Projects.setProjectBuildMode(project, BuildMode.MAKE);
    }
    else if (baseScope instanceof CompositeScope) {
      // Compile selected modules
      Projects.setModulesToBuild(project, Projects.getModulesToBuildFromSelection(project, null));
      Projects.setProjectBuildMode(project, BuildMode.COMPILE_JAVA);
    }

    TargetTypeBuildScope scope = CmdlineProtoUtil.createTargetsScope(TARGET_TYPE_ID, Collections.singletonList(TARGET_ID), forceBuild);
    return Collections.singletonList(scope);
  }
}
