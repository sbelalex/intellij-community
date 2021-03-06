// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.devkit.references;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.uast.UastPatterns;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import com.intellij.testFramework.TestDataFile;
import com.intellij.util.PathUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.testAssistant.TestDataNavigationHandler;
import org.jetbrains.uast.*;

import java.util.Collections;
import java.util.List;

public class TestDataFilesReferencesContributor extends PsiReferenceContributor {
  private static final String TEST_DATA_FILE_ANNOTATION_QUALIFIED_NAME = TestDataFile.class.getCanonicalName();

  @Override
  public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {

    UastReferenceRegistrar
      .registerUastReferenceProvider(
        registrar,
        UastPatterns.stringLiteralExpression().inCall(UastPatterns.callExpression()),
        new UastReferenceProvider() {
          @NotNull
          @Override
          public PsiReference[] getReferencesByElement(@NotNull UElement element, @NotNull ProcessingContext context) {
            ULiteralExpression expression = (ULiteralExpression)element;
            UCallExpression call = UastUtils.getUCallExpression(expression);
            if (call == null) return PsiReference.EMPTY_ARRAY;

            PsiParameter targetParameter = UastUtils.guessCorrespondingParameter(call, expression);
            if (!checkTestDataFileAnnotationPresent(targetParameter)) {
              return PsiReference.EMPTY_ARRAY;
            }

            String testDataFilePath = getTestDataFilePath(expression, call);
            if (testDataFilePath == null) {
              return PsiReference.EMPTY_ARRAY;
            }

            String directory = PathUtil.getParentPath(testDataFilePath);

            PsiLanguageInjectionHost host = UastLiteralUtils.getPsiLanguageInjectionHost(expression);
            if (host == null) return PsiReference.EMPTY_ARRAY;

            FileReferenceSet fileReferenceSet = new FileReferenceSet(host);
            fileReferenceSet.addCustomization(
              FileReferenceSet.DEFAULT_PATH_EVALUATOR_OPTION,
              ignore -> {
                VirtualFile file = LocalFileSystem.getInstance().findFileByPath(directory);
                return file == null ? null : Collections.singleton(host.getManager().findDirectory(file));
              });

            return fileReferenceSet.getAllReferences();
          }
        }, PsiReferenceRegistrar.DEFAULT_PRIORITY
      );
  }

  private static boolean checkTestDataFileAnnotationPresent(@Nullable PsiParameter targetParameter) {
    if (targetParameter == null) {
      return false;
    }
    PsiAnnotation[] annotations = targetParameter.getAnnotations();
    for (PsiAnnotation annotation : annotations) {
      if (TEST_DATA_FILE_ANNOTATION_QUALIFIED_NAME.equals(annotation.getQualifiedName())) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static String getTestDataFilePath(@NotNull ULiteralExpression expression,
                                            @NotNull UCallExpression methodCallExpression) {
    Object value = expression.getValue();
    if (!(value instanceof String)) {
      return null;
    }

    String relativePath = (String)value;
    PsiMethod testMethod = UElementKt.getAsJavaPsiElement(UastUtils.getParentOfType(methodCallExpression, UMethod.class), PsiMethod.class);
    if (testMethod == null) {
      return null;
    }

    List<String> filePaths = TestDataNavigationHandler.fastGetTestDataPathsByRelativePath(relativePath, testMethod);
    if (filePaths.size() != 1) {
      return null;
    }
    return filePaths.get(0);
  }
}
