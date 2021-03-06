/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.android.uipreview;

import com.android.ide.common.rendering.api.SessionParams;
import com.android.tools.idea.configurations.RenderContext;
import com.android.tools.idea.rendering.*;
import com.android.tools.idea.rendering.multi.RenderPreviewManager;
import com.android.tools.idea.rendering.multi.RenderPreviewMode;
import com.google.common.base.Objects;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLayeredPane;
import com.intellij.util.ui.AsyncProcessIcon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static com.android.tools.idea.rendering.RenderErrorPanel.SIZE_ERROR_PANEL_DYNAMICALLY;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidLayoutPreviewPanel extends JPanel implements Disposable {
  public static final Gray DESIGNER_BACKGROUND_COLOR = Gray._150;
  @SuppressWarnings("UseJBColor") // The designer canvas is not using light/dark themes; colors match Android theme rendering
  public static final Color SELECTION_BORDER_COLOR = new Color(0x00, 0x99, 0xFF, 255);
  @SuppressWarnings("UseJBColor")
  public static final Color SELECTION_FILL_COLOR = new Color(0x00, 0x99, 0xFF, 32);
  /** FileEditorProvider ID for the layout editor */
  public static final String ANDROID_DESIGNER_ID = "android-designer";
  private static final Integer LAYER_PROGRESS = JLayeredPane.POPUP_LAYER + 100;

  private RenderResult myRenderResult;

  private boolean myZoomToFit = true;

  private final List<ProgressIndicator> myProgressIndicators = new ArrayList<ProgressIndicator>();
  private final JComponent myImagePanel = new JComponent() {
  };

  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
  private MyProgressPanel myProgressPanel;
  private TextEditor myEditor;
  private List<RenderedView> mySelectedViews;
  private CaretModel myCaretModel;
  private RenderErrorPanel myErrorPanel;
  private int myErrorPanelHeight = -1;
  private CaretListener myCaretListener = new CaretListener() {
    @Override
    public void caretPositionChanged(CaretEvent e) {
      updateCaret();
    }
  };
  private RenderPreviewManager myPreviewManager;

  public AndroidLayoutPreviewPanel() {
    super(new VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, true));
    updateBackgroundColor();
    myImagePanel.setBackground(null);

    MyImagePanelWrapper previewPanel = new MyImagePanelWrapper();
    previewPanel.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent mouseEvent) {
        if (myRenderResult == null) {
          return;
        }

        selectViewAt(mouseEvent.getX(), mouseEvent.getY());
      }

      @Override
      public void mouseClicked(MouseEvent mouseEvent) {
        if (myRenderResult == null) {
          return;
        }

        if (mouseEvent.getClickCount() == 2) {
          // Double click: open in the UI editor
          switchToLayoutEditor();
        }
      }
    });

    add(previewPanel);

    myErrorPanel = new RenderErrorPanel();
    myErrorPanel.setVisible(false);
    previewPanel.add(myErrorPanel, JLayeredPane.POPUP_LAYER);
    myProgressPanel = new MyProgressPanel();
    previewPanel.add(myProgressPanel, LAYER_PROGRESS);
  }

  private void switchToLayoutEditor() {
    if (myEditor != null && myRenderResult != null && myRenderResult.getFile() != null) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          VirtualFile file = myRenderResult.getFile().getVirtualFile();
          if (file != null) {
            Project project = myEditor.getEditor().getProject();
            if (project != null) {
              FileEditorManager.getInstance(project).setSelectedEditor(file, ANDROID_DESIGNER_ID);
            }
          }
        }
      }, ModalityState.NON_MODAL);
    }
  }

  private void selectViewAt(int x1, int y1) {
    if (myEditor != null && myRenderResult.getImage() != null) {

      x1 -= myImagePanel.getX();
      y1 -= myImagePanel.getY();

      Point p = fromScreenToModel(x1, y1);
      if (p == null) {
        return;
      }
      int x = p.x;
      int y = p.y;

      RenderedViewHierarchy hierarchy = myRenderResult.getHierarchy();
      assert hierarchy != null; // because image != null
      RenderedView leaf = hierarchy.findLeafAt(x, y);

      // If you've clicked on for example a list item, the view you clicked
      // on may not correspond to a tag, it could be a designtime preview item,
      // so search upwards for the nearest surrounding tag
      while (leaf != null && leaf.tag == null) {
        leaf = leaf.getParent();
      }

      if (leaf != null) {
        int offset = leaf.tag.getTextOffset();
        if (offset != -1) {
          Editor editor = myEditor.getEditor();
          editor.getCaretModel().moveToOffset(offset);
          editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        }
      }
    }
  }

  /**
   * Computes the corresponding layoutlib point from a screen point (relative to the top left corner of the rendered image)
   */
  @Nullable
  private Point fromScreenToModel(int x, int y) {
    if (myRenderResult == null) {
      return null;
    }
    ScalableImage image = myRenderResult.getImage();
    if (image != null) {
      double zoomFactor = image.getScale();
      Rectangle imageBounds = image.getImageBounds();
      if (imageBounds != null) {
        x -= imageBounds.x;
        y -= imageBounds.y;
        double deviceFrameFactor = imageBounds.getWidth() / (double) image.getScaledWidth();
        zoomFactor *= deviceFrameFactor;
      }

      x /= zoomFactor;
      y /= zoomFactor;

      return new Point(x, y);
    }

    return null;
  }

  /**
   * Computes the corresponding screen coordinates (relative to the top left corner of the rendered image)
   * for a layoutlib rectangle.
   */
  @Nullable
  private Rectangle fromModelToScreen(int x, int y, int width, int height) {
    if (myRenderResult == null) {
      return null;
    }
    ScalableImage image = myRenderResult.getImage();
    if (image != null) {
      double zoomFactor = image.getScale();
      Rectangle imageBounds = image.getImageBounds();
      if (imageBounds != null) {
        double deviceFrameFactor = imageBounds.getWidth() / (double) image.getScaledWidth();
        zoomFactor *= deviceFrameFactor;
      }

      x *= zoomFactor;
      y *= zoomFactor;
      width *= zoomFactor;
      height *= zoomFactor;

      if (imageBounds != null) {
        x += imageBounds.x;
        y += imageBounds.y;
      }

      return new Rectangle(x, y, width, height);
    }

    return null;
  }

  private void paintRenderedImage(Graphics g, int px, int py) {
    if (myRenderResult == null) {
      return;
    }
    ScalableImage image = myRenderResult.getImage();
    if (image != null) {
      image.paint(g, px, py);

      // TODO: Use layout editor's static feedback rendering
      List<RenderedView> selectedViews = mySelectedViews;
      if (selectedViews != null && !selectedViews.isEmpty() && !myErrorPanel.isVisible()) {
        for (RenderedView selected : selectedViews) {
          Rectangle r = fromModelToScreen(selected.x, selected.y, selected.w, selected.h);
          if (r == null) {
            return;
          }
          int x = r.x + px;
          int y = r.y + py;
          int w = r.width;
          int h = r.height;

          g.setColor(SELECTION_FILL_COLOR);
          g.fillRect(x, y, w, h);

          g.setColor(SELECTION_BORDER_COLOR);
          x -= 1;
          y -= 1;
          w += 1; // +1 rather than +2: drawRect already includes end point whereas fillRect does not
          h += 1;
          if (x < 0) {
            w -= x;
            x = 0;
          }
          if (y < 0) {
            h -= y;
            y = 0;
          }

          g.drawRect(x, y, w, h);
        }
      }
    }
  }

  private void updateCaret() {
    if (myCaretModel != null) {
      RenderedViewHierarchy hierarchy = myRenderResult.getHierarchy();
      if (hierarchy != null) {
        int offset = myCaretModel.getOffset();
        if (offset != -1) {
          List<RenderedView> views = hierarchy.findByOffset(offset);
          if (views != null && views.size() == 1 && views.get(0).isRoot()) {
            views = null;
          }
          if (!Objects.equal(views, mySelectedViews)) {
            mySelectedViews = views;
            repaint();
          }
        }
      }
    }
  }

  public void setRenderResult(@NotNull final RenderResult renderResult, @Nullable final TextEditor editor) {
    double prevScale = myRenderResult != null && myRenderResult.getImage() != null ? myRenderResult.getImage().getScale() : 1;
    myRenderResult = renderResult;
    ScalableImage image = myRenderResult.getImage();
    if (image != null) {
      image.setDeviceFrameEnabled(myShowDeviceFrames && myRenderResult.getRenderService() != null &&
                                  myRenderResult.getRenderService().getRenderingMode() == SessionParams.RenderingMode.NORMAL &&
                                  myRenderResult.getRenderService().getShowDecorations());
      if (myPreviewManager != null && RenderPreviewMode.getCurrent() != RenderPreviewMode.NONE) {
        Dimension fixedRenderSize = myPreviewManager.getFixedRenderSize();
        if (fixedRenderSize != null) {
          image.setMaxSize(fixedRenderSize.width, fixedRenderSize.height);
          image.setUseLargeShadows(false);
        }
      }
      image.setScale(prevScale);
    }
    mySelectedViews = null;

    RenderLogger logger = myRenderResult.getLogger();
    if (logger.hasProblems()) {
      if (!myErrorPanel.isVisible()) {
        myErrorPanelHeight = -1;
      }
      myErrorPanel.showErrors(myRenderResult);
      myErrorPanel.setVisible(true);
    } else {
      myErrorPanel.setVisible(false);
    }

    setEditor(editor);
    updateCaret();
    doRevalidate();

    // Ensure that if we have a a preview mode enabled, it's shown
    if (myPreviewManager != null && myPreviewManager.hasPreviews()) {
      myPreviewManager.renderPreviews();
    }
  }

  RenderResult getRenderResult() {
    return myRenderResult;
  }

  private void setEditor(@Nullable TextEditor editor) {
    if (editor != myEditor) {
      myEditor = editor;

      if (myCaretModel != null) {
        myCaretModel.removeCaretListener(myCaretListener);
        myCaretModel = null;
      }
      if (editor != null)
      myCaretModel = myEditor.getEditor().getCaretModel();
      if (myCaretModel != null) {
        myCaretModel.addCaretListener(myCaretListener);
      }
    }
  }

  public synchronized void registerIndicator(@NotNull ProgressIndicator indicator) {
    synchronized (myProgressIndicators) {
      myProgressIndicators.add(indicator);
      myProgressPanel.showProgressIcon();
    }
  }

  public void unregisterIndicator(@NotNull ProgressIndicator indicator) {
    synchronized (myProgressIndicators) {
      myProgressIndicators.remove(indicator);

      if (myProgressIndicators.size() == 0) {
        myProgressPanel.hideProgressIcon();
      }
    }
  }

  private void doRevalidate() {
    revalidate();
    updateImageSize();
    repaint();
  }

  public void update() {
    revalidate();

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        doRevalidate();
      }
    });
  }

  void updateImageSize() {
    if (myRenderResult == null) {
      return;
    }
    updateBackgroundColor();


    ScalableImage image = myRenderResult.getImage();
    if (image == null) {
      myImagePanel.setSize(0, 0);
    }
    else {
      if (myZoomToFit) {
        double availableHeight = getPanelHeight();
        double availableWidth = getPanelWidth();
        final int MIN_SIZE = 200;
        if (myPreviewManager != null && availableWidth > MIN_SIZE) {
          int previewWidth  = myPreviewManager.computePreviewWidth();
          availableWidth = Math.max(MIN_SIZE, availableWidth - previewWidth);
        }
        image.zoomToFit((int)availableWidth, (int)availableHeight, false, 0, 0);
      }

      myImagePanel.setSize(getScaledImageSize());
      repaint();
    }
  }

  private void updateBackgroundColor() {
    // Ensure the background color is right: light/dark when showing device chrome, gray when not
    boolean useGray = false;
    if (!myShowDeviceFrames) {
      useGray = true;
    } else if (myPreviewManager != null && RenderPreviewMode.getCurrent() != RenderPreviewMode.NONE) {
      useGray = true;
    } else {
      if (myRenderResult != null) {
        ScalableImage image = myRenderResult.getImage();
        if (image != null) {
          Boolean framed = image.isFramed();
          if (framed == null) {
            return;
          }
          useGray = !framed;
        }
      }
    }
    Color background = useGray ? DESIGNER_BACKGROUND_COLOR : JBColor.WHITE;
    if (getBackground() != background) {
      setBackground(background);
    }
  }

  private double getPanelHeight() {
    return getParent().getParent().getSize().getHeight() - 5;
  }

  private double getPanelWidth() {
    return getParent().getParent().getSize().getWidth() - 5;
  }

  public void zoomOut() {
    myZoomToFit = false;
    if (myRenderResult.getImage() != null) {
      myRenderResult.getImage().zoomOut();
    }
    doRevalidate();
  }

  public void zoomIn() {
    myZoomToFit = false;
    if (myRenderResult.getImage() != null) {
      myRenderResult.getImage().zoomIn();
    }
    doRevalidate();
  }

  public void zoomActual() {
    myZoomToFit = false;
    if (myRenderResult.getImage() != null) {
      myRenderResult.getImage().zoomActual();
    }
    doRevalidate();
  }

  public void setZoomToFit(boolean zoomToFit) {
    myZoomToFit = zoomToFit;
    doRevalidate();
  }

  public boolean isZoomToFit() {
    return myZoomToFit;
  }

  @Override
  public void dispose() {
    if (myPreviewManager != null) {
      myPreviewManager.dispose();
      myPreviewManager = null;
    }
    myErrorPanel.dispose();
    myErrorPanel = null;
  }

  // RenderContext helpers

  public boolean hasAlphaChannel() {
    return myRenderResult.getImage() != null && !myRenderResult.getImage().hasAlphaChannel();
  }

  @NotNull
  public Dimension getFullImageSize() {
    if (myRenderResult != null) {
      ScalableImage scaledImage = myRenderResult.getImage();
      if (scaledImage != null) {
        return new Dimension(scaledImage.getOriginalWidth(), scaledImage.getOriginalHeight());
      }
    }

    return RenderContext.NO_SIZE;
  }

  @NotNull
  public Dimension getScaledImageSize() {
    if (myRenderResult != null) {
      ScalableImage scaledImage = myRenderResult.getImage();
      if (scaledImage != null) {
        return new Dimension(scaledImage.getScaledWidth(), scaledImage.getScaledHeight());
      }
    }

    return RenderContext.NO_SIZE;
  }

  public Component getRenderComponent() {
    return myImagePanel.getParent();
  }

  public void setPreviewManager(@Nullable RenderPreviewManager manager) {
    if (manager == myPreviewManager) {
      return;
    }
    Component renderComponent = getRenderComponent();
    if (myPreviewManager != null) {
      myPreviewManager.unregisterMouseListener(renderComponent);
      myPreviewManager.dispose();
    }
    myPreviewManager = manager;
    if (myPreviewManager != null) {
      myPreviewManager.registerMouseListener(renderComponent);
    }
  }

  @Nullable
  public RenderPreviewManager getPreviewManager(@Nullable RenderContext context, boolean createIfNecessary) {
    if (myPreviewManager == null && createIfNecessary && context != null) {
      setPreviewManager(new RenderPreviewManager(context));
    }

    return myPreviewManager;
  }

  public void setMaxSize(int width, int height) {
    ScalableImage scaledImage = myRenderResult.getImage();
    if (scaledImage != null) {
      scaledImage.setMaxSize(width, height);
      scaledImage.setUseLargeShadows(width <= 0);
      myImagePanel.revalidate();
    }
  }

  private boolean myShowDeviceFrames = true;

  public void setDeviceFramesEnabled(boolean on) {
    myShowDeviceFrames = on;
    if (myRenderResult != null) {
      ScalableImage image = myRenderResult.getImage();
      if (image != null) {
        image.setDeviceFrameEnabled(on);
      }
    }
  }

  /**
   * Layered pane which shows the rendered image, as well as (if applicable) an error message panel on top of the rendering
   * near the bottom
   */
  private class MyImagePanelWrapper extends JBLayeredPane {
    public MyImagePanelWrapper() {
      add(myImagePanel);
      setBackground(null);
      setOpaque(true);
    }

    @Override
    public void doLayout() {
      super.doLayout();
      positionErrorPanel();
      myProgressPanel.setBounds(0, 0, getWidth(), getHeight());

      if (myPreviewManager == null || !myPreviewManager.hasPreviews()) {
        centerComponents();
      } else {
        if (myRenderResult != null) {
          ScalableImage image = myRenderResult.getImage();
          if (image != null) {
            int fixedWidth = image.getMaxWidth();
            int fixedHeight = image.getMaxHeight();
            if (fixedWidth > 0) {
              myImagePanel.setLocation(Math.max(0, (fixedWidth - image.getScaledWidth()) / 2),
                                       2 + Math.max(0, (fixedHeight - image.getScaledHeight()) / 2));
              return;
            }
          }
        }

        myImagePanel.setLocation(0, 0);
      }
    }

    private void centerComponents() {
      Rectangle bounds = getBounds();
      Point point = myImagePanel.getLocation();
      point.x = (bounds.width - myImagePanel.getWidth()) / 2;
      point.y = (bounds.height - myImagePanel.getHeight()) / 2;

      // If we're squeezing the image to fit, and there's a drop shadow showing
      // shift *some* space away from the tail portion of the drop shadow over to
      // the left to make the image look more balanced
      if (myRenderResult != null) {
        if (point.x <= 2) {
          ScalableImage image = myRenderResult.getImage();
          // If there's a drop shadow
          if (image != null) {
            if (image.hasDropShadow()) {
              point.x += ShadowPainter.SHADOW_SIZE / 3;
            }
          }
        }
        if (point.y <= 2) {
          ScalableImage image = myRenderResult.getImage();
          // If there's a drop shadow
          if (image != null) {
            if (image.hasDropShadow()) {
              point.y += ShadowPainter.SHADOW_SIZE / 3;
            }
          }
        }
      }
      myImagePanel.setLocation(point);
    }

    private void positionErrorPanel() {
      int height = getHeight();
      int width = getWidth();
      int size;
      if (SIZE_ERROR_PANEL_DYNAMICALLY) {
        if (myErrorPanelHeight == -1) {
          // Make the layout take up to 3/4ths of the height, and at least 1/4th, but
          // anywhere in between based on what the actual text requires
          size = height * 3 / 4;
          int preferredHeight = myErrorPanel.getPreferredHeight(width) + 8;
          if (preferredHeight < size) {
            size = Math.max(preferredHeight, Math.min(height / 4, size));
            myErrorPanelHeight = size;
          }
        } else {
          size = myErrorPanelHeight;
        }
      } else {
        size = height / 2;
      }

      myErrorPanel.setSize(width, size);
      myErrorPanel.setLocation(0, height - size);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      paintRenderedImage(graphics, myImagePanel.getX(), myImagePanel.getY());

      if (myPreviewManager != null) {
        myPreviewManager.paint((Graphics2D)graphics);
      }

      super.paintComponent(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
      return myImagePanel.getSize();
    }
  }

  /**
   * Panel which displays the progress icon. The progress icon can either be a large icon in the
   * center, when there is no rendering showing, or a small icon in the upper right corner when there
   * is a rendering. This is necessary because even though the progress icon looks good on some
   * renderings, depending on the layout theme colors it is invisible in other cases.
   */
  private class MyProgressPanel extends JPanel {
    private AsyncProcessIcon mySmallProgressIcon;
    private AsyncProcessIcon myLargeProgressIcon;
    private boolean mySmall;
    private boolean myProgressVisible;

    private MyProgressPanel() {
      super(new BorderLayout());
      setOpaque(false);
    }

    /** The "small" icon mode isn't just for the icon size; it's for the layout position too; see {@link #doLayout} */
    private void setSmallIcon(boolean small) {
      if (small != mySmall) {
        if (myProgressVisible && getComponentCount() != 0) {
          AsyncProcessIcon oldIcon = getProgressIcon();
          oldIcon.suspend();
        }
        mySmall = true;
        removeAll();
        AsyncProcessIcon icon = getProgressIcon();
        add(icon, BorderLayout.CENTER);
        if (myProgressVisible) {
          icon.setVisible(true);
          icon.resume();
        }
      }
    }

    public void showProgressIcon() {
      if (!myProgressVisible) {
        setSmallIcon(myRenderResult != null && myRenderResult.getImage() != null);
        myProgressVisible = true;
        setVisible(true);
        AsyncProcessIcon icon = getProgressIcon();
        if (getComponentCount() == 0) { // First time: haven't added icon yet?
          add(getProgressIcon(), BorderLayout.CENTER);
        } else {
          icon.setVisible(true);
        }
        icon.resume();
      }
    }

    public void hideProgressIcon() {
      if (myProgressVisible) {
        myProgressVisible = false;
        setVisible(false);
        AsyncProcessIcon icon = getProgressIcon();
        icon.setVisible(false);
        icon.suspend();
      }
    }

    @Override
    public void doLayout() {
      super.doLayout();

      if (!myProgressVisible) {
        return;
      }

      // Place the progress icon in the center if there's no rendering, and in the
      // upper right corner if there's a rendering. The reason for this is that the icon color
      // will depend on whether we're in a light or dark IDE theme, and depending on the rendering
      // in the layout it will be invisible. For example, in Darcula the icon is white, and if the
      // layout is rendering a white screen, the progress is invisible.
      AsyncProcessIcon icon = getProgressIcon();
      Dimension size = icon.getPreferredSize();
      if (mySmall) {
        icon.setBounds(getWidth() - size.width - 1, 1, size.width, size.height);
      } else {
        icon.setBounds(getWidth() / 2 - size.width / 2, getHeight() / 2 - size.height / 2, size.width, size.height);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return getProgressIcon().getPreferredSize();
    }

    @NotNull
    private AsyncProcessIcon getProgressIcon() {
      return getProgressIcon(mySmall);
    }

    @NotNull
    private AsyncProcessIcon getProgressIcon(boolean small) {
      if (small) {
        if (mySmallProgressIcon == null) {
          mySmallProgressIcon = new AsyncProcessIcon("Android layout rendering");
          Disposer.register(AndroidLayoutPreviewPanel.this, mySmallProgressIcon);
        }
        return mySmallProgressIcon;
      }
      else {
        if (myLargeProgressIcon == null) {
          myLargeProgressIcon = new AsyncProcessIcon.Big("Android layout rendering");
          Disposer.register(AndroidLayoutPreviewPanel.this, myLargeProgressIcon);
        }
        return myLargeProgressIcon;
      }
    }
  }
}
