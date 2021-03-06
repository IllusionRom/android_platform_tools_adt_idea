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
package com.android.tools.idea.editors;

import junit.framework.TestCase;

public class AndroidImportFilterTest extends TestCase {
  public void test() {
    AndroidImportFilter filter = new AndroidImportFilter();
    assertTrue(filter.shouldUseFullyQualifiedName("android.R"));
    assertTrue(filter.shouldUseFullyQualifiedName("android.R.anim"));
    assertTrue(filter.shouldUseFullyQualifiedName("android.R.anything"));
    assertFalse(filter.shouldUseFullyQualifiedName("com.android.tools.R"));
    assertTrue(filter.shouldUseFullyQualifiedName("com.android.tools.R.anim"));
    assertTrue(filter.shouldUseFullyQualifiedName("com.android.tools.R.layout"));
    assertTrue(filter.shouldUseFullyQualifiedName("a.R.string"));
    assertFalse(filter.shouldUseFullyQualifiedName("my.weird.clz.R"));
    assertFalse(filter.shouldUseFullyQualifiedName("my.weird.clz.R.bogus"));
    assertFalse(filter.shouldUseFullyQualifiedName(""));
    assertFalse(filter.shouldUseFullyQualifiedName("."));
    assertFalse(filter.shouldUseFullyQualifiedName("a.R"));
    assertFalse(filter.shouldUseFullyQualifiedName("android"));
    assertFalse(filter.shouldUseFullyQualifiedName("android."));
    assertFalse(filter.shouldUseFullyQualifiedName("android.r"));
    assertFalse(filter.shouldUseFullyQualifiedName("android.Random"));
    assertFalse(filter.shouldUseFullyQualifiedName("my.R.unrelated"));
    assertFalse(filter.shouldUseFullyQualifiedName("my.R.unrelated.to"));
    assertFalse(filter.shouldUseFullyQualifiedName("R.string")); // R is never in the default package
  }
}
