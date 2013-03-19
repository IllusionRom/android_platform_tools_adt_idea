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

import com.google.common.collect.Iterators;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class RenderedView implements Iterable<RenderedView> {
  @Nullable public final XmlTag tag;
  public final int x;
  public final int y;
  public final int w;
  public final int h;
  private List<RenderedView> myChildren;

  public RenderedView(@Nullable XmlTag tag, int x, int y, int w, int h) {
    this.x = x;
    this.y = y;
    this.w = w;
    this.h = h;
    this.tag = tag;
  }

  public final int x2() {
    return x + w;
  }

  public final int y2() {
    return y + h;
  }

  public void setChildren(List<RenderedView> children) {
    myChildren = children;
  }

  @NotNull
  public List<RenderedView> getChildren() {
    return myChildren != null ? myChildren : Collections.<RenderedView>emptyList();
  }

  @Nullable
  public RenderedView findViewByTag(XmlTag tag) {
    if (this.tag == tag) {
      return this;
    }

    if (myChildren != null) {
      for (RenderedView child : myChildren) {
        RenderedView result = child.findViewByTag(tag);
        if (result != null) {
          return result;
        }
      }
    }

    return null;
  }

  @Nullable
  public RenderedView findLeafAt(int px, int py) {
    if (myChildren != null) {
      for (RenderedView child : myChildren) {
        RenderedView result = child.findLeafAt(px, py);
        if (result != null) {
          return result;
        }
      }
    }

    return (x <= px && y <= py && x + w >= px && y + h >= py) ? this : null;
  }


  // ---- Implements Iterable<RenderedView> ----
  @Override
  public Iterator<RenderedView> iterator() {
    if (myChildren == null) {
      return Iterators.emptyIterator();
    }

    return myChildren.iterator();
  }
}