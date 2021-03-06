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
package com.android.tools.idea.gradle.service;

import com.android.tools.idea.gradle.AndroidProjectKeys;
import com.android.tools.idea.gradle.ProjectImportEventMessage;
import com.android.tools.idea.gradle.service.notification.CustomNotificationListener;
import com.android.tools.idea.gradle.service.notification.NotificationHyperlink;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemIdeNotificationManager;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.util.Collection;
import java.util.List;

/**
 * Presents to the user any unexpected events that occurred during project import.
 */
public class ProjectImportEventMessageDataService implements ProjectDataService<ProjectImportEventMessage, Void> {
  private static final Logger LOG = Logger.getInstance(ProjectImportEventMessageDataService.class);

  public static final String NO_CATEGORY = "";
  public static final String RECOMMENDED_ACTIONS_CATEGORY = "Recommended actions:";

  @NotNull
  @Override
  public Key<ProjectImportEventMessage> getTargetDataKey() {
    return AndroidProjectKeys.IMPORT_EVENT_MSG;
  }

  @Override
  public void importData(@NotNull Collection<DataNode<ProjectImportEventMessage>> toImport,
                         @NotNull final Project project,
                         boolean synchronous) {
    ExternalSystemIdeNotificationManager notificationManager = ServiceManager.getService(ExternalSystemIdeNotificationManager.class);
    if (notificationManager == null) {
      return;
    }

    final List<NotificationHyperlink> hyperlinks = Lists.newArrayList();

    Multimap<String, ProjectImportEventMessage.Content> messagesByCategory = ArrayListMultimap.create();

    for (DataNode<ProjectImportEventMessage> node : toImport) {
      ProjectImportEventMessage message = node.getData();
      String category = message.getCategory();
      messagesByCategory.put(category, message.getContent());
      LOG.info(message.toString());
    }

    if (messagesByCategory.isEmpty()) {
      return;
    }

    Collection<ProjectImportEventMessage.Content> individualMessages = messagesByCategory.removeAll(NO_CATEGORY);
    Collection<ProjectImportEventMessage.Content> recommendedActions = messagesByCategory.removeAll(RECOMMENDED_ACTIONS_CATEGORY);

    final StringBuilder builder = new StringBuilder();

    for (String category : messagesByCategory.keySet()) {
      Collection<ProjectImportEventMessage.Content> messages = messagesByCategory.get(category);
      builder.append(category).append('\n');
      // We show the category and each message as a list.
      for (ProjectImportEventMessage.Content message : messages) {
        appendMessage(message, builder, hyperlinks);
      }
      builder.append("\n");
    }

    // Show individual messages (without a category).
    if (individualMessages != null && !individualMessages.isEmpty()) {
      for (ProjectImportEventMessage.Content message : individualMessages) {
        appendMessage(message, builder, hyperlinks);
      }
      builder.append("\n");
    }

    // At the end, show "Recommended Actions"
    if (recommendedActions != null && !recommendedActions.isEmpty()) {
      builder.append(RECOMMENDED_ACTIONS_CATEGORY).append('\n');
      for (ProjectImportEventMessage.Content message : recommendedActions) {
        appendMessage(message, builder, hyperlinks);
      }
      builder.append("\n");
    }

    String title = String.format("Problems importing/refreshing Gradle project '%1$s':\n", project.getName());
    String messageToShow = builder.toString();
    NotificationListener listener = null;
    if (!hyperlinks.isEmpty()) {
      listener = new CustomNotificationListener(project, hyperlinks.toArray(new NotificationHyperlink[hyperlinks.size()]));
    }
    notificationManager.showNotification(title, messageToShow, NotificationType.ERROR, project, GradleConstants.SYSTEM_ID, listener);
  }

  private static void appendMessage(@NotNull ProjectImportEventMessage.Content message,
                                    @NotNull StringBuilder builder,
                                    @NotNull List<NotificationHyperlink> hyperlinks) {
    String text = StringUtil.escapeXml(message.getText());
    builder.append("- ").append(text);
    NotificationHyperlink hyperlink = message.getHyperlink();
    if (hyperlink != null) {
      hyperlinks.add(hyperlink);
      builder.append(" ").append(hyperlink.toString());
    }
    builder.append('\n');
  }

  @Override
  public void removeData(@NotNull Collection<? extends Void> toRemove, @NotNull Project project, boolean synchronous) {
  }
}
