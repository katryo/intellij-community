/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.codeInspection.reference;

import com.intellij.psi.PsiJavaModule;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author Pavel.Dolgov
 */
public interface RefJavaModule extends RefElement {
  @Override
  PsiJavaModule getElement();

  @NotNull
  Map<String, List<String>> getExportedPackageNames();

  @NotNull
  List<RequiredModule> getRequiredModules();

  class RequiredModule {
    @NotNull public final String moduleName;
    @NotNull public final Map<String, List<String>> packagesExportedByModule;
    public final boolean isPublic;

    public RequiredModule(@NotNull String moduleName, @NotNull Map<String, List<String>> packagesExportedByModule, boolean isPublic) {
      this.moduleName = moduleName;
      this.packagesExportedByModule = packagesExportedByModule;
      this.isPublic = isPublic;
    }
  }
}
