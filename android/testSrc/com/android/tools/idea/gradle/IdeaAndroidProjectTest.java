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
 */package com.android.tools.idea.gradle;

import com.android.builder.model.AndroidArtifact;
import com.android.builder.model.BuildTypeContainer;
import com.android.builder.model.ProductFlavorContainer;
import com.android.builder.model.Variant;
import com.android.tools.idea.gradle.stubs.android.AndroidProjectStub;
import com.android.tools.idea.gradle.stubs.android.VariantStub;
import com.intellij.testFramework.IdeaTestCase;

import java.io.File;

/**
 * Tests for {@link IdeaAndroidProject}.
 */
public class IdeaAndroidProjectTest extends IdeaTestCase {
  private AndroidProjectStub myDelegate;
  private IdeaAndroidProject myAndroidProject;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    File rootDirPath = new File(getProject().getBasePath());
    myDelegate = TestProjects.createFlavorsProject();
    myAndroidProject = new IdeaAndroidProject(myDelegate.getName(), rootDirPath, myDelegate, "f1fa-debug");
  }

  public void testFindBuildType() throws Exception {
    String buildTypeName = "debug";
    BuildTypeContainer buildType = myAndroidProject.findBuildType(buildTypeName);
    assertNotNull(buildType);
    assertSame(myDelegate.findBuildType(buildTypeName), buildType);
  }

  public void testFindProductFlavor() throws Exception {
    String flavorName = "fa";
    ProductFlavorContainer flavor = myAndroidProject.findProductFlavor(flavorName);
    assertNotNull(flavor);
    assertSame(myDelegate.findProductFlavor(flavorName), flavor);
  }

  public void testFindInstrumentationTestArtifactInSelectedVariant() throws Exception {
    AndroidArtifact instrumentationTestArtifact = myAndroidProject.findInstrumentationTestArtifactInSelectedVariant();
    VariantStub firstVariant = myDelegate.getFirstVariant();
    assertNotNull(firstVariant);
    assertSame(firstVariant.getInstrumentTestArtifact(), instrumentationTestArtifact);
  }

  public void testGetSelectedVariant() throws Exception {
    Variant selectedVariant = myAndroidProject.getSelectedVariant();
    assertNotNull(selectedVariant);
    assertSame(myDelegate.getFirstVariant(), selectedVariant);
  }
}
