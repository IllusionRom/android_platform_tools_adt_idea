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

import com.android.ide.common.sdk.SdkVersionInfo;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.SdkManager;
import com.android.sdklib.local.LocalPkgInfo;
import com.android.sdklib.local.LocalSdk;
import com.android.sdklib.repository.FullRevision;
import com.android.tools.idea.templates.Parameter;
import com.android.tools.idea.templates.TemplateMetadata;
import com.android.tools.idea.templates.TemplateUtils;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.pom.java.LanguageLevel;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.android.tools.idea.templates.TemplateMetadata.*;
import static com.android.tools.idea.wizard.NewModuleWizardState.APP_NAME;
import static com.android.tools.idea.wizard.NewProjectWizardState.*;

/**
 * ConfigureAndroidModuleStep is the first page in the New Project wizard that sets project/module name, location, and other project-global
 * parameters.
 */
public class ConfigureAndroidModuleStep extends TemplateWizardStep {
  private static final Logger LOG = Logger.getInstance("#" + ConfigureAndroidModuleStep.class.getName());
  private static final String SAMPLE_PACKAGE_PREFIX = "com.example.";
  private static final String INVALID_FILENAME_CHARS = "[/\\\\?%*:|\"<>]";
  private static final Set<String> INVALID_MSFT_FILENAMES = ImmutableSet
    .of("con", "prn", "aux", "clock$", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2",
        "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "$mft", "$mftmirr", "$logfile", "$volume", "$attrdef", "$bitmap", "$boot",
        "$badclus", "$secure", "$upcase", "$extend", "$quota", "$objid", "$reparse");

  private TextFieldWithBrowseButton myProjectLocation;
  private JTextField myAppName;
  private JTextField myPackageName;
  private JComboBox myMinSdk;
  private JComboBox myTargetSdk;
  private JComboBox myCompileWith;
  private JComboBox myTheme;
  private JCheckBox myCreateCustomLauncherIconCheckBox;
  private JCheckBox myCreateActivityCheckBox;
  private JCheckBox myLibraryCheckBox;
  private JCheckBox myFragmentCheckBox;
  private JCheckBox myActionBarCheckBox;
  private JPanel myPanel;
  private JTextField myModuleName;
  private JLabel myDescription;
  private JLabel myError;
  private JLabel myProjectLocationLabel;
  private JLabel myModuleNameLabel;
  private JCheckBox myGridLayoutCheckBox;
  private JCheckBox myNavigationDrawerCheckBox;
  private JComboBox mySourceCombo;
  private JLabel mySourceVersionLabel;
  private JLabel myAppNameLabel;
  boolean myInitializedPackageNameText = false;
  private boolean myInitialized = false;
  @Nullable private WizardContext myWizardContext;

  public ConfigureAndroidModuleStep(TemplateWizardState state, @Nullable Project project, @Nullable Icon sidePanelIcon,
                                    UpdateListener updateListener) {
    super(state, project, sidePanelIcon, updateListener);
  }

  private void initialize() {
    IAndroidTarget[] targets = getCompilationTargets();

    if (AndroidSdkUtils.isAndroidSdkAvailable()) {
      String[] knownVersions = TemplateUtils.getKnownVersions();

      for (int i = 0; i < knownVersions.length; i++) {
        AndroidTargetComboBoxItem targetInfo = new AndroidTargetComboBoxItem(knownVersions[i], i + 1);
        myMinSdk.addItem(targetInfo);
        myTargetSdk.addItem(targetInfo);
      }
      myTemplateState.put(ATTR_TARGET_API, SdkVersionInfo.HIGHEST_KNOWN_API);
    }

    int highestApi = -1;
    for (IAndroidTarget target : targets) {
      highestApi = Math.max(highestApi, target.getVersion().getApiLevel());
      AndroidTargetComboBoxItem targetInfo = new AndroidTargetComboBoxItem(target);
      myCompileWith.addItem(targetInfo);
      if (target.getVersion().isPreview()) {
        myMinSdk.addItem(targetInfo);
        myTargetSdk.addItem(targetInfo);
      }
    }
    if (highestApi >= 1) {
      myTemplateState.put(ATTR_BUILD_API, highestApi);
      if (highestApi > SdkVersionInfo.HIGHEST_KNOWN_API) {
        myTemplateState.put(ATTR_TARGET_API, highestApi);
      }
    }

    // If using KitKat platform tools, we can support language level
    if (isJdk7Supported(getSdkManager())) {
      // We only support a few levels at this point, not for example 1.3 or 1.8
      mySourceCombo.addItem(new SourceLevelComboBoxItem(LanguageLevel.JDK_1_5));
      mySourceCombo.addItem(new SourceLevelComboBoxItem(LanguageLevel.JDK_1_6));
      mySourceCombo.addItem(new SourceLevelComboBoxItem(LanguageLevel.JDK_1_7));
      if (!myTemplateState.hasAttr(ATTR_JAVA_VERSION)) {
        LanguageLevel defaultLevel = LanguageLevel.JDK_1_6;
        myTemplateState.put(ATTR_JAVA_VERSION, languageLevelToString( defaultLevel));
      }
    } else {
      mySourceVersionLabel.setVisible(false);
      mySourceCombo.setVisible(false);
    }

    // Find a unique project location
    String projectLocation = myTemplateState.getString(ATTR_PROJECT_LOCATION);
    if (projectLocation != null && !projectLocation.isEmpty() && (myProject == null || !myProject.isInitialized())) {
      File file = new File(projectLocation);
      if (file.exists()) {
        String appName = myTemplateState.getString(ATTR_APP_TITLE);
        int i = 2;
        while (file.exists()) {
          myTemplateState.put(ATTR_APP_TITLE, appName + Integer.toString(i));
          deriveValues();
          file = new File(myTemplateState.getString(ATTR_PROJECT_LOCATION));
          i++;
        }
      }
    }


    registerUiElements();

    myProjectLocation.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        FileSaverDescriptor fileSaverDescriptor = new FileSaverDescriptor("Project location", "Please choose a location for your project");
        File currentPath = new File(myProjectLocation.getText());
        File parentPath = currentPath.getParentFile();
        if (parentPath == null) {
          parentPath = new File("/");
        }
        VirtualFile parent = LocalFileSystem.getInstance().findFileByIoFile(parentPath);
        String filename = currentPath.getName();
        VirtualFileWrapper fileWrapper =
            FileChooserFactory.getInstance().createSaveFileDialog(fileSaverDescriptor, (Project)null).save(parent, filename);
        if (fileWrapper != null && fileWrapper.getFile() != null) {
          myProjectLocation.setText(fileWrapper.getFile().getAbsolutePath());
        }
      }
    });
    myProjectLocation.getTextField().addFocusListener(this);
    myProjectLocation.getTextField().getDocument().addDocumentListener(this);
    if (myTemplateState.myHidden.contains(ATTR_PROJECT_LOCATION)) {
      myProjectLocation.setVisible(false);
      myProjectLocationLabel.setVisible(false);
    }
    if (myTemplateState.myHidden.contains(ATTR_IS_LIBRARY_MODULE)) {
      myLibraryCheckBox.setVisible(false);
    }
    if (myTemplateState.myHidden.contains(ATTR_MODULE_NAME)) {
      myModuleName.setVisible(false);
      myModuleNameLabel.setVisible(false);
    }
    if (myTemplateState.myHidden.contains(ATTR_APP_TITLE)) {
      myAppNameLabel.setVisible(false);
      myAppName.setVisible(false);
    }
  }

  private void registerUiElements() {
    TemplateMetadata metadata = myTemplateState.getTemplateMetadata();
    if (metadata != null) {
      Parameter param = metadata.getParameter(ATTR_BASE_THEME);
      if (param != null && param.element != null) {
        populateComboBox(myTheme, param);
        register(ATTR_BASE_THEME, myTheme);
      }
    }

    register(ATTR_MODULE_NAME, myModuleName);
    register(ATTR_PROJECT_LOCATION, myProjectLocation);
    register(ATTR_APP_TITLE, myAppName);
    register(ATTR_PACKAGE_NAME, myPackageName);
    register(ATTR_MIN_API, myMinSdk);
    register(ATTR_TARGET_API, myTargetSdk);
    register(ATTR_BUILD_API, myCompileWith);
    register(ATTR_CREATE_ACTIVITY, myCreateActivityCheckBox);
    register(ATTR_CREATE_ICONS, myCreateCustomLauncherIconCheckBox);
    register(ATTR_IS_LIBRARY_MODULE, myLibraryCheckBox);
    register(ATTR_FRAGMENTS_EXTRA, myFragmentCheckBox);
    register(ATTR_NAVIGATION_DRAWER_EXTRA, myNavigationDrawerCheckBox);
    register(ATTR_ACTION_BAR_EXTRA, myActionBarCheckBox);
    register(ATTR_GRID_LAYOUT_EXTRA, myGridLayoutCheckBox);
    if (mySourceCombo.isVisible()) {
      register(ATTR_JAVA_VERSION, mySourceCombo);
    }
  }

  @Override
  public void refreshUiFromParameters() {
    // It's easier to just re-register the UI elements instead of trying to set their values manually. Not all of the elements have
    // parameters in the template, and the super refreshUiFromParameters won't touch those elements.
    registerUiElements();
    super.refreshUiFromParameters();
  }

  @Override
  @NotNull
  public JComponent getComponent() {
    return myPanel;
  }

  @Override
  public void updateStep() {
    if (!myInitialized) {
      myInitialized = true;
      initialize();
    }
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myAppName;
  }

  public void setModuleName(String name) {
    myModuleName.setText(name);
    myTemplateState.put(ATTR_MODULE_NAME, name);
    myTemplateState.myModified.add(ATTR_MODULE_NAME);
    validate();
  }

  @NotNull
  private IAndroidTarget[] getCompilationTargets() {
    SdkManager sdkManager = getSdkManager();
    if (sdkManager == null) {
      return new IAndroidTarget[0];
    }
    IAndroidTarget[] targets = sdkManager.getTargets();
    List<IAndroidTarget> list = new ArrayList<IAndroidTarget>();

    for (IAndroidTarget target : targets) {
      if (target.isPlatform() == false &&
          (target.getOptionalLibraries() == null ||
           target.getOptionalLibraries().length == 0)) {
        continue;
      }
      list.add(target);
    }

    return list.toArray(new IAndroidTarget[list.size()]);
  }

  @Override
  @Nullable
  public String getHelpText(@NotNull String param) {
    if (param.equals(ATTR_MODULE_NAME)) {
      return "This module name is used only by the IDE. It can typically be the same as the application name.";
    } else if (param.equals(ATTR_APP_TITLE)) {
      return "The application name is shown in the Play store, as well as in the Manage Applications list in Settings.";
    } else if (param.equals(ATTR_PACKAGE_NAME)) {
      return "The package name must be a unique identifier for your application.\n It is typically not shown to users, " +
             "but it <b>must</b> stay the same for the lifetime of your application; it is how multiple versions of the same application " +
             "are considered the \"same app\".\nThis is typically the reverse domain name of your organization plus one or more " +
             "application identifiers, and it must be a valid Java package name.";
    } else if (param.equals(ATTR_MIN_API)) {
      return "Choose the lowest version of Android that your application will support. Lower API levels target more devices, " +
             "but means fewer features are available. By targeting API 8 and later, you reach approximately 95% of the market.";
    } else if (param.equals(ATTR_TARGET_API)) {
      return "Choose the highest API level that the application is known to work with. This attribute informs the system that you have " +
             "tested against the target version and the system should not enable any compatibility behaviors to maintain your app's " +
             "forward-compatibility with the target version. The application is still able to run on older versions (down to " +
             "minSdkVersion). Your application may look dated if you are not targeting the current version.";
    } else if (param.equals(ATTR_BUILD_API)) {
      return "Choose a target API to compile your code against, from your installed SDKs. This is typically the most recent version, " +
             "or the first version that supports all the APIs you want to directly access without reflection.";
    } else if (param.equals(ATTR_BASE_THEME)) {
      return "Choose the base theme to use for the application";
    } else if (param.equals(ATTR_FRAGMENTS_EXTRA)) {
      return "Select this box if you plan to use Fragments and will need the Support Library.";
    } else if (param.equals(ATTR_ACTION_BAR_EXTRA)) {
      return "Select this box if you plan to use the Action Bar and will need the AppCompat Library.";
    } else if (param.equals(ATTR_GRID_LAYOUT_EXTRA)) {
      return "Select this box if you plan to use the new GridLayout and will need the GridLayout Support Library.";
    } else if (param.equals(ATTR_NAVIGATION_DRAWER_EXTRA)) {
      return "Select this box if you plan to use the Navigation Drawer and will need the Support Library.";
    } else {
      return null;
    }
  }

  @Override
  public void onStepLeaving() {
    ((NewModuleWizardState)myTemplateState).updateParameters();
  }

  @Override
  protected JLabel getDescription() {
    return myDescription;
  }

  @Override
  protected JLabel getError() {
    return myError;
  }

  @Override
  protected void deriveValues() {
    updateDerivedValue(ATTR_MODULE_NAME, myModuleName, new Callable<String>() {
      @Override
      public String call() {
        return computeModuleName();
      }
    });
    updateDerivedValue(ATTR_PACKAGE_NAME, myPackageName, new Callable<String>() {
      @Override
      public String call() {
        return computePackageName();
      }
    });
    if (!myTemplateState.myHidden.contains(ATTR_PROJECT_LOCATION)) {
      updateDerivedValue(ATTR_PROJECT_LOCATION, myProjectLocation.getTextField(), new Callable<String>() {
        @Override
        public String call() {
          return computeProjectLocation();
        }
      });
    }
    if (!myInitializedPackageNameText) {
      myInitializedPackageNameText = true;
      if ((myTemplateState.getString(ATTR_PACKAGE_NAME)).startsWith(SAMPLE_PACKAGE_PREFIX)) {
        int length = SAMPLE_PACKAGE_PREFIX.length();
        if (SAMPLE_PACKAGE_PREFIX.endsWith(".")) {
          length--;
        }
        myPackageName.select(0, length);
      }
    }
  }

  @Override
  public boolean validate() {
    ((NewModuleWizardState)myTemplateState).updateParameters();

    if (!super.validate()) {
      return false;
    }

    AndroidTargetComboBoxItem item = (AndroidTargetComboBoxItem)myMinSdk.getSelectedItem();
    if (item != null) {
      myTemplateState.put(ATTR_MIN_API_LEVEL, item.apiLevel);
    }

    setErrorHtml("");

    if (!myTemplateState.myHidden.contains(ATTR_APP_TITLE)) {
      String applicationName = myTemplateState.getString(ATTR_APP_TITLE);
      if (applicationName == null || applicationName.isEmpty()) {
        setErrorHtml("Enter an application name (shown in launcher)");
        return false;
      }
      if (Character.isLowerCase(applicationName.charAt(0))) {
        setErrorHtml("The application name for most apps begins with an uppercase letter");
      }
    }
    String packageName = myTemplateState.getString(ATTR_PACKAGE_NAME);
    if (packageName.startsWith(SAMPLE_PACKAGE_PREFIX)) {
      setErrorHtml(String.format("The prefix '%1$s' is meant as a placeholder and should " +
                                    "not be used", SAMPLE_PACKAGE_PREFIX));
    }

    String moduleName = myTemplateState.getString(ATTR_MODULE_NAME);
    if (moduleName == null || moduleName.isEmpty()) {
      setErrorHtml("Please specify a module name.");
      return false;
    } else if (!isValidModuleName(moduleName)) {
      setErrorHtml("Invalid module name.");
      return false;
    }

    Integer minSdk = (Integer)myTemplateState.get(ATTR_MIN_API);
    if (minSdk == null) {
      setErrorHtml("Select a minimum SDK version");
      return false;
    }
    // TODO: Properly handle preview versions
    int minLevel = (Integer)myTemplateState.get(ATTR_MIN_API_LEVEL);
    int buildLevel = (Integer)myTemplateState.get(ATTR_BUILD_API);
    int targetLevel = (Integer)myTemplateState.get(ATTR_TARGET_API);
    if (targetLevel < minLevel) {
      setErrorHtml("The target SDK version should be at least as high as the minimum SDK version");
      return false;
    }
    if (buildLevel < minLevel) {
      setErrorHtml("The build target version should be at least as high as the minimum SDK version");
      return false;
    }

    if (myTemplateState.hasAttr(ATTR_JAVA_VERSION)) {
      String sourceVersion = myTemplateState.getString(ATTR_JAVA_VERSION);
      if ("1.7".equals(sourceVersion)) {
        if (buildLevel < 19) {
          setErrorHtml("Using Java language level 7 requires compiling with API 19: Android 4.4 (KitKat)");
          return false;
        }
        if (minLevel < 19) {
          setErrorHtml("Note: With minSdkVersion less than 19, you cannot use try-with-resources, but other Java 7 language " +
                       "features are fine");
        }
      }
    }

    toggleVisibleOnApi(myFragmentCheckBox, 10, minLevel);
    toggleVisibleOnApi(myNavigationDrawerCheckBox, 10, minLevel);
    toggleVisibleOnApi(myActionBarCheckBox, 10, minLevel);
    toggleVisibleOnApi(myGridLayoutCheckBox, 13, minLevel);

    if (!myTemplateState.myHidden.contains(ATTR_PROJECT_LOCATION)) {
      String projectLocation = myTemplateState.getString(ATTR_PROJECT_LOCATION);
      if (projectLocation == null || projectLocation.isEmpty()) {
        setErrorHtml("Please specify a project location");
        return false;
      }
      File file = new File(projectLocation);
      if (file.exists()) {
        setErrorHtml("There must not already be a file or directory at the project location");
        return false;
      }
      if (file.getParent() == null) {
        setErrorHtml("The project location can not be at the filesystem root");
        return false;
      }
      if (file.getParentFile().exists() && !file.getParentFile().isDirectory()) {
        setErrorHtml("The project location's parent directory must be a directory, not a plain file");
        return false;
      }
    } else if (!isUniqueModuleName(moduleName)) {
      // In this state, we've got a pre-existing project. Let's make sure we're not trying to overwrite an existing module
      setErrorHtml(String.format(Locale.getDefault(), "Module %1$s already exists", moduleName));
    }

    return true;
  }

  /**
   * Shows or hides a checkbox based on a given API level and the max API level for which it should be shown
   * @param component The component to hide
   * @param maxApiLevel the maximum API level for which the given component should be visible
   * @param apiLevel the selected API level
   */
  private void toggleVisibleOnApi(JCheckBox component, int maxApiLevel, int apiLevel) {
    component.setVisible(apiLevel <= maxApiLevel);
    if (!component.isVisible()) {
      component.setSelected(false);
    }
  }

  @NotNull
  private String computePackageName() {
    String moduleName = myTemplateState.getString(ATTR_MODULE_NAME);
    if (moduleName != null && !moduleName.isEmpty()) {
      moduleName = moduleName.replaceAll("[^a-zA-Z0-9_\\-]", "");
      moduleName = moduleName.toLowerCase();
      return SAMPLE_PACKAGE_PREFIX + moduleName;
    } else {
      return "";
    }
  }

  @NotNull
  private String computeModuleName() {
    String name = myTemplateState.getBoolean(ATTR_IS_LIBRARY_MODULE) ? LIB_NAME : APP_NAME;
    int i = 2;
    while (!isUniqueModuleName(name)) {
      name = name + Integer.toString(i);
    }
    return name;
  }

  private static boolean isValidModuleName(@NotNull String moduleName) {
    if (!moduleName.replaceAll(INVALID_FILENAME_CHARS, "").equals(moduleName)) {
      return false;
    }
    for (String s : Splitter.on('.').split(moduleName)) {
      if (INVALID_MSFT_FILENAMES.contains(s.toLowerCase())) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private boolean isUniqueModuleName(@NotNull String moduleName) {
    if (myProject == null) {
      return true;
    }
    // Check our modules
    ModuleManager moduleManager = ModuleManager.getInstance(myProject);
    for (Module m : moduleManager.getModules()) {
      if (m.getName().equals(moduleName)) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private String computeProjectLocation() {
    String name = myTemplateState.getString(ATTR_APP_TITLE);
    if (name == null) {
      name = "";
    }
    name = name.replaceAll("[^a-zA-Z0-9_\\-.]", "");
    return new File(NewProjectWizardState.getProjectFileDirectory(), name)
      .getAbsolutePath();
  }

  public void setWizardContext(WizardContext wizardContext) {
    myWizardContext = wizardContext;
  }

  public static boolean isJdk7Supported(@Nullable SdkManager sdkManager) {
    if (sdkManager != null) {
      LocalPkgInfo info = sdkManager.getLocalSdk().getPkgInfo(LocalSdk.PKG_PLATFORM_TOOLS);
      if (info != null && info.hasFullRevision()) {
        FullRevision fullRevision = info.getFullRevision();
        assert fullRevision != null;
        if (fullRevision.getMajor() >= 19) {
          JavaSdk jdk = JavaSdk.getInstance();
          Sdk sdk = ProjectJdkTable.getInstance().findMostRecentSdkOfType(jdk);
          if (sdk != null) {
            JavaSdkVersion version = jdk.getVersion(sdk);
            if (version != null && version.isAtLeast(JavaSdkVersion.JDK_1_7)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @Nullable
  private SdkManager getSdkManager() {
    SdkManager sdkManager = myWizardContext != null
                            ? TemplateWizardModuleBuilder.getSdkManager(myWizardContext.getProjectJdk())
                            : null;
    if (sdkManager == null) {
      sdkManager = AndroidSdkUtils.tryToChooseAndroidSdk();
    }
    return sdkManager;
  }

  public static String languageLevelToString(LanguageLevel level) { // Performs the reverse of LanguageLevel.parse()
    switch (level) {
      case JDK_1_5: return "1.5";
      case JDK_1_6: return "1.6";
      case JDK_1_7: return "1.7";
      default: return level.name().substring(4).replace('_','.'); // JDK_1_2 => 1.2
    }
  }

  public static class SourceLevelComboBoxItem extends ComboBoxItem {
    public final LanguageLevel level;

    public SourceLevelComboBoxItem(@NotNull LanguageLevel level) {
      super(languageLevelToString(level), level.getPresentableText(), 1, 1);
      this.level = level;
    }

    @Override
    public String toString() {
      return level.getPresentableText();
    }
  }

  public static class AndroidTargetComboBoxItem extends ComboBoxItem {
    public int apiLevel = -1;
    public IAndroidTarget target = null;

    public AndroidTargetComboBoxItem(@NotNull String label, int apiLevel) {
      super(apiLevel, label, 1, 1);
      this.apiLevel = apiLevel;
    }

    public AndroidTargetComboBoxItem(@NotNull IAndroidTarget target) {
      super(getId(target), getLabel(target), 1, 1);
      this.target = target;
      apiLevel = target.getVersion().getApiLevel();
    }

    @NotNull
    private static String getLabel(@NotNull IAndroidTarget target) {
      if (target.isPlatform()
          && target.getVersion().getApiLevel() <= SdkVersionInfo.HIGHEST_KNOWN_API) {
        return SdkVersionInfo.getAndroidName(target.getVersion().getApiLevel());
      } else {
        return TemplateUtils.getTargetLabel(target);
      }
    }

    @NotNull
    private static Object getId(@NotNull IAndroidTarget target) {
      if (target.getVersion().isPreview()) {
        return target.getVersion().getCodename();
      } else {
        return target.getVersion().getApiLevel();
      }
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
