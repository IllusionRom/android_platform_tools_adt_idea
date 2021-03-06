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

import com.android.tools.idea.AndroidTestCaseHelper;
import com.android.tools.idea.gradle.util.Projects;
import com.google.common.collect.Lists;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.KeyValue;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.IdeaTestCase;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;

import java.util.List;

import static org.easymock.classextension.EasyMock.*;

/**
 * Tests for {@link AndroidGradleBuildProcessParametersProvider}.
 */
public class AndroidGradleBuildProcessParametersProviderTest extends IdeaTestCase {
  private AndroidGradleBuildProcessParametersProvider myParametersProvider;
  private Sdk myJdk;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    myJdk = AndroidTestCaseHelper.createAndSetJdk(myProject);
    myParametersProvider = new AndroidGradleBuildProcessParametersProvider(myProject);
  }

  public void testPopulateJvmArgsWithGradleExecutionSettings() {
    GradleExecutionSettings settings = createMock(GradleExecutionSettings.class);

    expect(settings.getRemoteProcessIdleTtlInMs()).andReturn(55L);
    expect(settings.getGradleHome()).andReturn("~/gradle-1.6");
    expect(settings.isVerboseProcessing()).andReturn(true);
    expect(settings.getServiceDirectory()).andReturn("~./gradle");
    expect(settings.getDaemonVmOptions()).andReturn("-Xmx2048m -XX:MaxPermSize=512m");

    replay(settings);

    List<String> jvmArgs = Lists.newArrayList();
    myParametersProvider.populateJvmArgs(settings, jvmArgs);

    verify(settings);

    String projectDirPath = FileUtil.toSystemDependentName(myProject.getBasePath());
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.project.path=" + projectDirPath));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.daemon.max.idle.time=55"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.home.path=~/gradle-1.6"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.use.verbose.logging=true"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.service.dir.path=~./gradle"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.daemon.jvm.option.count=2"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.daemon.jvm.option.0=-Xmx2048m"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.daemon.jvm.option.1=-XX:MaxPermSize=512m"));
    String javaHomeDirPath = myJdk.getHomePath();
    assertNotNull(javaHomeDirPath);
    javaHomeDirPath = FileUtil.toSystemDependentName(javaHomeDirPath);
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.java.home.path=" + javaHomeDirPath));
  }

  public void testPopulateHttpProxyProperties() {
    List<KeyValue<String, String>> properties = Lists.newArrayList();
    properties.add(KeyValue.create("http.proxyHost", "proxy.android.com"));
    properties.add(KeyValue.create("http.proxyPort", "8080"));

    List<String> jvmArgs = Lists.newArrayList();
    AndroidGradleBuildProcessParametersProvider.populateHttpProxyProperties(jvmArgs, properties);

    assertEquals(3, jvmArgs.size());
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.proxy.property.count=2"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.proxy.property.0=http.proxyHost:proxy.android.com"));
    assertTrue(jvmArgs.contains("-Dcom.android.studio.gradle.proxy.property.1=http.proxyPort:8080"));
  }

  public void testPopulateModulesToBuildWithModuleNames() {
    Projects.setModulesToBuild(myProject, new Module[] {myModule});
    List<String> jvmArgs= Lists.newArrayList();
    myParametersProvider.populateModulesToBuild(jvmArgs);
    assertEquals(2, jvmArgs.size());
    assertEquals("-Dcom.android.studio.gradle.modules.count=1", jvmArgs.get(0));
    assertEquals("-Dcom.android.studio.gradle.modules.0=" + myModule.getName(), jvmArgs.get(1));
  }
}
