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
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public class SyncProjectHyperlink extends NotificationHyperlink {
  public SyncProjectHyperlink() {
    super("syncProjectWithGradle", "Sync Project with Gradle files");
  }

  @Override
  protected void execute(@NotNull Project project) {
    try {
      GradleProjectImporter.getInstance().reImportProject(project, null);
    }
    catch (ConfigurationException e) {
      Messages.showErrorDialog(project, e.getMessage(), e.getTitle());
    }
  }
}
