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
package org.jetbrains.plugins.groovy.lang.resolve;

import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.PairProcessor;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ClassHint;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ResolverProcessor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
* @author peter
*/
class DeclarationCacheKey {
  private static final CachedValueProvider<ConcurrentMap<DeclarationCacheKey, List<DeclarationHolder>>> VALUE_PROVIDER =
    new CachedValueProvider<ConcurrentMap<DeclarationCacheKey, List<DeclarationHolder>>>() {
      @Nullable
      @Override
      public Result<ConcurrentMap<DeclarationCacheKey, List<DeclarationHolder>>> compute() {
        ConcurrentMap<DeclarationCacheKey, List<DeclarationHolder>> map = ContainerUtil.newConcurrentMap();
        return Result.create(map, PsiModificationTracker.MODIFICATION_COUNT);
      }
    };
  @Nullable private final String name;
  @NotNull private final EnumSet<ClassHint.ResolveKind> kinds;
  private final boolean nonCode;

  DeclarationCacheKey(@Nullable String name, ClassHint hint, boolean nonCode) {
    this.name = name;
    this.kinds = getResolveKinds(hint);
    this.nonCode = nonCode;
  }

  private static EnumSet<ClassHint.ResolveKind> getResolveKinds(ClassHint hint) {
    EnumSet<ClassHint.ResolveKind> set = EnumSet.noneOf(ClassHint.ResolveKind.class);
    for (ClassHint.ResolveKind kind : ClassHint.ResolveKind.values()) {
      if (hint.shouldProcess(kind)) {
        set.add(kind);
      }
    }
    return set;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DeclarationCacheKey)) {
      return false;
    }

    DeclarationCacheKey key = (DeclarationCacheKey)o;

    if (nonCode != key.nonCode) {
      return false;
    }
    if (!kinds.equals(key.kinds)) {
      return false;
    }
    if (name != null ? !name.equals(key.name) : key.name != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + kinds.hashCode();
    result = 31 * result + (nonCode ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return "DeclarationCacheKey{" +
           "name='" + name + '\'' +
           ", kinds=" + kinds +
           ", nonCode=" + nonCode +
           '}';
  }

  private List<DeclarationHolder> collectDeclarations(final GroovyPsiElement place) {
    final ArrayList<DeclarationHolder> result = new ArrayList<DeclarationHolder>();
    PsiTreeUtil.treeWalkUp(place, null, new PairProcessor<PsiElement, PsiElement>() {
      @Override
      public boolean process(PsiElement scope, PsiElement lastParent) {
        result.add(collectScopeDeclarations(scope, lastParent, place));
        return true;
      }
    });
    return result;
  }

  private DeclarationHolder collectScopeDeclarations(PsiElement scope, PsiElement lastParent, GroovyPsiElement place) {
    MyCollectProcessor plainCollector = new MyCollectProcessor(scope);
    MyCollectProcessor nonCodeCollector = new MyCollectProcessor(scope);
    ResolveUtil.doProcessDeclarations(place, lastParent, scope, plainCollector, nonCode ? nonCodeCollector : null);
    return new DeclarationHolder(scope, plainCollector.declarations, nonCodeCollector.declarations);
  }

  private List<DeclarationHolder> getAllDeclarations(GroovyPsiElement place) {
    ConcurrentMap<DeclarationCacheKey, List<DeclarationHolder>> cache =
      CachedValuesManager.getManager(place.getProject()).getCachedValue(place, VALUE_PROVIDER);
    List<DeclarationHolder> declarations = cache.get(this);
    if (declarations == null) {
      cache.putIfAbsent(this, declarations = collectDeclarations(place));
    }
    return declarations;
  }

  boolean processCachedDeclarations(GroovyPsiElement place, PsiScopeProcessor processor) {
    for (DeclarationHolder holder : getAllDeclarations(place)) {
      if (!holder.processCachedDeclarations(processor)) {
        return false;
      }
    }
    return true;
  }

  private static class DeclarationHolder {
    final PsiElement scope;
    final List<Pair<PsiElement, ResolveState>> plainDeclarations;
    final List<Pair<PsiElement, ResolveState>> nonCodeDeclarations;

    private DeclarationHolder(PsiElement scope,
                              List<Pair<PsiElement, ResolveState>> plainDeclarations,
                              List<Pair<PsiElement, ResolveState>> nonCodeDeclarations) {
      this.scope = scope;
      this.plainDeclarations = plainDeclarations;
      this.nonCodeDeclarations = nonCodeDeclarations;
    }

    boolean processCachedDeclarations(PsiScopeProcessor processor) {
      PsiScopeProcessor realProcessor = ResolveUtil.substituteProcessor(processor, scope);
      for (Pair<PsiElement, ResolveState> pair : plainDeclarations) {
        if (!realProcessor.execute(pair.first, pair.second)) {
          return false;
        }
      }
      for (Pair<PsiElement, ResolveState> pair : nonCodeDeclarations) {
        if (!processor.execute(pair.first, pair.second)) {
          return false;
        }
      }

      ResolveUtil.issueLevelChangeEvents(processor, scope);
      return true;
    }

  }

  private class MyCollectProcessor extends ResolverProcessor {
    final List<Pair<PsiElement, ResolveState>> declarations = CollectionFactory.arrayList();

    public MyCollectProcessor(PsiElement scope) {
      super(DeclarationCacheKey.this.name, DeclarationCacheKey.this.kinds, scope, PsiType.EMPTY_ARRAY);
    }

    @Override
    public boolean execute(@NotNull PsiElement element, ResolveState state) {
      declarations.add(Pair.create(element, state));
      return true;
    }
  }
}

