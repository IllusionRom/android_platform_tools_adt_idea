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

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.intellij.compiler.CompilerWorkspaceConfiguration;
import com.intellij.ide.PowerSaveMode;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.RawCommandLineEditor;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.settings.GradleSettings;

import javax.swing.*;

/**
 * Configuration page for Gradle compiler settings.
 */
public class GradleCompilerSettingsConfigurable implements SearchableConfigurable, Configurable.NoScroll {
  private final CompilerWorkspaceConfiguration myCompilerConfiguration;
  private final AndroidGradleBuildConfiguration myBuildConfiguration;
  private final GradleSettings myGradleSettings;

  private JCheckBox myParallelBuildCheckBox;
  private HyperlinkLabel myParallelBuildDocHyperlinkLabel;
  private RawCommandLineEditor myVmOptionsEditor;
  private JCheckBox myAutoMakeCheckBox;
  private JCheckBox myUseExperimentalBuildCheckBox;
  private JPanel myContentPanel;
  private JCheckBox myOfflineModeCheckBox;

  public GradleCompilerSettingsConfigurable(@NotNull Project project) {
    myCompilerConfiguration = CompilerWorkspaceConfiguration.getInstance(project);
    myBuildConfiguration = AndroidGradleBuildConfiguration.getInstance(project);
    myGradleSettings = GradleSettings.getInstance(project);
  }

  @Override
  @NotNull
  public String getId() {
    return "gradle.compiler";
  }

  @Override
  @Nullable
  public Runnable enableSearch(String option) {
    return null;
  }

  @Override
  @Nls
  public String getDisplayName() {
    return "Gradle";
  }

  @Override
  @Nullable
  public String getHelpTopic() {
    return null;
  }

  @Override
  @Nullable
  public JComponent createComponent() {
    return myContentPanel;
  }

  @Override
  public boolean isModified() {
    return myCompilerConfiguration.PARALLEL_COMPILATION != isParallelBuildsEnabled() ||
           !Objects.equal(getVmOptions(), myGradleSettings.getGradleVmOptions()) ||
           myCompilerConfiguration.MAKE_PROJECT_ON_SAVE != isAutoMakeEnabled() ||
           myBuildConfiguration.USE_EXPERIMENTAL_FASTER_BUILD != isExperimentalBuildEnabled() ||
           myBuildConfiguration.OFFLINE_MODE != isOfflineModeEnabled();
  }

  @Override
  public void apply() {
    myCompilerConfiguration.PARALLEL_COMPILATION = isParallelBuildsEnabled();
    myGradleSettings.setGradleVmOptions(getVmOptions());
    myCompilerConfiguration.MAKE_PROJECT_ON_SAVE = isAutoMakeEnabled();
    myBuildConfiguration.USE_EXPERIMENTAL_FASTER_BUILD = isExperimentalBuildEnabled();
    myBuildConfiguration.OFFLINE_MODE = isOfflineModeEnabled();
  }

  private boolean isParallelBuildsEnabled() {
    return myParallelBuildCheckBox.isSelected();
  }

  private boolean isAutoMakeEnabled() {
    return myAutoMakeCheckBox.isSelected();
  }

  private boolean isExperimentalBuildEnabled() {
    return myUseExperimentalBuildCheckBox.isSelected();
  }

  private boolean isOfflineModeEnabled() {
    return myOfflineModeCheckBox.isSelected();
  }

  @Nullable
  private String getVmOptions() {
    return Strings.emptyToNull(myVmOptionsEditor.getText().trim());
  }

  @Override
  public void reset() {
    myParallelBuildCheckBox.setSelected(myCompilerConfiguration.PARALLEL_COMPILATION);
    String vmOptions = Strings.nullToEmpty(myGradleSettings.getGradleVmOptions());
    myVmOptionsEditor.setText(vmOptions);
    myAutoMakeCheckBox.setSelected(myCompilerConfiguration.MAKE_PROJECT_ON_SAVE);
    myUseExperimentalBuildCheckBox.setSelected(myBuildConfiguration.USE_EXPERIMENTAL_FASTER_BUILD);
    myAutoMakeCheckBox.setText("Make project automatically (only works while not running / debugging" +
                               (PowerSaveMode.isEnabled() ? ", disabled in Power Save mode" : "") +
                               ")");
    myOfflineModeCheckBox.setSelected(myBuildConfiguration.OFFLINE_MODE);
  }

  @Override
  public void disposeUIResources() {
  }

  private void createUIComponents() {
    myParallelBuildDocHyperlinkLabel = new HyperlinkLabel();
    myParallelBuildDocHyperlinkLabel
      .setHyperlinkText("This option is in \"incubation\" and should only be used with ", "decoupled projects", ".");
    myParallelBuildDocHyperlinkLabel
      .setHyperlinkTarget("http://www.gradle.org/docs/current/userguide/multi_project_builds.html#sec:decoupled_projects");

    myVmOptionsEditor = new RawCommandLineEditor();
    myVmOptionsEditor.setDialogCaption("Gradle VM Options");
  }
}
