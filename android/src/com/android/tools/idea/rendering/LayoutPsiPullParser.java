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

import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.res2.ValueXmlHelper;
import com.android.resources.Density;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.android.SdkConstants.*;
import static com.android.tools.idea.rendering.RenderService.AttributeFilter;

/**
 * {@link com.android.ide.common.rendering.api.ILayoutPullParser} implementation on top of
 * the PSI {@link XmlTag}.
 * <p/>
 * It's designed to work on layout files, and will not work on other resource files (no text event
 * support for example).
 * <p/>
 * This pull parser generates {@link com.android.ide.common.rendering.api.ViewInfo}s whose keys
 * are of type {@link XmlTag}.
 */
public class LayoutPsiPullParser extends LayoutPullParser {
  @NotNull
  private final RenderLogger myLogger;

  @NotNull
  private final List<XmlTag> myNodeStack = new ArrayList<XmlTag>();

  @Nullable
  protected final XmlTag myRoot;

  @Nullable
  private String myToolsPrefix;

  @Nullable
  private String myAndroidPrefix;

  private boolean myProvideViewCookies = true;

  /**
   * Constructs a new {@link LayoutPsiPullParser}, a parser dedicated to the special case of
   * parsing a layout resource files.
   *
   * @param file         The {@link XmlTag} for the root node.
   * @param logger       The logger to emit warnings too, such as missing fragment associations
   */
  @NotNull
  public static LayoutPsiPullParser create(@NotNull XmlFile file, @NotNull RenderLogger logger) {
    return new LayoutPsiPullParser(file, logger);
  }

  /**
   * Constructs a new {@link LayoutPsiPullParser}, a parser dedicated to the special case of
   * parsing a layout resource files, and handling "exploded rendering" - adding padding on views
   * to make them easier to see and operate on.
   *
   * @param file         The {@link com.intellij.psi.xml.XmlTag} for the root node.
   * @param logger       The logger to emit warnings too, such as missing fragment associations
   * @param explodeNodes A set of individual nodes that should be assigned a fixed amount of
 *                       padding ({@link com.android.tools.idea.rendering.PaddingLayoutPsiPullParser#FIXED_PADDING_VALUE}).
 *                       This is intended for use with nodes that (without padding) would be
 *                       invisible.
   * @param density      the density factor for the screen.
   */
  @NotNull
  public static LayoutPsiPullParser create(@NotNull XmlFile file,
                                           @NotNull RenderLogger logger,
                                           @Nullable Set<XmlTag> explodeNodes,
                                           @NotNull Density density) {
    if (explodeNodes != null && !explodeNodes.isEmpty()) {
      return new PaddingLayoutPsiPullParser(file, logger, explodeNodes, density);
    } else {
      return new LayoutPsiPullParser(file, logger);
    }
  }

  @NotNull
  public static LayoutPsiPullParser create(@Nullable final AttributeFilter filter,
                                           @NotNull XmlTag root,
                                           @NotNull RenderLogger logger) {
    return new LayoutPsiPullParser(root, logger) {
      @Override
      public String getAttributeValue(String namespace, String localName) {
        if (filter != null) {
          Object cookie = getViewCookie();
          if (cookie instanceof XmlTag) {
            XmlTag tag = (XmlTag)cookie;
            String value = filter.getAttribute(tag, namespace, localName);
            if (value != null) {
              if (value.isEmpty()) { // empty means unset
                return null;
              }
              return value;
            }
            // null means no preference, not "unset".
          }
        }

        return super.getAttributeValue(namespace, localName);
      }
    };
  }

  /** Use one of the {@link #create} factory methods instead */
  protected LayoutPsiPullParser(@NotNull XmlFile file, @NotNull RenderLogger logger) {
    this(file.getRootTag(), logger);
  }

  protected LayoutPsiPullParser(@Nullable XmlTag root, @NotNull RenderLogger logger) {
    myRoot = root;
    myLogger = logger;

    if (myRoot != null) {
      myAndroidPrefix = myRoot.getPrefixByNamespace(ANDROID_URI);
      myToolsPrefix = myRoot.getPrefixByNamespace(TOOLS_URI);
    }
  }

  @Nullable
  protected final XmlTag getCurrentNode() {
    if (myNodeStack.size() > 0) {
      return myNodeStack.get(myNodeStack.size() - 1);
    }

    return null;
  }

  @Nullable
  protected final XmlAttribute getAttribute(int i) {
    if (myParsingState != START_TAG) {
      throw new IndexOutOfBoundsException();
    }

    // get the current uiNode
    XmlTag uiNode = getCurrentNode();
    if (uiNode != null) {
      return uiNode.getAttributes()[i];
    }

    return null;
  }

  protected void push(@NotNull XmlTag node) {
    myNodeStack.add(node);
  }

  @NotNull
  protected XmlTag pop() {
    return myNodeStack.remove(myNodeStack.size() - 1);
  }

  // ------------- IXmlPullParser --------

  /**
   * {@inheritDoc}
   * <p/>
   * This implementation returns the underlying DOM node of type {@link XmlTag}.
   * Note that the link between the GLE and the parsing code depends on this being the actual
   * type returned, so you can't just randomly change it here.
   */
  @Nullable
  @Override
  public Object getViewCookie() {
    return myProvideViewCookies ? getCurrentNode() : null;
  }

  /**
   * Legacy method required by {@link com.android.layoutlib.api.IXmlPullParser}
   */
  @SuppressWarnings("deprecation")
  @Nullable
  @Override
  public Object getViewKey() {
    return getViewCookie();
  }

  /**
   * This implementation does nothing for now as all the embedded XML will use a normal KXML
   * parser.
   */
  @Nullable
  @Override
  public ILayoutPullParser getParser(String layoutName) {
    return null;
  }

  // ------------- XmlPullParser --------

  @Override
  public String getPositionDescription() {
    return "XML DOM element depth:" + myNodeStack.size();
  }

  /*
   * This does not seem to be called by the layoutlib, but we keep this (and maintain
   * it) just in case.
   */
  @Override
  public int getAttributeCount() {
    XmlTag node = getCurrentNode();

    if (node != null) {
      return node.getAttributes().length;
    }

    return 0;
  }

  /*
   * This does not seem to be called by the layoutlib, but we keep this (and maintain
   * it) just in case.
   */
  @Nullable
  @Override
  public String getAttributeName(int i) {
    XmlAttribute attribute = getAttribute(i);
    if (attribute != null) {
      return attribute.getLocalName();
    }

    return null;
  }

  /*
   * This does not seem to be called by the layoutlib, but we keep this (and maintain
   * it) just in case.
   */
  @Override
  public String getAttributeNamespace(int i) {
    XmlAttribute attribute = getAttribute(i);
    if (attribute != null) {
      return attribute.getNamespace();
    }
    return ""; //$NON-NLS-1$
  }

  /*
   * This does not seem to be called by the layoutlib, but we keep this (and maintain
   * it) just in case.
   */
  @Nullable
  @Override
  public String getAttributePrefix(int i) {
    XmlAttribute attribute = getAttribute(i);
    if (attribute != null) {
      String prefix = attribute.getNamespacePrefix();
      if (prefix.isEmpty()) {
        prefix = null;
      }
      return prefix;
    }
    return null;
  }

  /*
   * This does not seem to be called by the layoutlib, but we keep this (and maintain
   * it) just in case.
   */
  @Nullable
  @Override
  public String getAttributeValue(int i) {
    XmlAttribute attribute = getAttribute(i);
    if (attribute != null) {
      return attribute.getValue();
    }

    return null;
  }

  /*
   * This is the main method used by the LayoutInflater to query for attributes.
   */
  @Nullable
  @Override
  public String getAttributeValue(String namespace, String localName) {
    // get the current uiNode
    XmlTag tag = getCurrentNode();
    if (tag != null) {
      if (ATTR_LAYOUT.equals(localName) && VIEW_FRAGMENT.equals(tag.getName())) {
        String layout = LayoutMetadata.getFragmentLayout(tag);
        if (layout != null) {
          return layout;
        }
      }

      String value = null;
      if (namespace == null) {
        value = tag.getAttributeValue(localName);
      } else if (namespace.equals(ANDROID_URI)) {
        if (myAndroidPrefix != null) {
          // The PSI implementation of XmlTag#getAttributeValue(name, namespace)
          // just turns around and looks up the prefix, then concatenates the prefix
          // and the name and turns around and calls getAttributeValue(name) anyway.
          // Here we pre-compute the prefix once, and if we know that the document
          // also has a tools prefix, we allow the tools attribute to win at designtime.
          if (myToolsPrefix != null) {
            value = tag.getAttributeValue(myToolsPrefix + ':' + localName);
            if (value != null) {
              if (value.isEmpty()) {
                // Empty when there is a runtime attribute set means unset the runtime attribute
                return tag.getAttributeValue(myAndroidPrefix + ':' + localName) != null ? null : value;
              }
            }
          }
          if (value == null) {
            value = tag.getAttributeValue(myAndroidPrefix + ':' + localName);
          }
        } else {
          value = tag.getAttributeValue(localName, namespace);
        }
      } else {
        value = tag.getAttributeValue(localName, namespace);

        // Auto-convert http://schemas.android.com/apk/res-auto resources. The lookup
        // will be for the current application's resource package, e.g.
        // http://schemas.android.com/apk/res/foo.bar, but the XML document will
        // be using http://schemas.android.com/apk/res-auto in library projects:
        if (value == null) {
          value = tag.getAttributeValue(localName, AUTO_URI);
        }
      }

      if (value != null) {
        // on the fly convert match_parent to fill_parent for compatibility with older
        // platforms.
        if (VALUE_MATCH_PARENT.equals(value) &&
            (ATTR_LAYOUT_WIDTH.equals(localName) || ATTR_LAYOUT_HEIGHT.equals(localName)) &&
            ANDROID_URI.equals(namespace)) {
          return VALUE_FILL_PARENT;
        }

        // Handle unicode and XML escapes
        for (int i = 0, n = value.length(); i < n; i++) {
          char c = value.charAt(i);
          if (c == '&' || c == '\\') {
            value = ValueXmlHelper.unescapeResourceString(value, true, false);
            break;
          }
        }
      }

      return value;
    }

    return null;
  }

  @Override
  public int getDepth() {
    return myNodeStack.size();
  }

  @Nullable
  @Override
  public String getName() {
    if (myParsingState == START_TAG || myParsingState == END_TAG) {
      XmlTag currentNode = getCurrentNode();
      assert currentNode != null; // Should only be called when START_TAG
      String name = currentNode.getLocalName();
      if (name.equals(VIEW_FRAGMENT)) {
        // Temporarily translate <fragment> to <include> (and in getAttribute
        // we will also provide a layout-attribute for the corresponding
        // fragment name attribute)
        String layout = LayoutMetadata.getFragmentLayout(currentNode);
        if (layout != null) {
          return VIEW_INCLUDE;
        } else {
          String fragmentId = currentNode.getAttributeValue(ATTR_CLASS);
          if (fragmentId == null || fragmentId.isEmpty()) {
            fragmentId = currentNode.getAttributeValue(ATTR_NAME, ANDROID_URI);
            if (fragmentId == null || fragmentId.isEmpty()) {
              fragmentId = currentNode.getAttributeValue(ATTR_ID, ANDROID_URI);
            }
          }
          myLogger.warning(RenderLogger.TAG_MISSING_FRAGMENT, "Missing fragment association", fragmentId);
        }
      }

      return name;
    }

    return null;
  }

  @Nullable
  @Override
  public String getNamespace() {
    if (myParsingState == START_TAG || myParsingState == END_TAG) {
      XmlTag currentNode = getCurrentNode();
      assert currentNode != null;  // Should only be called when START_TAG
      return currentNode.getNamespace();
    }

    return null;
  }

  @Nullable
  @Override
  public String getPrefix() {
    if (myParsingState == START_TAG || myParsingState == END_TAG) {
      XmlTag currentNode = getCurrentNode();
      assert currentNode != null;  // Should only be called when START_TAG
      String prefix = currentNode.getNamespacePrefix();
      if (prefix.isEmpty()) {
        prefix = null;
      }
      return prefix;
    }

    return null;
  }

  @Override
  public boolean isEmptyElementTag() throws XmlPullParserException {
    if (myParsingState == START_TAG) {
      XmlTag currentNode = getCurrentNode();
      assert currentNode != null;  // Should only be called when START_TAG
      return currentNode.isEmpty();
    }

    throw new XmlPullParserException("Call to isEmptyElementTag while not in START_TAG", this, null);
  }

  @Override
  protected void onNextFromStartDocument() {
    if (myRoot != null) {
      push(myRoot);
      myParsingState = START_TAG;
    } else {
      myParsingState = END_DOCUMENT;
    }
  }

  @Override
  protected void onNextFromStartTag() {
    // get the current node, and look for text or children (children first)
    XmlTag node = getCurrentNode();
    assert node != null;  // Should only be called when START_TAG
    XmlTag[] children = node.getSubTags();
    if (children.length > 0) {
      // move to the new child, and don't change the state.
      push(children[0]);

      // in case the current state is CURRENT_DOC, we set the proper state.
      myParsingState = START_TAG;
    }
    else {
      if (myParsingState == START_DOCUMENT) {
        // this handles the case where there's no node.
        myParsingState = END_DOCUMENT;
      }
      else {
        myParsingState = END_TAG;
      }
    }
  }

  @Override
  protected void onNextFromEndTag() {
    // look for a sibling. if no sibling, go back to the parent
    XmlTag node = getCurrentNode();
    assert node != null;  // Should only be called when END_TAG

    PsiElement sibling = node.getNextSibling();
    while (sibling != null && !(sibling instanceof XmlTag)) {
      sibling = sibling.getNextSibling();
    }
    if (sibling != null) {
      node = (XmlTag)sibling;
      // to go to the sibling, we need to remove the current node,
      pop();
      // and add its sibling.
      push(node);
      myParsingState = START_TAG;
    }
    else {
      // move back to the parent
      pop();

      // we have only one element left (myRoot), then we're done with the document.
      if (myNodeStack.isEmpty()) {
        myParsingState = END_DOCUMENT;
      }
      else {
        myParsingState = END_TAG;
      }
    }
  }

  /** Sets whether this parser will provide view cookies */
  public void setProvideViewCookies(boolean provideViewCookies) {
    myProvideViewCookies = provideViewCookies;
  }
}
