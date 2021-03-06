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
package com.android.tools.idea.wizard;

import com.android.tools.idea.templates.TemplateManager;
import com.android.tools.idea.templates.TemplateMetadata;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBList;
import com.intellij.util.ArrayUtil;
import icons.AndroidIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.android.tools.idea.templates.TemplateMetadata.*;

/**
 * ChooseTemplateStep is a wizard page that shows the user a list of templates of a given type and lets the user choose one.
 */
public class ChooseTemplateStep extends TemplateWizardStep implements ListSelectionListener {
  private static final Logger LOG = Logger.getInstance("#" + ChooseTemplateStep.class.getName());
  private final TemplateChangeListener myTemplateChangeListener;

  private JPanel myPanel;
  protected JBList myTemplateList;
  private ImageComponent myTemplateImage;
  private JLabel myDescription;
  private JLabel myError;
  private int myPreviousSelection = -1;

  public interface TemplateChangeListener {
    void templateChanged(String templateName);
  }

  public ChooseTemplateStep(TemplateWizardState state, String templateCategory, @Nullable Project project, @Nullable Icon sidePanelIcon,
                            UpdateListener updateListener, @Nullable TemplateChangeListener templateChangeListener) {
    this(state, templateCategory, project, sidePanelIcon, updateListener, templateChangeListener, null);
  }

  public ChooseTemplateStep(TemplateWizardState state, String templateCategory, @Nullable Project project, @Nullable Icon sidePanelIcon,
                            UpdateListener updateListener, @Nullable TemplateChangeListener templateChangeListener,
                            @Nullable Set<String> excluded) {
    super(state, project, sidePanelIcon, updateListener);
    myTemplateChangeListener = templateChangeListener;

    if (templateCategory != null) {
      List<MetadataListItem> templates = getTemplateList(state, templateCategory, excluded);
      setListData(templates);
      validate();
    }
  }


  /**
   * Search the given folder for a list of templates and populate the display list.
   */
  protected static List<MetadataListItem> getTemplateList(TemplateWizardState state, String templateFolder, @Nullable Set<String> excluded) {
    TemplateManager manager = TemplateManager.getInstance();
    List<File> templates = manager.getTemplates(templateFolder);
    List<MetadataListItem> metadataList = new ArrayList<MetadataListItem>(templates.size());
    for (File template : templates) {
      TemplateMetadata metadata = manager.getTemplate(template);
      if (metadata == null || !metadata.isSupported()) {
        continue;
      }
      // If we're trying to create a launchable activity, don't include templates that
      // lack the isLauncher parameter.
      Boolean isLauncher = (Boolean)state.get(ATTR_IS_LAUNCHER);
      if (isLauncher != null && isLauncher && metadata.getParameter(TemplateMetadata.ATTR_IS_LAUNCHER) == null) {
        continue;
      }

      // Don't include this template if it's been excluded
      if (excluded != null && excluded.contains(metadata.getTitle())) {
        continue;
      }

      metadataList.add(new MetadataListItem(template, metadata));
    }

    return metadataList;
  }

  /**
   * Populate the JBList of templates from the given list of metadata.
   */
  protected void setListData(List<MetadataListItem> metadataList) {
    myTemplateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    myTemplateList.setModel(JBList.createDefaultListModel(ArrayUtil.toObjectArray(metadataList)));
    if (!metadataList.isEmpty()) {
      myTemplateList.setSelectedIndex(0);
    }
    myTemplateList.addListSelectionListener(this);
  }

  @Override
  public JComponent getComponent() {
    return myPanel;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myTemplateList;
  }

  @Override
  public void valueChanged(ListSelectionEvent listSelectionEvent) {
    update();
  }

  @Override
  public boolean validate() {
    if (!super.validate()) {
      return false;
    }
    int index = myTemplateList.getSelectedIndex();
    myTemplateImage.setIcon(null);
    setDescriptionHtml("");
    if (index != -1) {
      MetadataListItem templateListItem = (MetadataListItem)myTemplateList.getModel().getElementAt(index);

      if (templateListItem != null) {
        myTemplateState.setTemplateLocation(templateListItem.getTemplateFile());
        myTemplateState.convertApisToInt();
        String thumb = templateListItem.myMetadata.getThumbnailPath();
        if (thumb != null && !thumb.isEmpty()) {
          File file = new File(myTemplateState.myTemplate.getRootPath(), thumb.replace('/', File.separatorChar));
          try {
            byte[] bytes = Files.toByteArray(file);
            ImageIcon previewImage = new ImageIcon(bytes);
            myTemplateImage.setIcon(previewImage);
          }
          catch (IOException e) {
            LOG.warn(e);
          }
        } else {
          myTemplateImage.setIcon(AndroidIcons.Wizards.DefaultTemplate);
        }
        setDescriptionHtml(templateListItem.myMetadata.getDescription());
        int minSdk = templateListItem.myMetadata.getMinSdk();
        Integer minApi = (Integer)myTemplateState.get(ATTR_MIN_API);
        if (minApi != null && minSdk > minApi) {
          setErrorHtml(String.format("The activity %s has a minimum SDK level of %d.", templateListItem.myMetadata.getTitle(), minSdk));
          return false;
        }
        int minBuildApi = templateListItem.myMetadata.getMinBuildApi();
        Integer buildApi = (Integer)myTemplateState.get(ATTR_BUILD_API);
        if (buildApi != null && minSdk > buildApi) {
          setErrorHtml(String.format("The activity %s has a minimum build API level of %d.", templateListItem.myMetadata.getTitle(), minBuildApi));
          return false;
        }
        if (myTemplateChangeListener != null && myPreviousSelection != index) {
          myPreviousSelection = index;
          myTemplateChangeListener.templateChanged(templateListItem.toString());
        }
      }
    }
    return true;
  }

  @NotNull
  @Override
  protected JLabel getDescription() {
    return myDescription;
  }

  @NotNull
  @Override
  protected JLabel getError() {
    return myError;
  }

  protected static class MetadataListItem {
    private TemplateMetadata myMetadata;
    private final File myTemplate;

    public MetadataListItem(@NotNull File template, @NotNull TemplateMetadata metadata) {
      myTemplate = template;
      myMetadata = metadata;
    }

    @Override
    public String toString() {
      return myMetadata.getTitle();
    }

    /**
     * Get the folder containing this template
     */
    public File getTemplateFile() {
      return myTemplate;
    }
  }
}
