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
package com.android.tools.idea.rendering;

import com.android.resources.ResourceType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.android.AndroidTestCase;

import java.util.Arrays;
import java.util.Collections;

import static com.android.tools.idea.rendering.ModuleResourceRepositoryTest.getFirstItem;

public class AppResourceRepositoryTest extends AndroidTestCase {
  private static final String LAYOUT = "resourceRepository/layout.xml";
  private static final String VALUES = "resourceRepository/values.xml";
  private static final String VALUES_OVERLAY1 = "resourceRepository/valuesOverlay1.xml";
  private static final String VALUES_OVERLAY2 = "resourceRepository/valuesOverlay2.xml";
  private static final String VALUES_OVERLAY2_NO = "resourceRepository/valuesOverlay2No.xml";

  public void testStable() {
    assertSame(AppResourceRepository.getAppResources(myFacet, true), AppResourceRepository.getAppResources(myFacet, true));
    assertSame(AppResourceRepository.getAppResources(myFacet, true), AppResourceRepository.getAppResources(myModule, true));
  }

  public void testMerging() {
    // Like testOverlayUpdates1, but rather than testing changes to layout resources (file-based resource)
    // perform document edits in value-documents

    VirtualFile layoutFile = myFixture.copyFileToProject(LAYOUT, "res/layout/layout1.xml");

    VirtualFile res1 = myFixture.copyFileToProject(VALUES, "res/values/values.xml").getParent().getParent();
    VirtualFile res2 = myFixture.copyFileToProject(VALUES_OVERLAY1, "res2/values/values.xml").getParent().getParent();
    VirtualFile res3 = myFixture.copyFileToProject(VALUES_OVERLAY2, "res3/values/nameDoesNotMatter.xml").getParent().getParent();
    myFixture.copyFileToProject(VALUES_OVERLAY2_NO, "res3/values-no/values.xml");

    assertNotSame(res1, res2);
    assertNotSame(res1, res3);
    assertNotSame(res2, res3);

    // res3 is not used as an overlay here; instead we use it to simulate an AAR library below
    final ModuleResourceRepository moduleRepository = ModuleResourceRepository.createForTest(myFacet, Arrays.asList(res1, res2));
    final ProjectResourceRepository projectResources = ProjectResourceRepository.createForTest(
      myFacet, Arrays.<LocalResourceRepository>asList(moduleRepository));

    final AppResourceRepository appResources = AppResourceRepository.createForTest(
      myFacet, Collections.<LocalResourceRepository>singletonList(projectResources), Collections.<LocalResourceRepository>emptyList());

    assertTrue(appResources.hasResourceItem(ResourceType.STRING, "title_card_flip"));
    assertFalse(appResources.hasResourceItem(ResourceType.STRING, "non_existent_title_card_flip"));

    assertTrue(projectResources.hasResourceItem(ResourceType.STRING, "title_card_flip"));
    assertFalse(projectResources.hasResourceItem(ResourceType.STRING, "non_existent_title_card_flip"));

    assertTrue(moduleRepository.hasResourceItem(ResourceType.STRING, "title_card_flip"));
    assertFalse(moduleRepository.hasResourceItem(ResourceType.STRING, "non_existent_title_card_flip"));

    FileResourceRepository aar1 = FileResourceRepository.get(VfsUtilCore.virtualToIoFile(res3));
    appResources.updateRoots(Arrays.<LocalResourceRepository>asList(projectResources, aar1),
                             Collections.<LocalResourceRepository>singletonList(aar1));

    assertTrue(appResources.hasResourceItem(ResourceType.STRING, "another_unique_string"));
    assertTrue(aar1.hasResourceItem(ResourceType.STRING, "another_unique_string"));
    assertFalse(projectResources.hasResourceItem(ResourceType.STRING, "another_unique_string"));
    assertFalse(moduleRepository.hasResourceItem(ResourceType.STRING, "another_unique_string"));
    assertTrue(appResources.hasResourceItem(ResourceType.STRING, "title_card_flip"));
    assertFalse(appResources.hasResourceItem(ResourceType.STRING, "non_existent_title_card_flip"));

    // Update module resource repository and assert that changes make it all the way up
    PsiFile layoutPsiFile = PsiManager.getInstance(getProject()).findFile(layoutFile);
    assertNotNull(layoutPsiFile);
    assertTrue(moduleRepository.hasResourceItem(ResourceType.ID, "btn_title_refresh"));
    final PsiResourceItem item = getFirstItem(moduleRepository, ResourceType.ID, "btn_title_refresh");

    final long generation = moduleRepository.getModificationCount();
    final long projectGeneration = projectResources.getModificationCount();
    final long appGeneration = appResources.getModificationCount();
    final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(getProject());
    final Document document = documentManager.getDocument(layoutPsiFile);
    assertNotNull(document);
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        String string = "<ImageView style=\"@style/TitleBarSeparator\" />";
        int offset = document.getText().indexOf(string);
        document.deleteString(offset, offset + string.length());
        documentManager.commitDocument(document);
      }
    });

    assertTrue(moduleRepository.isScanPending(layoutPsiFile));
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        assertTrue(generation < moduleRepository.getModificationCount());
        assertTrue(projectGeneration < projectResources.getModificationCount());
        assertTrue(appGeneration < appResources.getModificationCount());
        // Should still be defined:
        assertTrue(moduleRepository.hasResourceItem(ResourceType.ID, "btn_title_refresh"));
        assertTrue(appResources.hasResourceItem(ResourceType.ID, "btn_title_refresh"));
        assertTrue(projectResources.hasResourceItem(ResourceType.ID, "btn_title_refresh"));
        PsiResourceItem newItem = getFirstItem(appResources, ResourceType.ID, "btn_title_refresh");
        assertNotNull(newItem.getSource());
        // However, should be a different item
        assertNotSame(item, newItem);
      }
    });
  }
}
