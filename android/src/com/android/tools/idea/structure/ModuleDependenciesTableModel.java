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
package com.android.tools.idea.structure;

import com.android.tools.idea.gradle.parser.Dependency;
import com.google.common.collect.Lists;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.util.ui.ItemRemovable;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/**
 * A data model for the Android Gradle module dependencies table.
 */
public class ModuleDependenciesTableModel extends AbstractTableModel implements ItemRemovable {
  private static final String SCOPE_COLUMN_NAME = ProjectBundle.message("modules.order.export.scope.column");
  public static final int ITEM_COLUMN = 0;
  public static final int SCOPE_COLUMN = 1;
  private final List<ModuleDependenciesTableItem> myItems = Lists.newArrayList();
  private boolean myModified;

  public ModuleDependenciesTableItem getItemAt(int row) {
    return myItems.get(row);
  }

  public void addItem(ModuleDependenciesTableItem item) {
    myItems.add(item);
    myModified = true;
  }

  public void addItemAt(ModuleDependenciesTableItem item, int row) {
    myItems.add(row, item);
    myModified = true;
  }

  public ModuleDependenciesTableItem removeDataRow(int row) {
    myModified = true;
    return myItems.remove(row);
  }

  @Override
  public void removeRow(int row) {
    removeDataRow(row);
  }

  public void clear() {
    myModified = true;
    myItems.clear();
  }

  @Override
  public int getRowCount() {
    return myItems.size();
  }

  @Nullable
  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    final ModuleDependenciesTableItem item = myItems.get(rowIndex);
    if (columnIndex == SCOPE_COLUMN) {
      return item.getScope();
    }
    if (columnIndex == ITEM_COLUMN) {
      return item;
    }
    return null;
  }

  @Override
  public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
    final ModuleDependenciesTableItem item = myItems.get(rowIndex);
    if (columnIndex == SCOPE_COLUMN && aValue instanceof Dependency.Scope) {
      item.setScope((Dependency.Scope)aValue);
    }
    myModified = true;
  }

  @Override
  public String getColumnName(int column) {
    if (column == SCOPE_COLUMN) {
      return SCOPE_COLUMN_NAME;
    }
    return "";
  }

  @Override
  @Nullable
  public Class getColumnClass(int column) {
    if (column == SCOPE_COLUMN) {
      return Dependency.Scope.class;
    }
    if (column == ITEM_COLUMN) {
      return ModuleDependenciesTableItem.class;
    }
    return null;
  }

  @Override
  public int getColumnCount() {
    return 2;
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return column == SCOPE_COLUMN;
  }

  public List<ModuleDependenciesTableItem> getItems() {
    return myItems;
  }

  public boolean isModified() {
    return myModified;
  }

  public void resetModified() {
    myModified = false;
  }

  public void setModified() {
    myModified = true;
  }
}
