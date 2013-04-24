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
package com.android.tools.idea.gradle.model.android;

import com.android.build.gradle.model.Dependencies;
import com.android.builder.model.AndroidLibrary;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

public class DependenciesStub implements Dependencies {
  @NotNull
  @Override
  public List<AndroidLibrary> getLibraries() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public List<File> getJars() {
    throw new UnsupportedOperationException();
  }

  @NotNull
  @Override
  public List<String> getProjectDependenciesPath() {
    throw new UnsupportedOperationException();
  }
}