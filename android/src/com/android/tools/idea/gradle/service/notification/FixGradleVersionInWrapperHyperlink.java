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
package com.android.tools.idea.gradle.service.notification;

import com.android.tools.idea.gradle.project.GradleProjectImporter;
import com.android.tools.idea.gradle.util.GradleUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.settings.DistributionType;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;

import java.io.File;
import java.io.IOException;

/**
 * Fixes the Gradle version in a project's Gradle wrapper.
 */
class FixGradleVersionInWrapperHyperlink extends NotificationHyperlink {
  @NotNull private final File myWrapperPropertiesFile;

  @Nullable
  static NotificationHyperlink createIfProjectUsesGradleWrapper(@NotNull Project project) {
    if (!isUsingWrapper(project)) {
      // No point in fixing wrapper if project does not use it. Project import will fail again.
      return null;
    }

    File wrapperPropertiesFile = findWrapperPropertiesFile(project);
    if (wrapperPropertiesFile != null) {
      return new FixGradleVersionInWrapperHyperlink(wrapperPropertiesFile);
    }

    return null;
  }

  private static boolean isUsingWrapper(@NotNull Project project) {
    GradleSettings gradleSettings = GradleSettings.getInstance(project);
    for (GradleProjectSettings projectSettings : gradleSettings.getLinkedProjectsSettings()) {
      if (projectSettings != null) {
        DistributionType distributionType = projectSettings.getDistributionType();
        if (DistributionType.WRAPPED.equals(distributionType) || DistributionType.DEFAULT_WRAPPED.equals(distributionType)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static File findWrapperPropertiesFile(@NotNull Project project) {
    File baseDir = new File(project.getBasePath());
    File wrapperPropertiesFile = GradleUtil.getGradleWrapperPropertiesFilePath(baseDir);
    return wrapperPropertiesFile.isFile() ? wrapperPropertiesFile : null;
  }

  private FixGradleVersionInWrapperHyperlink(@NotNull File wrapperPropertiesFile) {
    super("fixGradleVersionInWrapper", "Fix Gradle wrapper and re-import project");
    myWrapperPropertiesFile = wrapperPropertiesFile;
  }

  @Override
  protected void execute(@NotNull Project project) {
    String gradleVersion = GradleUtil.GRADLE_MINIMUM_VERSION;
    try {
      GradleUtil.updateGradleDistributionUrl(gradleVersion, myWrapperPropertiesFile);
      try {
        GradleProjectImporter.getInstance().reImportProject(project, null);
      }
      catch (ConfigurationException e) {
        Messages.showErrorDialog(e.getMessage(), e.getTitle());
        Logger.getInstance(FixGradleVersionInWrapperHyperlink.class).info(e);
      }
    }
    catch (IOException e) {
      String msg = String.format("Unable to update Gradle wrapper to use Gradle %1$s\n", gradleVersion);
      msg += e.getMessage();
      Messages.showErrorDialog(project, msg, "Quick Fix Failed");
    }
  }
}
