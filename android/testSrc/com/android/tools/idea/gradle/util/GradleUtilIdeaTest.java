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
package com.android.tools.idea.gradle.util;

import com.android.SdkConstants;
import com.android.tools.idea.gradle.IdeaGradleProject;
import com.android.tools.idea.gradle.facet.AndroidGradleFacet;
import com.intellij.facet.FacetManager;
import com.intellij.facet.ModifiableFacetModel;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.IdeaTestCase;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Tests for {@link GradleUtil}.
 */
public class GradleUtilIdeaTest extends IdeaTestCase {
  private File myModuleRootDir;
  private File myBuildFile;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    File moduleFilePath = new File(myModule.getModuleFilePath());
    myModuleRootDir = moduleFilePath.getParentFile();
    myBuildFile = new File(myModuleRootDir, SdkConstants.FN_BUILD_GRADLE);
    FileUtilRt.createIfNotExists(myBuildFile);
  }

  public void testGetGradleBuildFileFromRootDir() {
    VirtualFile buildFile = GradleUtil.getGradleBuildFile(myModuleRootDir);
    assertIsGradleBuildFile(buildFile);
  }

  public void testGetGradleBuildFileFromModuleWithoutGradleFacet() {
    VirtualFile buildFile = GradleUtil.getGradleBuildFile(myModule);
    assertIsGradleBuildFile(buildFile);
  }

  public void testGetGradleBuildFileFromModuleWithGradleFacet() {
    IdeaGradleProject gradleProject = new IdeaGradleProject(myModule.getName(), myBuildFile, myModule.getName());

    FacetManager facetManager = FacetManager.getInstance(myModule);
    ModifiableFacetModel model = facetManager.createModifiableModel();
    try {
      AndroidGradleFacet facet = facetManager.createFacet(AndroidGradleFacet.getFacetType(), AndroidGradleFacet.NAME, null);
      model.addFacet(facet);
      facet.setGradleProject(gradleProject);
    } finally {
      model.commit();
    }

    VirtualFile buildFile = GradleUtil.getGradleBuildFile(myModule);
    assertIsGradleBuildFile(buildFile);
  }

  private static void assertIsGradleBuildFile(@Nullable VirtualFile buildFile) {
    assertNotNull(buildFile);
    assertFalse(buildFile.isDirectory());
    assertTrue(buildFile.isValid());
    assertEquals(SdkConstants.FN_BUILD_GRADLE, buildFile.getName());
  }
}
