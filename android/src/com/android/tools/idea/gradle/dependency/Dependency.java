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
package com.android.tools.idea.gradle.dependency;

import com.android.builder.model.AndroidArtifact;
import com.android.builder.model.AndroidLibrary;
import com.android.builder.model.Variant;
import com.android.tools.idea.gradle.IdeaAndroidProject;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.util.io.FileUtil;
import org.gradle.tooling.model.idea.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.android.SdkConstants.DOT_AAR;
import static com.android.SdkConstants.FD_RES;

/**
 * An IDEA module's dependency on an artifact (e.g. a jar file or another IDEA module.)
 */
public abstract class Dependency {
  /**
   * The Android Gradle plug-in only supports "compile" and "test" scopes. This list is sorted by width of the scope, being "compile" a
   * wider scope than "test."
   */
  static final List<DependencyScope> SUPPORTED_SCOPES = Lists.newArrayList(DependencyScope.COMPILE, DependencyScope.TEST);

  @NotNull private final String myName;
  @NotNull private DependencyScope myScope;

  /**
   * Creates a new {@link Dependency}. This constructor sets the scope to {@link DependencyScope#COMPILE}.
   *
   * @param name the name of the artifact to depend on.
   */
  Dependency(@NotNull String name) {
    this(name, DependencyScope.COMPILE);
  }

  /**
   * Creates a new {@link Dependency}.
   *
   * @param name  the name of the artifact to depend on.
   * @param scope the scope of the dependency. Supported values are {@link DependencyScope#COMPILE} and {@link DependencyScope#TEST}.
   * @throws IllegalArgumentException if the given scope is not supported.
   */
  Dependency(@NotNull String name, @NotNull DependencyScope scope) throws IllegalArgumentException {
    myName = name;
    setScope(scope);
  }

  @NotNull
  public final String getName() {
    return myName;
  }

  @NotNull
  public final DependencyScope getScope() {
    return myScope;
  }

  /**
   * Sets the scope of this dependency.
   *
   * @param scope the scope of the dependency. Supported values are {@link DependencyScope#COMPILE} and {@link DependencyScope#TEST}.
   * @throws IllegalArgumentException if the given scope is not supported.
   */
  void setScope(@NotNull DependencyScope scope) throws IllegalArgumentException {
    if (!SUPPORTED_SCOPES.contains(scope)) {
      String msg = String.format("'%1$s' is not a supported scope. Supported scopes are %2$s.", scope, SUPPORTED_SCOPES);
      throw new IllegalArgumentException(msg);
    }
    myScope = scope;
  }

  @NotNull
  public static Collection<Dependency> extractFrom(@NotNull IdeaAndroidProject androidProject) {
    DependencySet dependencies = new DependencySet();
    Variant selectedVariant = androidProject.getSelectedVariant();

    AndroidArtifact testArtifact = androidProject.findInstrumentationTestArtifactInSelectedVariant();
    if (testArtifact != null) {
      populate(dependencies, testArtifact, DependencyScope.TEST);
    }
    AndroidArtifact mainArtifact = selectedVariant.getMainArtifact();
    populate(dependencies, mainArtifact, DependencyScope.COMPILE);

    return dependencies.getValues();
  }

  private static void populate(@NotNull DependencySet dependencies,
                               @NotNull AndroidArtifact androidArtifact,
                               @NotNull DependencyScope scope) {
    populate(dependencies, androidArtifact.getDependencies().getJars(), scope);

    Set<File> unique = Sets.newHashSet();
    for (AndroidLibrary lib : androidArtifact.getDependencies().getLibraries()) {
      ModuleDependency mainDependency = null;
      String gradleProjectPath = lib.getProject();
      if (gradleProjectPath != null && !gradleProjectPath.isEmpty()) {
        //noinspection TestOnlyProblems
        mainDependency = new ModuleDependency(gradleProjectPath, scope);
        dependencies.add(mainDependency);
      }
      if (mainDependency == null) {
        addLibrary(lib, dependencies, scope, unique);
      }
      else {
        // add the aar as dependency in case there is a module dependency that cannot be satisfied (e.g. the module is outside of the
        // project.) If we cannot set the module dependency, we set a library dependency instead.
        LibraryDependency backupDependency = new LibraryDependency(getLibraryName(lib));
        backupDependency.addPath(LibraryDependency.PathType.BINARY, lib.getJarFile());
        //noinspection TestOnlyProblems
        mainDependency.setBackupDependency(backupDependency);
      }

      populate(dependencies, lib.getLocalJars(), scope);
    }

    for (String gradleProjectPath : androidArtifact.getDependencies().getProjects()) {
      if (gradleProjectPath != null && !gradleProjectPath.isEmpty()) {
        //noinspection TestOnlyProblems
        ModuleDependency dependency = new ModuleDependency(gradleProjectPath, scope);
        dependencies.add(dependency);
      }
    }
  }

  private static String getLibraryName(@NotNull AndroidLibrary library) {
    File jar = library.getJarFile();
    File aar = jar.getParentFile();
    return aar != null ? aar.getName() : FileUtil.getNameWithoutExtension(jar);
  }

  /**
   * Add a library, along with any recursive library dependencies
   */
  private static void addLibrary(@NotNull AndroidLibrary library,
                                 @NotNull DependencySet dependencies,
                                 @NotNull DependencyScope scope,
                                 @NotNull Set<File> unique) {
    // We're using the library location as a unique handle rather than the AndroidLibrary instance itself, in case
    // the model just blindly manufactures library instances as it's following dependencies
    File folder = library.getFolder();
    if (unique.contains(folder)) {
      return;
    }
    unique.add(folder);

    LibraryDependency dependency = new LibraryDependency(getLibraryName(library), scope);
    File jar = library.getJarFile();
    dependency.addPath(LibraryDependency.PathType.BINARY, jar);
    dependencies.add(dependency);

    // The model does not yet provide pointers to resources in AAR files, so
    // manually look for them where they are known to be and add them manually
    File aar = jar.getParentFile();
    if (aar != null && aar.getName().endsWith(DOT_AAR)) {
      File res = new File(aar, FD_RES);
      if (res.exists()) {
        dependency.addPath(LibraryDependency.PathType.BINARY, res);
      }
    }

    for (AndroidLibrary dependentLibrary : library.getLibraryDependencies()) {
      addLibrary(dependentLibrary, dependencies, scope, unique);
    }
  }

  private static void populate(@NotNull DependencySet dependencies, @NotNull Collection<File> jars, @NotNull DependencyScope scope) {
    for (File jar : jars) {
      //noinspection TestOnlyProblems
      dependencies.add(new LibraryDependency(jar, scope));
    }
  }

  @NotNull
  public static Collection<Dependency> extractFrom(@NotNull IdeaModule module) {
    DependencySet dependencies = new DependencySet();
    for (IdeaDependency ideaDependency : module.getDependencies()) {
      DependencyScope scope = parseScope(ideaDependency.getScope());

      if (ideaDependency instanceof IdeaModuleDependency) {
        IdeaModule ideaModule = ((IdeaModuleDependency)ideaDependency).getDependencyModule();
        String moduleName = ideaModule.getName();
        String gradlePath = ideaModule.getGradleProject().getPath();
        Dependency dependency = new ModuleDependency(moduleName, gradlePath, scope);
        dependencies.add(dependency);
      }
      else if (ideaDependency instanceof IdeaSingleEntryLibraryDependency) {
        IdeaSingleEntryLibraryDependency ideaLibrary = (IdeaSingleEntryLibraryDependency)ideaDependency;
        //noinspection TestOnlyProblems
        LibraryDependency dependency = new LibraryDependency(ideaLibrary.getFile(), scope);
        File javadoc = ideaLibrary.getJavadoc();
        if (javadoc != null) {
          dependency.addPath(LibraryDependency.PathType.DOC, javadoc);
        }
        File source = ideaLibrary.getSource();
        if (source != null) {
          dependency.addPath(LibraryDependency.PathType.SOURCE, source);
        }
        dependencies.add(dependency);
      }
    }
    return dependencies.getValues();
  }

  @NotNull
  private static DependencyScope parseScope(@Nullable IdeaDependencyScope scope) {
    if (scope != null) {
      String scopeAsString = scope.getScope();
      if (scopeAsString != null) {
        for (DependencyScope dependencyScope : DependencyScope.values()) {
          if (scopeAsString.equalsIgnoreCase(dependencyScope.toString())) {
            return dependencyScope;
          }
        }
      }
    }
    return DependencyScope.COMPILE;
  }
}
