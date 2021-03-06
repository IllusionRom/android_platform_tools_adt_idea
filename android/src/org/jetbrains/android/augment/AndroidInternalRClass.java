package org.jetbrains.android.augment;

import com.android.resources.ResourceType;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.psi.*;
import org.jetbrains.android.resourceManagers.ResourceManager;
import org.jetbrains.android.resourceManagers.SystemResourceManager;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Eugene.Kudelevsky
 */
class AndroidInternalRClass extends AndroidLightClassBase {
  private final PsiFile myFile;
  private final ResourceManager mySystemResourceManager;
  private final PsiClass[] myInnerClasses;

  public AndroidInternalRClass(@NotNull PsiManager psiManager, @NotNull AndroidPlatform platform) {
    super(psiManager);
    myFile = PsiFileFactory.getInstance(myManager.getProject()).createFileFromText("R.java", JavaFileType.INSTANCE, "");
    mySystemResourceManager = new SystemResourceManager(psiManager.getProject(), platform, false);

    final ResourceType[] types = ResourceType.values();
    myInnerClasses = new PsiClass[types.length];

    for (int i = 0; i < types.length; i++) {
      myInnerClasses[i] = new MyInnerClass(types[i].getName());
    }
  }

  @Override
  public String toString() {
    return "AndroidInternalRClass";
  }

  @Nullable
  @Override
  public String getQualifiedName() {
    return AndroidPsiElementFinder.INTERNAL_R_CLASS_QNAME;
  }

  @Override
  public String getName() {
    return "R";
  }

  @Nullable
  @Override
  public PsiClass getContainingClass() {
    return null;
  }

  @Nullable
  @Override
  public PsiFile getContainingFile() {
    return myFile;
  }

  @NotNull
  @Override
  public PsiClass[] getInnerClasses() {
    return myInnerClasses;
  }

  @Override
  public PsiClass findInnerClassByName(@NonNls String name, boolean checkBases) {
    for (PsiClass aClass : getInnerClasses()) {
      if (name.equals(aClass.getName())) {
        return aClass;
      }
    }
    return null;
  }

  private class MyInnerClass extends ResourceTypeClassBase {

    public MyInnerClass(String name) {
      super(AndroidInternalRClass.this, name);
    }

    @NotNull
    @Override
    protected PsiField[] doGetFields() {
      return buildResourceFields(mySystemResourceManager, false, myName, AndroidInternalRClass.this);
    }
  }
}
