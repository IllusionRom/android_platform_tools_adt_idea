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

import com.android.ide.common.rendering.HardwareConfigHelper;
import com.android.ide.common.rendering.LayoutLibrary;
import com.android.ide.common.rendering.RenderSecurityManager;
import com.android.ide.common.rendering.api.*;
import com.android.ide.common.rendering.api.SessionParams.RenderingMode;
import com.android.ide.common.resources.ResourceResolver;
import com.android.ide.common.resources.configuration.LayoutDirectionQualifier;
import com.android.resources.LayoutDirection;
import com.android.resources.ResourceFolderType;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.devices.Device;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.configurations.RenderContext;
import com.android.tools.idea.gradle.AndroidModuleInfo;
import com.android.tools.idea.rendering.multi.RenderPreviewMode;
import com.google.common.collect.Maps;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.maven.AndroidMavenUtil;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.sdk.AndroidSdkAdditionalData;
import org.jetbrains.android.sdk.AndroidSdkType;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.android.uipreview.RenderingException;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.xmlpull.v1.XmlPullParserException;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.android.SdkConstants.*;
import static com.intellij.lang.annotation.HighlightSeverity.ERROR;

/**
 * The {@link RenderService} provides rendering and layout information for
 * Android layouts. This is a wrapper around the layout library.
 */
public class RenderService implements IImageFactory {
  private static final Logger LOG = Logger.getInstance("#com.android.tools.idea.rendering.RenderService");

  @NotNull
  private final Module myModule;

  @NotNull
  private final XmlFile myPsiFile;

  @NotNull
  private final RenderLogger myLogger;

  @NotNull
  private final ProjectCallback myProjectCallback;

  private final int myMinSdkVersion;

  private final int myTargetSdkVersion;

  @NotNull
  private final LayoutLibrary myLayoutLib;

  @NotNull
  private final HardwareConfigHelper myHardwareConfigHelper;

  @Nullable
  private IncludeReference myIncludedWithin;

  @NotNull
  private RenderingMode myRenderingMode = RenderingMode.NORMAL;

  @Nullable
  private Integer myOverrideBgColor;

  private boolean myShowDecorations = true;

  @NotNull
  private final Configuration myConfiguration;

  private long myTimeout;

  @Nullable
  private Set<XmlTag> myExpandNodes;

  @Nullable
  private RenderContext myRenderContext;

  @NotNull
  private final Locale myLocale;

  private final Object myCredential = new Object();

  /**
   * Creates a new {@link RenderService} associated with the given editor.
   *
   * @return a {@link RenderService} which can perform rendering services
   */
  @Nullable
  public static RenderService create(@NotNull final AndroidFacet facet,
                                     @NotNull final Module module,
                                     @NotNull final PsiFile psiFile,
                                     @NotNull final Configuration configuration,
                                     @NotNull final RenderLogger logger,
                                     @Nullable final RenderContext renderContext) {

    Project project = module.getProject();
    AndroidPlatform platform = getPlatform(module);
    if (platform == null) {
      if (!AndroidMavenUtil.isMavenizedModule(module)) {
        RenderProblem.Html message = RenderProblem.create(ERROR);
        logger.addMessage(message);
        message.getHtmlBuilder().addLink("No Android SDK found. Please ", "configure", " an Android SDK.",
                                         logger.getLinkManager().createRunnableLink(new Runnable() {
          @Override
          public void run() {
            AndroidSdkUtils.openModuleDependenciesConfigurable(module);
          }
        }));
      }
      else {
        String message = AndroidBundle.message("android.maven.cannot.parse.android.sdk.error", module.getName());
        logger.addMessage(RenderProblem.createPlain(ERROR, message));
      }
      return null;
    }

    IAndroidTarget target = configuration.getTarget();
    if (target == null) {
      logger.addMessage(RenderProblem.createPlain(ERROR, "No render target was chosen"));
      return null;
    }

    LayoutLibrary layoutLib;
    try {
      layoutLib = platform.getSdkData().getTargetData(target).getLayoutLibrary(project);
      if (layoutLib == null) {
        String message = AndroidBundle.message("android.layout.preview.cannot.load.library.error");
        logger.addMessage(RenderProblem.createPlain(ERROR, message));
        return null;
      }
    }
    catch (RenderingException e) {
      String message = e.getPresentableMessage();
      message = message != null ? message : AndroidBundle.message("android.layout.preview.default.error.message");
      logger.addMessage(RenderProblem.createPlain(ERROR, message, module.getProject(), logger.getLinkManager(), e));
      return null;
    }
    catch (IOException e) {
      final String message = e.getMessage();
      logger.error(null, "I/O error: " + (message != null ? ": " + message : ""), e);
      return null;
    }

    RenderService service = new RenderService(facet, module, psiFile, configuration, logger, layoutLib);
    if (renderContext != null) {
      service.setRenderContext(renderContext);
    }

    return service;
  }

  /**
   * Use the {@link #create} factory instead
   */
  private RenderService(@NotNull AndroidFacet facet,
                        @NotNull Module module,
                        @NotNull PsiFile psiFile,
                        @NotNull Configuration configuration,
                        @NotNull RenderLogger logger,
                        @NotNull LayoutLibrary layoutLib) {
    myModule = module;
    myLogger = logger;
    myLogger.setCredential(myCredential);
    if (!(psiFile instanceof XmlFile)) {
      throw new IllegalArgumentException("Can only render XML files: " + psiFile.getClass().getName());
    }
    myPsiFile = (XmlFile)psiFile;
    myConfiguration = configuration;

    Device device = configuration.getDevice();
    assert device != null; // Should only attempt render with configuration that has device
    myHardwareConfigHelper = new HardwareConfigHelper(device);

    myHardwareConfigHelper.setOrientation(configuration.getFullConfig().getScreenOrientationQualifier().getValue());
    myLayoutLib = layoutLib;
    LocalResourceRepository appResources = AppResourceRepository.getAppResources(facet, true);
    myProjectCallback = new ProjectCallback(myLayoutLib, appResources, myModule, facet, myLogger, myCredential);
    myProjectCallback.loadAndParseRClass();
    AndroidModuleInfo moduleInfo = AndroidModuleInfo.get(facet);
    myMinSdkVersion = moduleInfo.getMinSdkVersion();
    myTargetSdkVersion = moduleInfo.getTargetSdkVersion();
    myLocale = configuration.getLocale();
  }

  @Nullable
  public AndroidPlatform getPlatform() {
    return getPlatform(myModule);
  }

  @Nullable
  private static AndroidPlatform getPlatform(@NotNull Module module) {
    Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    if (sdk == null || !(sdk.getSdkType() instanceof AndroidSdkType)) {
      return null;
    }
    AndroidSdkAdditionalData data = (AndroidSdkAdditionalData)sdk.getSdkAdditionalData();
    if (data == null) {
      return null;
    }
    return data.getAndroidPlatform();
  }

  /**
   * Returns the {@link ResourceResolver} for this editor
   *
   * @return the resolver used to resolve resources for the current configuration of
   *         this editor, or null
   */
  @Nullable
  public ResourceResolver getResourceResolver() {
    return myConfiguration.getResourceResolver();
  }

  @NotNull
  public Configuration getConfiguration() {
    return myConfiguration;
  }

  @Nullable
  public ResourceFolderType getFolderType() {
    return ResourceHelper.getFolderType(myPsiFile);
  }

  @NotNull
  public Module getModule() {
    return myModule;
  }

  @NotNull
  public RenderLogger getLogger() {
    return myLogger;
  }

  @Nullable
  public Set<XmlTag> getExpandNodes() {
    return myExpandNodes;
  }

  @NotNull
  public HardwareConfigHelper getHardwareConfigHelper() {
    return myHardwareConfigHelper;
  }

  public boolean getShowDecorations() {
    return myShowDecorations;
  }

  public void dispose() {
    myProjectCallback.setLogger(null);
    myProjectCallback.setResourceResolver(null);
  }

  /**
   * Overrides the width and height to be used during rendering (which might be adjusted if
   * the {@link #setRenderingMode(com.android.ide.common.rendering.api.SessionParams.RenderingMode)} is
   * {@link com.android.ide.common.rendering.api.SessionParams.RenderingMode#FULL_EXPAND}.
   * <p/>
   * A value of -1 will make the rendering use the normal width and height coming from the
   * {@link Configuration#getDevice()} object.
   *
   * @param overrideRenderWidth  the width in pixels of the layout to be rendered
   * @param overrideRenderHeight the height in pixels of the layout to be rendered
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setOverrideRenderSize(int overrideRenderWidth, int overrideRenderHeight) {
    myHardwareConfigHelper.setOverrideRenderSize(overrideRenderWidth, overrideRenderHeight);
    return this;
  }

  /**
   * Sets the max width and height to be used during rendering (which might be adjusted if
   * the {@link #setRenderingMode(com.android.ide.common.rendering.api.SessionParams.RenderingMode)} is
   * {@link com.android.ide.common.rendering.api.SessionParams.RenderingMode#FULL_EXPAND}.
   * <p/>
   * A value of -1 will make the rendering use the normal width and height coming from the
   * {@link Configuration#getDevice()} object.
   *
   * @param maxRenderWidth  the max width in pixels of the layout to be rendered
   * @param maxRenderHeight the max height in pixels of the layout to be rendered
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setMaxRenderSize(int maxRenderWidth, int maxRenderHeight) {
    myHardwareConfigHelper.setMaxRenderSize(maxRenderWidth, maxRenderHeight);
    return this;
  }

  /**
   * Sets the {@link com.android.ide.common.rendering.api.SessionParams.RenderingMode} to be used during rendering. If none is specified,
   * the default is {@link com.android.ide.common.rendering.api.SessionParams.RenderingMode#NORMAL}.
   *
   * @param renderingMode the rendering mode to be used
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setRenderingMode(@NotNull RenderingMode renderingMode) {
    myRenderingMode = renderingMode;
    return this;
  }

  /** Returns the {@link RenderingMode} to be used */
  @NotNull
  public RenderingMode getRenderingMode() {
    return myRenderingMode;
  }

  public RenderService setTimeout(long timeout) {
    myTimeout = timeout;
    return this;
  }

  /**
   * Sets the overriding background color to be used, if any. The color should be a
   * bitmask of AARRGGBB. The default is null.
   *
   * @param overrideBgColor the overriding background color to be used in the rendering,
   *                        in the form of a AARRGGBB bitmask, or null to use no custom background.
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setOverrideBgColor(Integer overrideBgColor) {
    myOverrideBgColor = overrideBgColor;
    return this;
  }

  /**
   * Sets whether the rendering should include decorations such as a system bar, an
   * application bar etc depending on the SDK target and theme. The default is true.
   *
   * @param showDecorations true if the rendering should include system bars etc.
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setDecorations(boolean showDecorations) {
    myShowDecorations = showDecorations;
    return this;
  }

  /**
   * Gets the context for the usage of this {@link RenderService}, which can
   * control for example how {@code <fragment/>} tags are processed when missing
   * preview data
   */
  @Nullable
  public RenderContext getRenderContext() {
    return myRenderContext;
  }

  /**
   * Sets the context for the usage of this {@link RenderService}, which can
   * control for example how {@code <fragment/>} tags are processed when missing
   * preview data
   *
   * @param renderContext the render context
   * @return this, for constructor chaining
   */
  @Nullable
  public RenderService setRenderContext(@Nullable RenderContext renderContext) {
    myRenderContext = renderContext;
    return this;
  }

  /**
   * Sets the nodes to expand during rendering. These will be padded with approximately
   * 20 pixels. The default is null.
   *
   * @param nodesToExpand the nodes to be expanded
   * @return this (such that chains of setters can be stringed together)
   */
  @NotNull
  public RenderService setNodesToExpand(@Nullable Set<XmlTag> nodesToExpand) {
    myExpandNodes = nodesToExpand;
    return this;
  }

  /**
   * Sets the {@link IncludeReference} to an outer layout that this layout should be rendered
   * within. The outer layout <b>must</b> contain an include tag which points to this
   * layout. The default is null.
   *
   * @param includedWithin a reference to an outer layout to render this layout within
   * @return this (such that chains of setters can be stringed together)
   */
  public RenderService setIncludedWithin(IncludeReference includedWithin) {
    myIncludedWithin = includedWithin;
    return this;
  }

  /**
   * Renders the model and returns the result as a {@link com.android.ide.common.rendering.api.RenderSession}.
   *
   * @return the {@link com.android.ide.common.rendering.api.RenderSession} resulting from rendering the current model
   */
  @Nullable
  private RenderSession createRenderSession() {
    ResourceResolver resolver = getResourceResolver();
    if (resolver == null) {
      // Abort the rendering if the resources are not found.
      return null;
    }

    ILayoutPullParser modelParser = LayoutPullParserFactory.create(this);
    ILayoutPullParser topParser = modelParser;

    myProjectCallback.reset();

    // Code to support editing included layout
    if (myIncludedWithin != null) {
      // Outer layout name:
      String contextLayoutName = myIncludedWithin.getName();

      // Find the layout file.
      ResourceValue contextLayout = resolver.findResValue(LAYOUT_RESOURCE_PREFIX + contextLayoutName, false  /* forceFrameworkOnly*/);
      if (contextLayout != null) {
        File layoutFile = new File(contextLayout.getValue());
        if (layoutFile.isFile()) {
          try {
            // Get the name of the layout actually being edited, without the extension
            // as it's what IXmlPullParser.getParser(String) will receive.
            String queryLayoutName = ResourceHelper.getResourceName(myPsiFile);
            myProjectCallback.setLayoutParser(queryLayoutName, modelParser);
            topParser = LayoutFilePullParser.create(myProjectCallback, layoutFile);
          }
          catch (IOException e) {
            myLogger.error(null, String.format("Could not read layout file %1$s", layoutFile), e);
          }
          catch (XmlPullParserException e) {
            myLogger.error(null, String.format("XML parsing error: %1$s", e.getMessage()), e.getDetail() != null ? e.getDetail() : e);
          }
        }
      }
    }

    HardwareConfig hardwareConfig = myHardwareConfigHelper.getConfig();
    final SessionParams params =
      new SessionParams(topParser, myRenderingMode, myModule /* projectKey */, hardwareConfig, resolver, myProjectCallback,
                        myMinSdkVersion, myTargetSdkVersion, myLogger);

    // Request margin and baseline information.
    // TODO: Be smarter about setting this; start without it, and on the first request
    // for an extended view info, re-render in the same session, and then set a flag
    // which will cause this to create extended view info each time from then on in the
    // same session
    params.setExtendedViewInfoMode(true);

    ManifestInfo manifestInfo = ManifestInfo.get(myModule);

    LayoutDirectionQualifier qualifier = myConfiguration.getFullConfig().getLayoutDirectionQualifier();
    if (qualifier != null && qualifier.getValue() == LayoutDirection.RTL) {
      params.setRtlSupport(true);
      // We don't have a flag to force RTL regardless of locale, so just pick a RTL locale (note that
      // this is decoupled from resource lookup)
      params.setLocale("ur");
    } else {
      params.setLocale(myLocale.toLocaleId());
      try {
        params.setRtlSupport(manifestInfo.isRtlSupported());
      } catch (Exception e) {
        // ignore.
      }
    }
    if (!myShowDecorations) {
      params.setForceNoDecor();
    }
    else {
      try {
        params.setAppLabel(manifestInfo.getApplicationLabel());
        params.setAppIcon(manifestInfo.getApplicationIcon());
      }
      catch (Exception e) {
        // ignore.
      }
    }

    if (myOverrideBgColor != null) {
      params.setOverrideBgColor(myOverrideBgColor.intValue());
    } else if (requiresTransparency()) {
      params.setOverrideBgColor(0);
    }

    params.setImageFactory(this);

    if (myTimeout > 0) {
      params.setTimeout(myTimeout);
    }

    try {
      myProjectCallback.setLogger(myLogger);
      myProjectCallback.setResourceResolver(resolver);

      return ApplicationManager.getApplication().runReadAction(new Computable<RenderSession>() {
        @Nullable
        @Override
        public RenderSession compute() {
          RenderSecurityManager securityManager = createSecurityManager();
          securityManager.setActive(true, myCredential);

          try {
            int retries = 0;
            RenderSession session = null;
            while (retries < 10) {
              session = myLayoutLib.createSession(params);
              Result result = session.getResult();
              if (result.getStatus() != Result.Status.ERROR_TIMEOUT) {
                // Sometimes happens at startup; treat it as a timeout; typically a retry fixes it
                if (!result.isSuccess() && "The main Looper has already been prepared.".equals(result.getErrorMessage())) {
                  retries++;
                  continue;
                }
                break;
              }
              retries++;
            }

            return session;
          }
          finally {
            securityManager.dispose(myCredential);
          }
        }
      });
    }
    catch (RuntimeException t) {
      // Exceptions from the bridge
      myLogger.error(null, t.getLocalizedMessage(), t, null);
      throw t;
    }
  }

  private RenderSecurityManager createSecurityManager() {
    String projectPath = null;
    String sdkPath = null;
    if (RenderSecurityManager.RESTRICT_READS) {
      projectPath = myModule.getProject().getBasePath();
      AndroidPlatform platform = getPlatform();
      if (platform != null) {
        sdkPath = platform.getSdkData().getSdkManager().getLocation();
      }
    }

    @SuppressWarnings("ConstantConditions")
    RenderSecurityManager securityManager = new RenderSecurityManager(sdkPath, projectPath);
    securityManager.setLogger(new LogWrapper(RenderLogger.LOG));

    return securityManager;
  }

  /** Returns true if the given file can be rendered */
  public static boolean canRender(@Nullable PsiFile file) {
    return LayoutPullParserFactory.isSupported(file);
  }

  private static final Object RENDERING_LOCK = new Object();

  @Nullable
  public RenderResult render() {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    synchronized (RENDERING_LOCK) {
      RenderResult renderResult;
      try {
        RenderSession session = createRenderSession();
        renderResult = new RenderResult(this, session, myPsiFile, myLogger);
        if (session != null) {
          addDiagnostics(session);
        }
      } catch (final Exception e) {
        String message = e.getMessage();
        if (message == null) {
          message = e.toString();
        }
        myLogger.addMessage(RenderProblem.createPlain(ERROR, message, myModule.getProject(), myLogger.getLinkManager(), e));
        renderResult = new RenderResult(this, null, myPsiFile, myLogger);
      }

      return renderResult;
    }
  }

  @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
  private void addDiagnostics(RenderSession session) {
    Result r = session.getResult();
    if (!myLogger.hasProblems() && !r.isSuccess()) {
      if (r.getException() != null || r.getErrorMessage() != null) {
        myLogger.error(null, r.getErrorMessage(), r.getException(), null);
      } else if (r.getStatus() == Result.Status.ERROR_TIMEOUT) {
        myLogger.error(null, "Rendering timed out.", null);
      } else {
        myLogger.error(null, "Unknown render problem: " + r.getStatus(), null);
      }
    }
  }

  /**
   * Renders the given resource value (which should refer to a drawable) and returns it
   * as an image
   *
   * @param drawableResourceValue the drawable resource value to be rendered, or null
   * @return the image, or null if something went wrong
   */
  @Nullable
  public BufferedImage renderDrawable(ResourceValue drawableResourceValue) {
    if (drawableResourceValue == null) {
      return null;
    }

    HardwareConfig hardwareConfig = myHardwareConfigHelper.getConfig();

    DrawableParams params =
      new DrawableParams(drawableResourceValue, myModule, hardwareConfig, getResourceResolver(), myProjectCallback, myMinSdkVersion,
                         myTargetSdkVersion, myLogger);
    params.setForceNoDecor();
    Result result = myLayoutLib.renderDrawable(params);
    if (result != null && result.isSuccess()) {
      Object data = result.getData();
      if (data instanceof BufferedImage) {
        return (BufferedImage)data;
      }
    }

    return null;
  }

//    private static final String DEFAULT_APP_LABEL = "Android Application";
//    private static String getAppLabelToShow(final AndroidFacet facet) {
//        return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
//            @Override
//            public String compute() {
//                final Manifest manifest = facet.getManifest();
//                if (manifest != null) {
//                    final Application application = manifest.getApplication();
//                    if (application != null) {
//                        final String label = application.getLabel().getStringValue();
//                        if (label != null) {
//                            return label;
//                        }
//                    }
//                }
//                return DEFAULT_APP_LABEL;
//            }
//        });
//    }

  @NotNull
  public ProjectCallback getProjectCallback() {
    return myProjectCallback;
  }

  @NotNull
  public XmlFile getPsiFile() {
    return myPsiFile;
  }

  public static boolean supportsCapability(@NotNull final Module module, @NotNull IAndroidTarget target, @NotNull Capability capability) {
    Project project = module.getProject();
    AndroidPlatform platform = getPlatform(module);
    if (platform != null) {
      try {
        LayoutLibrary library = platform.getSdkData().getTargetData(target).getLayoutLibrary(project);
        if (library != null) {
          return library.supports(capability);
        }
      }
      catch (RenderingException e) {
        // Ignore: if service can't be found, that capability isn't available
      }
      catch (IOException e) {
        // Ditto
      }
    }
    return false;
  }

  @Nullable
  public static LayoutLibrary getLayoutLibrary(@Nullable final Module module, @Nullable IAndroidTarget target) {
    if (module == null || target == null) {
      return null;
    }
    Project project = module.getProject();
    AndroidPlatform platform = getPlatform(module);
    if (platform != null) {
      try {
        return platform.getSdkData().getTargetData(target).getLayoutLibrary(project);
      }
      catch (RenderingException e) {
        // Ignore.
      }
      catch (IOException e) {
        // Ditto
      }
    }
    return null;
  }

  /** Returns true if this service can render a non-rectangular shape */
  public boolean isNonRectangular() {
    // Drawable images can have non-rectangular shapes; we need to ensure that we blank out the
    // background with full alpha
    return getFolderType() == ResourceFolderType.DRAWABLE;
  }

  /** Returns true if this service requires rendering into a transparent/alpha channel image */
  public boolean requiresTransparency() {
    // Drawable images can have non-rectangular shapes; we need to ensure that we blank out the
    // background with full alpha
    return isNonRectangular();
  }

  // ---- Implements IImageFactory ----

  /** TODO: reuse image across subsequent render operations if the size is the same */
  @SuppressWarnings("UndesirableClassUsage") // Don't need Retina for layoutlib rendering; will scale down anyway
  @Override
  public BufferedImage getImage(int width, int height) {
    return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
  }

  /**
   * Notifies the render service that it is being used in design mode for this layout.
   * For example, that means that when rendering a ScrollView, it should measure the necessary
   * vertical space, and size the layout according to the needs rather than the available
   * device size.
   * <p>
   * We don't want to do this when for example offering thumbnail previews of the various
   * layouts.
   *
   * @param rootTag the tag, if any
   */
  public void useDesignMode(@Nullable XmlTag rootTag) {
    if (rootTag != null) {
      // In multi configuration rendering, clip to screen bounds
      RenderPreviewMode currentMode = RenderPreviewMode.getCurrent();
      if (currentMode != RenderPreviewMode.NONE) {
        return;
      }
      String tagName = rootTag.getName();
      if (SCROLL_VIEW.equals(tagName)) {
        setRenderingMode(RenderingMode.V_SCROLL);
        setDecorations(false);
      } else if (HORIZONTAL_SCROLL_VIEW.equals(tagName)) {
        setRenderingMode(RenderingMode.H_SCROLL);
        setDecorations(false);
      }
    }
  }

  /**
   * Measure the children of the given parent element.
   *
   * @param parent the parent element whose children should be measured
   * @return a list of root view infos
   */
  @Nullable
  public List<ViewInfo> measure(Element parent) {
    ILayoutPullParser modelParser = new DomPullParser(parent);
    RenderSession session = measure(modelParser);
    if (session != null) {
      Result result = session.getResult();
      if (result != null && result.isSuccess()) {
        assert session.getRootViews().size() == 1;
        return session.getRootViews();
      }
    }

    return null;
  }

  /**
   * Measure the children of the given parent tag, applying the given filter to the
   * pull parser's attribute values.
   *
   * @param parent the parent tag to measure children for
   * @param filter the filter to apply to the attribute values
   * @return a map from the children of the parent to new bounds of the children
   */
  @Nullable
  public Map<XmlTag, ViewInfo> measureChildren(XmlTag parent, final AttributeFilter filter) {
    ILayoutPullParser modelParser = LayoutPsiPullParser.create(filter, parent, myLogger);
    Map<XmlTag, ViewInfo> map = Maps.newHashMap();
    RenderSession session = measure(modelParser);
    if (session != null) {
      Result result = session.getResult();
      if (result != null && result.isSuccess()) {
        assert session.getRootViews().size() == 1;
        ViewInfo root = session.getRootViews().get(0);
        List<ViewInfo> children = root.getChildren();
        for (ViewInfo info : children) {
          Object cookie = info.getCookie();
          if (cookie instanceof XmlTag) {
            map.put((XmlTag)cookie, info);
          }
        }
      }

      return map;
    }

    return null;
  }

  /**
   * Measure the given child in context, applying the given filter to the
   * pull parser's attribute values.
   *
   * @param tag the child to measure
   * @param filter the filter to apply to the attribute values
   * @return a view info, if found
   */
  @Nullable
  public ViewInfo measureChild(XmlTag tag, final AttributeFilter filter) {
    XmlTag parent = tag.getParentTag();
    if (parent != null) {
      Map<XmlTag, ViewInfo> map = measureChildren(parent, filter);
      if (map != null) {
        for (Map.Entry<XmlTag, ViewInfo> entry : map.entrySet()) {
          if (entry.getKey() == tag) {
            return entry.getValue();
          }
        }
      }
    }

    return null;
  }

  @Nullable
  private RenderSession measure(ILayoutPullParser parser) {
    ResourceResolver resolver = getResourceResolver();
    if (resolver == null) {
      // Abort the rendering if the resources are not found.
      return null;
    }

    myProjectCallback.reset();

    HardwareConfig hardwareConfig = myHardwareConfigHelper.getConfig();
    final SessionParams params = new SessionParams(
      parser,
      RenderingMode.FULL_EXPAND,
      myModule /* projectKey */,
      hardwareConfig,
      resolver,
      myProjectCallback,
      myMinSdkVersion,
      myTargetSdkVersion,
      myLogger);
    params.setLayoutOnly();
    params.setForceNoDecor();
    params.setExtendedViewInfoMode(true);
    params.setLocale(myLocale.toLocaleId());
    ManifestInfo manifestInfo = ManifestInfo.get(myModule);
    try {
      params.setRtlSupport(manifestInfo.isRtlSupported());
    } catch (Exception e) {
      // ignore.
    }

    try {
      myProjectCallback.setLogger(myLogger);
      myProjectCallback.setResourceResolver(resolver);

      return ApplicationManager.getApplication().runReadAction(new Computable<RenderSession>() {
        @Nullable
        @Override
        public RenderSession compute() {
          int retries = 0;
          while (retries < 10) {
            RenderSession session = myLayoutLib.createSession(params);
            Result result = session.getResult();
            if (result.getStatus() != Result.Status.ERROR_TIMEOUT) {
              // Sometimes happens at startup; treat it as a timeout; typically a retry fixes it
              if (!result.isSuccess() && "The main Looper has already been prepared.".equals(result.getErrorMessage())) {
                retries++;
                continue;
              }
              return session;
            }
            retries++;
          }

          return null;
        }
      });
    }
    catch (RuntimeException t) {
      // Exceptions from the bridge
      myLogger.error(null, t.getLocalizedMessage(), t, null);
      throw t;
    }
  }

  /**
   * The {@link AttributeFilter} allows a client of {@link #measureChildren} to modify the actual
   * XML values of the nodes being rendered, for example to force width and height values to
   * wrap_content when measuring preferred size.
   */
  public interface AttributeFilter {
    /**
     * Returns the attribute value for the given node and attribute name. This filter
     * allows a client to adjust the attribute values that a node presents to the
     * layout library.
     * <p/>
     * Returns "" to unset an attribute. Returns null to return the unfiltered value.
     *
     * @param node      the node for which the attribute value should be returned
     * @param namespace the attribute namespace
     * @param localName the attribute local name
     * @return an override value, or null to return the unfiltered value
     */
    @Nullable
    String getAttribute(@NotNull XmlTag node, @Nullable String namespace, @NotNull String localName);
  }
}
