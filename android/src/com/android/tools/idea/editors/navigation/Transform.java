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
package com.android.tools.idea.editors.navigation;

import com.android.tools.idea.rendering.RenderedView;

import java.awt.*;

public class Transform {
  public final float myScale;

  public Transform(float scale) {
    myScale = scale;
  }

  public Dimension modelToView(Dimension size) {
    return Utilities.scale(size, myScale);
  }

  public int modelToView(int d) {
    return ((int)(d * myScale));
  }

  public int viewToModel(int d) {
    return (int)(d / myScale);
  }

  public Point modelToView(com.android.navigation.Point loc) {
    return new Point(modelToView(loc.x), modelToView(loc.y));
  }

  public com.android.navigation.Point viewToModel(Point loc) {
    return new com.android.navigation.Point(viewToModel(loc.x), viewToModel(loc.y));
  }

  public Rectangle getBounds(RenderedView v) {
    return new Rectangle(modelToView(v.x), modelToView(v.y), modelToView(v.w), modelToView(v.h));
  }
}
