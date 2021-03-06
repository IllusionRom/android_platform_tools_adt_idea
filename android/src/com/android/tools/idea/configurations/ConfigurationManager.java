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
package com.android.tools.idea.configurations;

import com.android.annotations.VisibleForTesting;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.resources.configuration.LanguageQualifier;
import com.android.ide.common.resources.configuration.RegionQualifier;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.devices.Device;
import com.android.sdklib.devices.DeviceManager;
import com.android.sdklib.internal.avd.AvdInfo;
import com.android.tools.idea.gradle.AndroidModuleInfo;
import com.android.tools.idea.rendering.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.SoftValueHashMap;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.*;
import org.jetbrains.android.uipreview.UserDeviceManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.android.sdklib.devices.DeviceManager.DEFAULT_DEVICES;
import static com.android.sdklib.devices.DeviceManager.VENDOR_DEVICES;
import static com.android.tools.idea.configurations.ConfigurationListener.CFG_LOCALE;
import static com.android.tools.idea.configurations.ConfigurationListener.CFG_TARGET;

/**
 * A {@linkplain ConfigurationManager} is responsible for managing {@link Configuration}
 * objects for a given project.
 * <p>
 * Whereas a {@link Configuration} is tied to a specific render target or theme,
 * the {@linkplain ConfigurationManager} knows the set of available targets, themes,
 * locales etc. for the current project.
 * <p>
 * The {@linkplain ConfigurationManager} is also responsible for storing and retrieving
 * the saved configuration state for a given file.
 */
public class ConfigurationManager implements Disposable {
  @NotNull private final Module myModule;
  private List<Device> myDevices;
  private final UserDeviceManager myUserDeviceManager;
  private final SoftValueHashMap<VirtualFile, Configuration> myCache = new SoftValueHashMap<VirtualFile, Configuration>();
  private List<Locale> myLocales;
  private Device myDefaultDevice;
  private Locale myLocale;
  private IAndroidTarget myTarget;
  private int myStateVersion;
  private ResourceResolverCache myResolverCache;
  private long myLocaleCacheStamp;

  private ConfigurationManager(@NotNull Module module) {
    myModule = module;

    myUserDeviceManager = new UserDeviceManager() {
      @Override
      protected void userDevicesChanged() {
        // Force refresh
        myDevices = null;
        // TODO: How do I trigger changes in the UI?
      }
    };
    Disposer.register(this, myUserDeviceManager);
  }

  /**
   * Gets the {@link Configuration} associated with the given file
   * @return the {@link Configuration} for the given file
   */
  @NotNull
  public Configuration getConfiguration(@NotNull VirtualFile file) {
    Configuration configuration = myCache.get(file);
    if (configuration == null) {
      configuration = create(file);
      myCache.put(file, configuration);
    }

    return configuration;
  }

  @VisibleForTesting
  boolean hasCachedConfiguration(@NotNull VirtualFile file) {
    return myCache.get(file) != null;
  }

  /**
   * Creates a new {@link Configuration} associated with this manager
   * @return a new {@link Configuration}
   */
  @NotNull
  private Configuration create(@NotNull VirtualFile file) {
    ConfigurationStateManager stateManager = getStateManager();
    ConfigurationFileState fileState = stateManager.getConfigurationState(file);
    FolderConfiguration config = FolderConfiguration.getConfigForFolder(file.getParent().getName());
    if (config == null) {
      config = new FolderConfiguration();
    }
    Configuration configuration = Configuration.create(this, file, fileState, config);
    LocalResourceRepository resources = AppResourceRepository.getAppResources(myModule, true);
    ConfigurationMatcher matcher = new ConfigurationMatcher(configuration, resources, file);
    if (fileState != null) {
      matcher.adaptConfigSelection(true);
    } else {
      matcher.findAndSetCompatibleConfig(false);
    }

    return configuration;
  }

  /**
   * Similar to {@link #getConfiguration(com.intellij.openapi.vfs.VirtualFile)}, but creates a configuration
   * for a file known to be new, and crucially, bases the configuration on the existing configuration
   * for a known file. This is intended for when you fork a layout, and you expect the forked layout
   * to have a configuration that is (as much as possible) similar to the configuration of the
   * forked file. For example, if you create a landscape version of a layout, it will preserve the
   * screen size, locale, theme and render target of the existing layout.
   *
   * @param file the file to create a configuration for
   * @param baseFile the other file to base the configuration on
   * @return the new configuration
   */
  @NotNull
  public Configuration createSimilar(@NotNull VirtualFile file, @NotNull VirtualFile baseFile) {
    ConfigurationStateManager stateManager = getStateManager();
    ConfigurationFileState fileState = stateManager.getConfigurationState(baseFile);
    FolderConfiguration config = FolderConfiguration.getConfigForFolder(file.getParent().getName());
    if (config == null) {
      config = new FolderConfiguration();
    }
    Configuration configuration = Configuration.create(this, file, fileState, config);
    LocalResourceRepository resources = AppResourceRepository.getAppResources(myModule, true);
    ConfigurationMatcher matcher = new ConfigurationMatcher(configuration, resources, file);
    matcher.adaptConfigSelection(true /*needBestMatch*/);
    myCache.put(file, configuration);

    return configuration;
  }

  /** Returns the associated persistence manager */
  public ConfigurationStateManager getStateManager() {
    return ConfigurationStateManager.get(myModule.getProject());
  }

  /**
   * Creates a new {@link ConfigurationManager} for the given module
   *
   * @param module the associated module
   * @return a new {@link ConfigurationManager}
   */
  @NotNull
  public static ConfigurationManager create(@NotNull Module module) {
    return new ConfigurationManager(module);
  }

  /** Returns the list of available devices for the current platform, if any */
  @NotNull
  public List<Device> getDevices() {
    if (myDevices == null) {
      List<Device> devices = null;

      AndroidPlatform platform = AndroidPlatform.getPlatform(myModule);
      if (platform != null) {
        final AndroidSdkData sdkData = platform.getSdkData();
        devices = new ArrayList<Device>();
        DeviceManager deviceManager = sdkData.getDeviceManager();
        devices.addAll(deviceManager.getDevices(DEFAULT_DEVICES | VENDOR_DEVICES));
        devices.addAll(myUserDeviceManager.parseUserDevices(new MessageBuildingSdkLog()));
      }

      if (devices == null) {
        myDevices = Collections.emptyList();
      } else {
        myDevices = devices;
      }
    }

    return myDevices;
  }

  @Nullable
  public Device createDeviceForAvd(@NotNull AvdInfo avd) {
    AndroidFacet facet = AndroidFacet.getInstance(myModule);
    assert facet != null;
    for (Device device : getDevices()) {
      if (device.getManufacturer().equals(avd.getDeviceManufacturer())
          && (device.getId().equals(avd.getDeviceName()) || device.getDisplayName().equals(avd.getDeviceName()))) {

        String avdName = avd.getName();
        Device.Builder builder = new Device.Builder(device);
        builder.setName(avdName);
        return builder.build();
      }
    }

    return null;
  }

  /**
   * Returns all the {@link IAndroidTarget} instances applicable for the current module.
   * Note that this may include non-rendering targets, so for layout rendering contexts,
   * check individual members by calling {@link #isLayoutLibTarget(IAndroidTarget)} first.
   */
  @NotNull
  public IAndroidTarget[] getTargets() {
    AndroidPlatform platform = AndroidPlatform.getPlatform(myModule);
    if (platform != null) {
      final AndroidSdkData sdkData = platform.getSdkData();

      return sdkData.getTargets();
    }

    return new IAndroidTarget[0];
  }

  public static boolean isLayoutLibTarget(@NotNull IAndroidTarget target) {
    return target.isPlatform() && target.hasRenderingLibrary();
  }

  @Nullable
  public IAndroidTarget getHighestApiTarget() {
    // Note: The target list is already sorted in ascending API order.
    IAndroidTarget[] targetList = getTargets();
    for (int i = targetList.length - 1; i >= 0; i--) {
      IAndroidTarget target = targetList[i];
      if (isLayoutLibTarget(target)) {
        return target;
      }
    }

    return null;
  }

  /**
   * Returns the preferred theme, or null
   */
  @NotNull
  public String computePreferredTheme(@NotNull Configuration configuration) {
    ManifestInfo manifest = ManifestInfo.get(myModule);

    // TODO: If we are rendering a layout in included context, pick the theme
    // from the outer layout instead

    String activity = configuration.getActivity();
    if (activity != null) {
      Map<String, String> activityThemes = manifest.getActivityThemes();
      String theme = activityThemes.get(activity);
      if (theme != null) {
        return theme;
      }

      if (activity.startsWith(".")) {
        AndroidModuleInfo moduleInfo = AndroidModuleInfo.get(myModule);
        if (moduleInfo != null) {
          theme = activityThemes.get(moduleInfo.getPackage() + activity);
          if (theme != null) {
            return theme;
          }
        }

        theme = activityThemes.get(manifest.getPackage() + activity);
        if (theme != null) {
          return theme;
        }
      }
    }

    // Look up the default/fallback theme to use for this project (which
    // depends on the screen size when no particular theme is specified
    // in the manifest)
    return manifest.getDefaultTheme(configuration.getTarget(), configuration.getScreenSize());
  }

  @NotNull
  public Module getModule() {
    return myModule;
  }

  @NotNull
  public Project getProject() {
    return myModule.getProject();
  }

  @Override
  public void dispose() {
    myUserDeviceManager.dispose();
  }

  @Nullable
  public Device getDefaultDevice() {
    if (myDefaultDevice == null) {
      // Note that this may not be the device actually used in new layouts; the ConfigMatcher
      // has a PhoneComparator which sorts devices for a best match
      List<Device> devices = getDevices();
      if (!devices.isEmpty()) {
        Device device = devices.get(0);
        for (Device d : devices) {
          String name = d.getId();
          if (name.equals("Nexus 4")) {
            device = d;
            break;
          } else if (name.equals("Galaxy Nexus")) {
            device = d;
          }
        }

        myDefaultDevice = device;
      }
    }

    return myDefaultDevice;
  }

  /**
   * Return the default render target to use, or null if no strong preference
   */
  @Nullable
  public IAndroidTarget getDefaultTarget() {
    // Use the most recent target
    return getHighestApiTarget();
  }

  @NotNull
  public List<Locale> getLocales() {
    // Get locales from modules, but not libraries!
    LocalResourceRepository projectResources = ProjectResourceRepository.getProjectResources(myModule, true);
    if (projectResources.getModificationCount() > myLocaleCacheStamp) {
      myLocales = null;
    }
    if (myLocales == null) {
      List<Locale> locales = new ArrayList<Locale>();
      for (String language : projectResources.getLanguages()) {
        LanguageQualifier languageQualifier = new LanguageQualifier(language);
        locales.add(Locale.create(languageQualifier));
        for (String region : projectResources.getRegions(language)) {
          locales.add(Locale.create(languageQualifier, new RegionQualifier(region)));
        }
      }
      myLocales = locales;
      myLocaleCacheStamp = projectResources.getModificationCount();
    }

    return myLocales;
  }

  @Nullable
  public IAndroidTarget getProjectTarget() {
    AndroidPlatform platform = AndroidPlatform.getPlatform(myModule);
    return platform != null ? platform.getTarget() : null;
  }

  @NotNull
  public Locale getLocale() {
    if (myLocale == null) {
      String localeString = getStateManager().getProjectState().getLocale();
      if (localeString != null) {
        myLocale = ConfigurationProjectState.fromLocaleString(localeString);
      } else {
        myLocale = Locale.ANY;
      }
    }

    return myLocale;
  }

  public void setLocale(@NotNull Locale locale) {
    if (!locale.equals(myLocale)) {
      myLocale = locale;
      getStateManager().getProjectState().setLocale(ConfigurationProjectState.toLocaleString(locale));
      for (Configuration configuration : myCache.values()) {
        configuration.updated(CFG_LOCALE);
      }
      myStateVersion++;
    }
  }

  @Nullable
  public IAndroidTarget getTarget() {
    if (myTarget == null) {
      ConfigurationProjectState projectState = getStateManager().getProjectState();
      if (projectState.isPickTarget()) {
        myTarget = getDefaultTarget();
      } else {
        String targetString = projectState.getTarget();
        myTarget = ConfigurationProjectState.fromTargetString(this, targetString);
        if (myTarget == null) {
          myTarget = getDefaultTarget();
        }
      }
      return myTarget;
    }

    return myTarget;
  }

  /** Returns the best render target to use for the given minimum API level */
  @Nullable
  public IAndroidTarget getTarget(int min) {
    IAndroidTarget target = getTarget();
    if (target != null && target.getVersion().getApiLevel() >= min) {
      return target;
    }

    IAndroidTarget[] targetList = getTargets();
    for (int i = targetList.length - 1; i >= 0; i--) {
      target = targetList[i];
      if (isLayoutLibTarget(target) && target.getVersion().getApiLevel() >= min) {
        return target;
      }
    }

    return null;
  }

  public void setTarget(@Nullable IAndroidTarget target) {
    if (target != myTarget) {
      if (myTarget != null) {
        // Clear out the bitmap cache of the previous platform, since it's likely we won't
        // need it again. If you have *two* projects open with different platforms, this will
        // needlessly flush the bitmap cache for the project still using it, but that just
        // means the next render will need to fetch them again; from that point on both platform
        // bitmap sets are in memory.
        AndroidTargetData targetData = AndroidTargetData.getTargetData(myTarget, myModule);
        if (targetData != null) {
          targetData.clearLayoutBitmapCache(myModule);
        }
      }

      myTarget = target;
      if (target != null) {
        getStateManager().getProjectState().setTarget(ConfigurationProjectState.toTargetString(target));
        for (Configuration configuration : myCache.values()) {
          configuration.updated(CFG_TARGET);
        }
        myStateVersion++;
      }
    }
  }

  /**
   * Synchronizes changes to the given attributes (indicated by the mask
   * referencing the {@code CFG_} configuration attribute bit flags in
   * {@link Configuration} to the layout variations of the given updated file.
   *
   * @param flags the attributes which were updated
   * @param updatedFile the file which was updated
   * @param base the base configuration to base the chooser off of
   * @param includeSelf whether the updated file itself should be updated
   * @param async whether the updates should be performed asynchronously
   */
  public void syncToVariations(
    final int flags,
    final @NotNull VirtualFile updatedFile,
    final @NotNull Configuration base,
    final boolean includeSelf,
    boolean async) {
    if (async) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          doSyncToVariations(flags, updatedFile, includeSelf, base);
        }
      });
    } else {
      doSyncToVariations(flags, updatedFile, includeSelf, base);
    }
  }

  private void doSyncToVariations(@SuppressWarnings("UnusedParameters") int flags,
                                  VirtualFile updatedFile, boolean includeSelf,
                                  Configuration base) {
    // Synchronize the given changes to other configurations as well
    List<VirtualFile> files = ResourceHelper.getResourceVariations(updatedFile, includeSelf);
    for (VirtualFile file : files) {
      Configuration configuration = getConfiguration(file);
      Configuration.copyCompatible(base, configuration);
      configuration.save();
    }
  }

  public int getStateVersion() {
    return myStateVersion;
  }

  public ResourceResolverCache getResolverCache() {
    if (myResolverCache == null) {
      myResolverCache = ResourceResolverCache.create(this);
    }

    return myResolverCache;
  }
}
