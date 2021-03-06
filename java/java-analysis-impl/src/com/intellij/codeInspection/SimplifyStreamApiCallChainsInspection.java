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
package com.intellij.codeInspection;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.PsiDiamondTypeUtil;
import com.intellij.psi.util.*;
import com.siyeh.ig.psiutils.BoolUtils;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.StreamApiUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Pavel.Dolgov
 */
public class SimplifyStreamApiCallChainsInspection extends BaseJavaBatchLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance("#" + SimplifyStreamApiCallChainsInspection.class.getName());

  private static final String FOR_EACH_METHOD = "forEach";
  private static final String FOR_EACH_ORDERED_METHOD = "forEachOrdered";
  private static final String STREAM_METHOD = "stream";
  private static final String EMPTY_METHOD = "empty";
  private static final String AS_LIST_METHOD = "asList";
  private static final String OF_METHOD = "of";
  private static final String EMPTY_LIST_METHOD = "emptyList";
  private static final String EMPTY_SET_METHOD = "emptySet";
  private static final String SINGLETON_LIST_METHOD = "singletonList";
  private static final String SINGLETON_METHOD = "singleton";
  private static final String COLLECT_METHOD = "collect";
  private static final String IS_PRESENT_METHOD = "isPresent";
  private static final String FIND_ANY_METHOD = "findAny";
  private static final String FIND_FIRST_METHOD = "findFirst";
  private static final String FILTER_METHOD = "filter";
  private static final String ANY_MATCH_METHOD = "anyMatch";
  private static final String NONE_MATCH_METHOD = "noneMatch";
  private static final String ALL_MATCH_METHOD = "allMatch";
  private static final String TO_ARRAY_METHOD = "toArray";

  private static final String COUNTING_COLLECTOR = "counting";
  private static final String TO_LIST_COLLECTOR = "toList";
  private static final String TO_SET_COLLECTOR = "toSet";
  private static final String TO_COLLECTION_COLLECTOR = "toCollection";
  private static final String MIN_BY_COLLECTOR = "minBy";
  private static final String MAX_BY_COLLECTOR = "maxBy";
  private static final String MAPPING_COLLECTOR = "mapping";
  private static final String REDUCING_COLLECTOR = "reducing";
  private static final String SUMMING_INT_COLLECTOR = "summingInt";
  private static final String SUMMING_LONG_COLLECTOR = "summingLong";
  private static final String SUMMING_DOUBLE_COLLECTOR = "summingDouble";

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.isLanguageLevel8OrHigher(holder.getFile())) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }

    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression methodCall) {
        PsiMethod method = methodCall.resolveMethod();
        if(method == null) return;
        PsiClass psiClass = method.getContainingClass();
        if(psiClass == null) return;
        if (isCallOf(method, CommonClassNames.JAVA_UTIL_COLLECTION, STREAM_METHOD, 0)) {
          handleCollectionStream(methodCall);
        }
        else if (isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_STREAM, COLLECT_METHOD, 1)) {
          handleStreamCollect(methodCall);
        }
        else if (isCallOf(method, CommonClassNames.JAVA_UTIL_OPTIONAL, IS_PRESENT_METHOD, 0)) {
          handleOptionalIsPresent(methodCall);
        }
        else if (isStreamCall(method, TO_ARRAY_METHOD, false)) {
          handleToArray(methodCall);
        }
        else if (isStreamCall(method, ANY_MATCH_METHOD, true)) {
          if(isParentNegated(methodCall)) {
            boolean argNegated = isArgumentLambdaNegated(methodCall);
            registerMatchFix(methodCall,
                             new SimplifyMatchNegationFix(
                               "!" + psiClass.getName() + (argNegated ? ".anyMatch(x -> !(...))" : ".anyMatch(...)"),
                                                          argNegated ? ALL_MATCH_METHOD : NONE_MATCH_METHOD));
          }
        }
        else if (isStreamCall(method, NONE_MATCH_METHOD, true)) {
          if(isParentNegated(methodCall)) {
            registerMatchFix(methodCall, new SimplifyMatchNegationFix("!"+psiClass.getName()+".noneMatch(...)", ANY_MATCH_METHOD));
          }
          if(isArgumentLambdaNegated(methodCall)) {
            registerMatchFix(methodCall, new SimplifyMatchNegationFix(psiClass.getName()+".noneMatch(x -> !(...))", ALL_MATCH_METHOD));
          }
        }
        else if (isStreamCall(method, ALL_MATCH_METHOD, true)) {
          if(isArgumentLambdaNegated(methodCall)) {
            boolean parentNegated = isParentNegated(methodCall);
            registerMatchFix(methodCall,
                             new SimplifyMatchNegationFix((parentNegated ? "!" : "") + psiClass.getName() + ".allMatch(x -> !(...))",
                                                          parentNegated ? ANY_MATCH_METHOD : NONE_MATCH_METHOD));
          }
        }
        else {
          handleMapToObj(methodCall);
          handleStreamForEach(methodCall, method);
        }
      }

      private void handleToArray(PsiMethodCallExpression methodCall) {
        if(isCollectionStream(getQualifierMethodCall(methodCall))) {
          PsiArrayType type = getArrayType(methodCall);
          if(type != null) {
            PsiElement nameElement = methodCall.getMethodExpression().getReferenceNameElement();
            LOG.assertTrue(nameElement != null);
            String replacement = type.equalsToText(CommonClassNames.JAVA_LANG_OBJECT+"[]") ? "" :
                                 "new "+type.getCanonicalText().replaceFirst("\\[]", "[0]");
            holder.registerProblem(nameElement, "Can be replaced with collection.toArray()",
                                   new SimplifyCallChainFix(new ReplaceWithToArrayFix(replacement)));
          }
        }
      }

      void registerMatchFix(PsiMethodCallExpression methodCall, SimplifyMatchNegationFix fix) {
        PsiElement nameElement = methodCall.getMethodExpression().getReferenceNameElement();
        if(nameElement != null) {
          holder.registerProblem(nameElement, fix.getMessage(), new SimplifyCallChainFix(fix));
        }
      }

      private void handleMapToObj(PsiMethodCallExpression methodCall) {
        PsiElement nameElement = methodCall.getMethodExpression().getReferenceNameElement();
        if(nameElement == null || !"mapToObj".equals(nameElement.getText())) return;
        PsiExpression[] args = methodCall.getArgumentList().getExpressions();
        if(args.length != 1) return;
        PsiType type = StreamApiUtil.getStreamElementType(methodCall.getType());
        if(!(type instanceof PsiClassType)) return;
        PsiClass targetClass = ((PsiClassType)type).resolve();
        PsiExpression qualifier = methodCall.getMethodExpression().getQualifierExpression();
        if (qualifier == null || !TypeConversionUtil
          .boxingConversionApplicable(StreamApiUtil.getStreamElementType(qualifier.getType()), type)) {
          return;
        }
        if(isBoxingFunction(args[0], targetClass)) {
          ReplaceWithBoxedFix fix = new ReplaceWithBoxedFix();
          holder.registerProblem(nameElement,
                                 "Can be replaced with 'boxed'", new SimplifyCallChainFix(fix));
        }
      }

      @Contract("null, _ -> false")
      private boolean isBoxingFunction(PsiExpression arg, PsiClass targetClass) {
        if(arg instanceof PsiMethodReferenceExpression) {
          PsiElement target = ((PsiMethodReferenceExpression)arg).resolve();
          if(target instanceof PsiMethod) {
            PsiMethod method = (PsiMethod)target;
            // Integer::new or Integer::valueOf
            if(targetClass == method.getContainingClass() &&
               (method.isConstructor() || method.getName().equals("valueOf")) && method.getParameterList().getParametersCount() == 1) {
              return true;
            }
          }
        }
        if(arg instanceof PsiLambdaExpression) {
          PsiLambdaExpression lambda = (PsiLambdaExpression)arg;
          PsiParameter[] parameters = lambda.getParameterList().getParameters();
          if(parameters.length != 1) return false;
          PsiParameter parameter = parameters[0];
          PsiExpression expression = PsiUtil.skipParenthesizedExprDown(LambdaUtil.extractSingleExpressionFromBody(lambda.getBody()));
          // x -> x
          if(ExpressionUtils.isReferenceTo(expression, parameter)) {
            return true;
          }
          if(expression instanceof PsiCallExpression) {
            PsiExpressionList list = ((PsiCallExpression)expression).getArgumentList();
            if(list == null) return false;
            PsiExpression[] args = list.getExpressions();
            if(args.length != 1 || !ExpressionUtils.isReferenceTo(args[0], parameter)) {
              return false;
            }
            // x -> new Integer(x)
            if(expression instanceof PsiNewExpression) {
              PsiJavaCodeReferenceElement ref = ((PsiNewExpression)expression).getClassReference();
              if(ref != null && ref.isReferenceTo(targetClass)) return true;
            }
            // x -> Integer.valueOf(x)
            if(expression instanceof PsiMethodCallExpression) {
              PsiMethod method = ((PsiMethodCallExpression)expression).resolveMethod();
              if(method != null && method.getContainingClass() == targetClass && method.getName().equals("valueOf")) return true;
            }
          }
        }
        return false;
      }

      private void handleOptionalIsPresent(PsiMethodCallExpression methodCall) {
        PsiExpression optionalQualifier = methodCall.getMethodExpression().getQualifierExpression();
        if(optionalQualifier instanceof PsiMethodCallExpression) {
          PsiMethod optionalProducer = ((PsiMethodCallExpression)optionalQualifier).resolveMethod();
          if (isCallOf(optionalProducer, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FIND_FIRST_METHOD, 0) ||
              isCallOf(optionalProducer, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FIND_ANY_METHOD, 0)) {
            PsiExpression streamQualifier = ((PsiMethodCallExpression)optionalQualifier).getMethodExpression().getQualifierExpression();
            if(streamQualifier instanceof PsiMethodCallExpression) {
              PsiMethod streamMethod = ((PsiMethodCallExpression)streamQualifier).resolveMethod();
              if(isCallOf(streamMethod, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FILTER_METHOD, 1)) {
                ReplaceOptionalIsPresentChainFix fix = new ReplaceOptionalIsPresentChainFix(optionalProducer.getName());
                holder
                  .registerProblem(methodCall, getCallChainRange(methodCall, (PsiMethodCallExpression)streamQualifier), fix.getMessage(),
                                   new SimplifyCallChainFix(fix));
              }
            }
          }
        }
      }

      private void handleStreamForEach(PsiMethodCallExpression methodCall, PsiMethod method) {
        final String name;
        if (isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FOR_EACH_METHOD, 1)) {
          name = FOR_EACH_METHOD;
        }
        else if (isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_STREAM, FOR_EACH_ORDERED_METHOD, 1)) {
          name = FOR_EACH_ORDERED_METHOD;
        }
        else {
          return;
        }
        final PsiMethodCallExpression qualifierCall = getQualifierMethodCall(methodCall);
        if (isCollectionStream(qualifierCall)) {
          final ReplaceStreamMethodFix fix = new ReplaceStreamMethodFix(name, FOR_EACH_METHOD, true);
          holder
            .registerProblem(methodCall, getCallChainRange(methodCall, qualifierCall), fix.getMessage(), new SimplifyCallChainFix(fix));
        }
      }

      private void handleStreamCollect(PsiMethodCallExpression methodCall) {
        PsiElement parameter = methodCall.getArgumentList().getExpressions()[0];
        if(parameter instanceof PsiMethodCallExpression) {
          PsiMethodCallExpression collectorCall = (PsiMethodCallExpression)parameter;
          PsiMethod collectorMethod = collectorCall.resolveMethod();
          ReplaceCollectorFix fix;
          if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, COUNTING_COLLECTOR, 0)) {
            fix = new ReplaceCollectorFix(COUNTING_COLLECTOR, "count()", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, MIN_BY_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(MIN_BY_COLLECTOR, "min({0})", true);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, MAX_BY_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(MAX_BY_COLLECTOR, "max({0})", true);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, MAPPING_COLLECTOR, 2)) {
            fix = new ReplaceCollectorFix(MAPPING_COLLECTOR, "map({0}).collect({1})", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, REDUCING_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(REDUCING_COLLECTOR, "reduce({0})", true);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, REDUCING_COLLECTOR, 2)) {
            fix = new ReplaceCollectorFix(REDUCING_COLLECTOR, "reduce({0}, {1})", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, REDUCING_COLLECTOR, 3)) {
            fix = new ReplaceCollectorFix(REDUCING_COLLECTOR, "map({1}).reduce({0}, {2})", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, SUMMING_INT_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(SUMMING_INT_COLLECTOR, "mapToInt({0}).sum()", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, SUMMING_LONG_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(SUMMING_LONG_COLLECTOR, "mapToLong({0}).sum()", false);
          } else if(isCallOf(collectorMethod, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, SUMMING_DOUBLE_COLLECTOR, 1)) {
            fix = new ReplaceCollectorFix(SUMMING_DOUBLE_COLLECTOR, "mapToDouble({0}).sum()", false);
          } else {
            if(!(PsiUtil.resolveClassInClassTypeOnly(methodCall.getType()) instanceof PsiTypeParameter)) {
              String replacement = collectorToCollection(collectorCall);
              if (replacement != null) {
                PsiMethodCallExpression qualifier = getQualifierMethodCall(methodCall);
                if (isCollectionStream(qualifier)) {
                  PsiElement startElement = qualifier.getMethodExpression().getReferenceNameElement();
                  if (startElement != null) {
                    holder.registerProblem(methodCall, new TextRange(startElement.getTextOffset() - methodCall.getTextOffset(),
                                                                     methodCall.getTextLength()),
                                           "Can be replaced with '" + replacement + "' constructor",
                                           new SimplifyCallChainFix(new SimplifyCollectionCreationFix(replacement)));
                  }
                }
              }
            }
            return;
          }
          if (collectorCall.getArgumentList().getExpressions().length == collectorMethod.getParameterList().getParametersCount()) {
            TextRange range = methodCall.getTextRange();
            PsiElement nameElement = methodCall.getMethodExpression().getReferenceNameElement();
            if(nameElement != null) {
              range = new TextRange(nameElement.getTextOffset(), range.getEndOffset());
            }
            holder.registerProblem(methodCall, range.shiftRight(-methodCall.getTextOffset()), fix.getMessage(),
                                   new SimplifyCallChainFix(fix));
          }
        }
      }

      private void handleCollectionStream(PsiMethodCallExpression methodCall) {
        ReplaceCollectionStreamFix fix = findCollectionStreamFix(methodCall);
        if (fix != null) {
          holder.registerProblem(methodCall, null, fix.getMessage(), new SimplifyCallChainFix(fix));
        }
      }
    };
  }

  @Nullable
  private static ReplaceCollectionStreamFix findCollectionStreamFix(PsiMethodCallExpression methodCall) {
    PsiMethodCallExpression qualifierCall = getQualifierMethodCall(methodCall);
    if (qualifierCall == null) return null;
    PsiMethod qualifier = qualifierCall.resolveMethod();
    if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_ARRAYS, AS_LIST_METHOD, 1)) {
      return hasSingleArrayArgument(qualifierCall) ? new ArraysAsListSingleArrayFix() : new ReplaceWithStreamOfFix("Arrays.asList()");
    }
    else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, SINGLETON_LIST_METHOD, 1)) {
      if (!hasSingleArrayArgument(qualifierCall)) {
        return new ReplaceSingletonWithStreamOfFix("Collections.singletonList()");
      }
    }
    else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, SINGLETON_METHOD, 1)) {
      if (!hasSingleArrayArgument(qualifierCall)) {
        return new ReplaceSingletonWithStreamOfFix("Collections.singleton()");
      }
    }
    else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, EMPTY_LIST_METHOD, 0)) {
      return new ReplaceWithStreamEmptyFix(EMPTY_LIST_METHOD);
    }
    else if (isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTIONS, EMPTY_SET_METHOD, 0)) {
      return new ReplaceWithStreamEmptyFix(EMPTY_SET_METHOD);
    }
    return null;
  }

  @Contract("null -> false")
  private static boolean isCollectionStream(PsiMethodCallExpression qualifierCall) {
    if (qualifierCall == null) return false;
    PsiMethod qualifier = qualifierCall.resolveMethod();
    return isCallOf(qualifier, CommonClassNames.JAVA_UTIL_COLLECTION, STREAM_METHOD, 0);
  }

  public static PsiElement simplifyCollectionStreamCalls(PsiElement element) {
    boolean replaced = true;
    while(replaced) {
      replaced = false;
      PsiElement[] streamCalls =
        PsiTreeUtil.collectElements(element, e -> e instanceof PsiMethodCallExpression && isCollectionStream((PsiMethodCallExpression)e));
      for (PsiElement streamCall : streamCalls) {
        if (streamCall.isValid()) {
          ReplaceCollectionStreamFix fix = findCollectionStreamFix((PsiMethodCallExpression)streamCall);
          if (fix != null) {
            PsiElement replacement = fix.simplify((PsiMethodCallExpression)streamCall);
            if(replacement != null) {
              replaced = true;
              if(element == streamCall) {
                element = replacement;
              }
            }
          }
        }
      }
    }
    return element;
  }

  @Nullable
  private static PsiArrayType getArrayType(PsiMethodCallExpression call) {
    PsiType type = call.getType();
    if(!(type instanceof PsiArrayType)) return null;
    PsiArrayType candidate = (PsiArrayType)type;
    PsiExpression[] args = call.getArgumentList().getExpressions();
    if(args.length == 0) return candidate;
    if(args.length != 1) return null;
    PsiExpression supplier = args[0];
    if(supplier instanceof PsiMethodReferenceExpression) {
      // like toArray(String[]::new)
      PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression)supplier;
      PsiTypeElement qualifierType = methodRef.getQualifierType();
      if (methodRef.isConstructor() && qualifierType != null && candidate.isAssignableFrom(qualifierType.getType())) {
        return candidate;
      }
    } else if(supplier instanceof PsiLambdaExpression) {
      // like toArray(size -> new String[size])
      PsiLambdaExpression lambda = (PsiLambdaExpression)supplier;
      PsiParameter[] parameters = lambda.getParameterList().getParameters();
      if(parameters.length != 1) return null;
      PsiParameter sizeParameter = parameters[0];
      PsiExpression body = LambdaUtil.extractSingleExpressionFromBody(lambda.getBody());
      if(body instanceof PsiNewExpression) {
        PsiNewExpression newExpression = (PsiNewExpression)body;
        PsiExpression[] dimensions = newExpression.getArrayDimensions();
        PsiType newExpressionType = newExpression.getType();
        if (dimensions.length != 0 &&
            ExpressionUtils.isReferenceTo(dimensions[0], sizeParameter) &&
            newExpressionType != null &&
            candidate.isAssignableFrom(newExpressionType)) {
          return candidate;
        }
      }
    }
    return null;
  }

  @Contract("null -> false")
  private static boolean isCollectionConstructor(PsiMethod ctor) {
    if (ctor == null || !ctor.getModifierList().hasExplicitModifier(PsiModifier.PUBLIC)) return false;
    PsiParameterList list = ctor.getParameterList();
    if (list.getParametersCount() != 1) return false;
    PsiParameter parameter = list.getParameters()[0];
    PsiTypeElement typeElement = parameter.getTypeElement();
    if (typeElement == null) return false;
    PsiType type = typeElement.getType();
    PsiClass aClass = PsiUtil.resolveClassInClassTypeOnly(type);
    if(aClass == null || !CommonClassNames.JAVA_UTIL_COLLECTION.equals(aClass.getQualifiedName())) return false;
    PsiClass curClass = ctor.getContainingClass();
    LOG.assertTrue(curClass != null);
    // Do not perform deep check for standard Collections
    if(Objects.requireNonNull(curClass.getQualifiedName()).startsWith("java.util.")) return true;
    PsiSubstitutor substitutor = TypeConversionUtil.getSuperClassSubstitutor(aClass, curClass, PsiSubstitutor.EMPTY);
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(ctor.getProject());
    PsiClassType collectionType = factory.createType(aClass, substitutor);
    if (!(type.isAssignableFrom(collectionType))) return false;
    // Check that body either contains addAll(arg) or super(arg)
    return !PsiTreeUtil.processElements(ctor.getBody(), e -> {
      if(e instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression call = (PsiMethodCallExpression)e;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if(args.length == 1 && ExpressionUtils.isReferenceTo(args[0], parameter)) {
          PsiReferenceExpression methodExpression = call.getMethodExpression();
          if("super".equals(methodExpression.getReferenceName())) {
            return !isCollectionConstructor(call.resolveMethod());
          }
          if("addAll".equals(methodExpression.getReferenceName())) {
            PsiExpression qualifier = methodExpression.getQualifierExpression();
            if(qualifier == null || qualifier instanceof PsiThisExpression) {
              return false;
            }
          }
        }
      }
      return true;
    });
  }

  private static final Key<ParameterizedCachedValue<Boolean, PsiClass>> HAS_COLLECTION_CONSTRUCTOR = Key.create("HasCollectionConstructor");
  private static final ParameterizedCachedValueProvider<Boolean, PsiClass> HAS_COLLECTION_CONSTRUCTOR_PROVIDER = psiClass -> {
    boolean hasCollectionConstructor =
      Stream.of(psiClass.getConstructors()).anyMatch(SimplifyStreamApiCallChainsInspection::isCollectionConstructor);
    return CachedValueProvider.Result.create(hasCollectionConstructor, psiClass);
  };

  @Nullable
  private static String collectorToCollection(PsiMethodCallExpression call) {
    PsiMethod method = call.resolveMethod();
    if(isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, TO_LIST_COLLECTOR, 0)) {
      return CommonClassNames.JAVA_UTIL_ARRAY_LIST;
    }
    if(isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, TO_SET_COLLECTOR, 0)) {
      return CommonClassNames.JAVA_UTIL_HASH_SET;
    }
    if(isCallOf(method, CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS, TO_COLLECTION_COLLECTOR, 1)) {
      PsiExpression[] expressions = call.getArgumentList().getExpressions();
      if(expressions.length == 1 && expressions[0] instanceof PsiMethodReferenceExpression) {
        PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression)expressions[0];
        if(methodRef.isConstructor()) {
          PsiElement element = methodRef.resolve();
          if(element instanceof PsiMethod) {
            PsiMethod ctor = (PsiMethod)element;
            if(ctor.getParameterList().getParametersCount() == 0) {
              PsiClass aClass = ctor.getContainingClass();
              if (aClass != null &&
                  CachedValuesManager.getManager(aClass.getProject())
                    .getParameterizedCachedValue(aClass, HAS_COLLECTION_CONSTRUCTOR, HAS_COLLECTION_CONSTRUCTOR_PROVIDER, false, aClass)) {
                return aClass.getQualifiedName();
              }
            }
          }
        }
      }
    }
    return null;
  }

  static boolean isParentNegated(PsiMethodCallExpression methodCall) {
    PsiElement parent = PsiUtil.skipParenthesizedExprUp(methodCall.getParent());
    return parent instanceof PsiExpression && BoolUtils.isNegation((PsiExpression)parent);
  }

  static boolean isArgumentLambdaNegated(PsiMethodCallExpression methodCall) {
    PsiExpression[] expressions = methodCall.getArgumentList().getExpressions();
    if(expressions.length != 1) return false;
    PsiExpression arg = expressions[0];
    if(!(arg instanceof PsiLambdaExpression)) return false;
    PsiElement body = ((PsiLambdaExpression)arg).getBody();
    return body instanceof PsiExpression && BoolUtils.isNegation((PsiExpression)body);
  }

  static boolean hasSingleArrayArgument(PsiMethodCallExpression qualifierCall) {
    final PsiExpression[] argumentExpressions = qualifierCall.getArgumentList().getExpressions();
    if (argumentExpressions.length == 1) {
      PsiType type = argumentExpressions[0].getType();
      if (type instanceof PsiArrayType) {
        PsiType methodType = qualifierCall.getType();
        // Rule out cases like Arrays.<String[]>asList(stringArr)
        if (methodType instanceof PsiClassType) {
          PsiType[] parameters = ((PsiClassType)methodType).getParameters();
          if (parameters.length == 1 && TypeConversionUtil.isAssignable(parameters[0], type)
              && !TypeConversionUtil.isAssignable(parameters[0], ((PsiArrayType)type).getComponentType())) {
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }

  @Nullable
  static PsiMethodCallExpression getQualifierMethodCall(PsiMethodCallExpression methodCall) {
    final PsiExpression qualifierExpression = methodCall.getMethodExpression().getQualifierExpression();
    if (qualifierExpression instanceof PsiMethodCallExpression) {
      return (PsiMethodCallExpression)qualifierExpression;
    }
    return null;
  }

  @NotNull
  protected static TextRange getCallChainRange(@NotNull PsiMethodCallExpression expression,
                                               @NotNull PsiMethodCallExpression qualifierExpression) {
    final PsiReferenceExpression qualifierMethodExpression = qualifierExpression.getMethodExpression();
    final PsiElement qualifierNameElement = qualifierMethodExpression.getReferenceNameElement();
    final int startOffset = (qualifierNameElement != null ? qualifierNameElement : qualifierMethodExpression).getTextOffset();
    final int endOffset = expression.getMethodExpression().getTextRange().getEndOffset();
    return new TextRange(startOffset, endOffset).shiftRight(-expression.getTextOffset());
  }

  @Contract("null, _, _, _ -> false")
  protected static boolean isCallOf(@Nullable PsiMethod method,
                                    @NotNull String className,
                                    @NotNull String methodName,
                                    int parametersCount) {
    if (method == null) return false;
    if (methodName.equals(method.getName()) && method.getParameterList().getParametersCount() == parametersCount) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass != null && className.equals(containingClass.getQualifiedName())) {
        return true;
      }
    }
    return false;
  }

  @Contract("null, _, _ -> false")
  static boolean isStreamCall(@Nullable PsiMethod method, @NotNull String methodName, boolean checkSingleParameter) {
    if (method == null || !methodName.equals(method.getName()) ||
        (checkSingleParameter && method.getParameterList().getParametersCount() != 1)) {
      return false;
    }
    final PsiClass containingClass = method.getContainingClass();
    return containingClass != null && InheritanceUtil.isInheritor(containingClass, CommonClassNames.JAVA_UTIL_STREAM_BASE_STREAM);
  }

  interface CallChainFix {
    String getName();
    void applyFix(@NotNull Project project, PsiElement element);
  }

  private static class SimplifyCallChainFix implements LocalQuickFix {
    private final CallChainFix myFix;

    SimplifyCallChainFix(CallChainFix fix) {
      myFix = fix;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return myFix.getName();
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Simplify stream call chain";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      myFix.applyFix(project, descriptor.getStartElement());
    }
  }

  private static abstract class ReplaceCollectionStreamFix implements CallChainFix {
    private final String myClassName;
    private final String myMethodName;
    private final String myQualifierCall;

    private ReplaceCollectionStreamFix(String qualifierCall, String className, String methodName) {
      myQualifierCall = qualifierCall;
      myClassName = className;
      myMethodName = methodName;
    }

    @NotNull
    public String getMessage() {
      return myQualifierCall + ".stream() can be replaced with " + ClassUtil.extractClassName(myClassName) + "." + myMethodName + "()";
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace " + myQualifierCall + ".stream() with " + ClassUtil.extractClassName(myClassName) + "." + myMethodName + "()";
    }

    @Nullable
    protected String getTypeParameter(@NotNull PsiMethodCallExpression qualifierCall) {
      PsiType[] parameters = qualifierCall.getMethodExpression().getTypeParameters();
      return parameters.length == 1 ? parameters[0].getCanonicalText() : null;
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if (!(element instanceof PsiMethodCallExpression)) return;
      simplify((PsiMethodCallExpression)element);
    }

    @Nullable
    private PsiElement simplify(PsiMethodCallExpression streamCall) {
      PsiMethodCallExpression collectionCall = getQualifierMethodCall(streamCall);
      if (collectionCall == null) return null;
      streamCall.getArgumentList().replace(collectionCall.getArgumentList());
      String typeParameter = getTypeParameter(collectionCall);
      String replacement;
      if (typeParameter != null) {
        replacement = myClassName + ".<" + typeParameter + ">" + myMethodName;
      }
      else {
        replacement = myClassName + "." + myMethodName;
      }
      Project project = streamCall.getProject();
      PsiExpression newMethodExpression = JavaPsiFacade.getElementFactory(project).createExpressionFromText(replacement, streamCall);
      return JavaCodeStyleManager.getInstance(project).shortenClassReferences(streamCall.getMethodExpression().replace(newMethodExpression));
    }
  }

  private static class ReplaceWithStreamOfFix extends ReplaceCollectionStreamFix {
    private ReplaceWithStreamOfFix(String qualifierCall) {
      super(qualifierCall, CommonClassNames.JAVA_UTIL_STREAM_STREAM, OF_METHOD);
    }
  }

  private static class ReplaceSingletonWithStreamOfFix extends ReplaceWithStreamOfFix {
    private ReplaceSingletonWithStreamOfFix(String qualifierCall) {
      super(qualifierCall);
    }

    @Nullable
    @Override
    protected String getTypeParameter(@NotNull PsiMethodCallExpression qualifierCall) {
      String typeParameter = super.getTypeParameter(qualifierCall);
      if (typeParameter != null) {
        return typeParameter;
      }
      PsiType[] argTypes = qualifierCall.getArgumentList().getExpressionTypes();
      if (argTypes.length == 1) {
        PsiType argType = argTypes[0];
        if (argType instanceof PsiArrayType) {
          return argType.getCanonicalText();
        }
      }
      return null;
    }
  }

  private static class ArraysAsListSingleArrayFix extends ReplaceCollectionStreamFix {
    private ArraysAsListSingleArrayFix() {
      super("Arrays.asList()", CommonClassNames.JAVA_UTIL_ARRAYS, STREAM_METHOD);
    }
  }

  private static class ReplaceWithStreamEmptyFix extends ReplaceCollectionStreamFix {
    private ReplaceWithStreamEmptyFix(String qualifierMethodName) {
      super("Collections." + qualifierMethodName + "()", CommonClassNames.JAVA_UTIL_STREAM_STREAM, EMPTY_METHOD);
    }
  }

  static class ReplaceStreamMethodFix implements CallChainFix {
    private final String myStreamMethod;
    private final String myCollectionMethod;
    private final boolean myChangeSemantics;

    public ReplaceStreamMethodFix(String streamMethod, String collectionMethod, boolean changeSemantics) {
      myStreamMethod = streamMethod;
      myCollectionMethod = collectionMethod;
      myChangeSemantics = changeSemantics;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace Collection.stream()." + myStreamMethod +
             "() with Collection." + myCollectionMethod + "()" +
             (myChangeSemantics ? " (may change semantics)" : "");
    }

    @NotNull
    public String getMessage() {
      return "Collection.stream()." + myStreamMethod +
             "() can be replaced with Collection." + myCollectionMethod + "()" +
             (myChangeSemantics ? " (may change semantics)" : "");
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if (!(element instanceof PsiMethodCallExpression)) return;
      PsiMethodCallExpression streamMethodCall = (PsiMethodCallExpression)element;
      PsiMethodCallExpression collectionStreamCall = getQualifierMethodCall(streamMethodCall);
      if (collectionStreamCall == null) return;
      PsiExpression collectionExpression = collectionStreamCall.getMethodExpression().getQualifierExpression();
      if (collectionExpression == null) return;
      collectionStreamCall.replace(collectionExpression);
      if (!myStreamMethod.equals(myCollectionMethod)) {
        streamMethodCall.getMethodExpression().handleElementRename(myCollectionMethod);
      }
    }
  }

  private static class ReplaceCollectorFix implements CallChainFix {
    private final String myCollector;
    private final String myStreamSequence;
    private final String myStreamSequenceStripped;
    private final boolean myChangeSemantics;

    public ReplaceCollectorFix(String collector, String streamSequence, boolean changeSemantics) {
      myCollector = collector;
      myStreamSequence = streamSequence;
      myStreamSequenceStripped = streamSequence.replaceAll("\\([^)]+\\)", "()");
      myChangeSemantics = changeSemantics;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace Stream.collect(" + myCollector +
             "()) with Stream." + myStreamSequenceStripped +
             (myChangeSemantics ? " (may change semantics when result is null)" : "");
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if (element instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression collectCall = (PsiMethodCallExpression)element;
        PsiExpression qualifierExpression = collectCall.getMethodExpression().getQualifierExpression();
        if (qualifierExpression != null) {
          PsiElement parameter = collectCall.getArgumentList().getExpressions()[0];
          if (parameter instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression collectorCall = (PsiMethodCallExpression)parameter;
            PsiExpression[] collectorArgs = collectorCall.getArgumentList().getExpressions();
            String result = MessageFormat.format(myStreamSequence, Arrays.stream(collectorArgs).map(PsiExpression::getText).toArray());
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiExpression replacement = factory.createExpressionFromText(qualifierExpression.getText() + "." + result, collectCall);
            addBoxingIfNecessary(factory, collectCall.replace(replacement));
          }
        }
      }
    }

    /*
    Replacements like .collect(counting()) -> .count() change the result type from boxed to primitive
    In rare cases it's necessary to add cast to return back to boxed type
    example:
    List<Integer> intList; List<String> stringList;
    intList.remove(stringList.stream().collect(summingInt(String::length)) -- remove given element
    intList.remove(stringList.stream().mapToInt(String::length).sum()) -- remove element by index
    */
    private static void addBoxingIfNecessary(PsiElementFactory factory, PsiElement expression) {
      if(expression instanceof PsiExpression) {
        PsiType type = ((PsiExpression)expression).getType();
        if(type instanceof PsiPrimitiveType) {
          PsiClassType boxedType = ((PsiPrimitiveType)type).getBoxedType(expression);
          if(boxedType != null) {
            PsiExpression castExpression =
              factory.createExpressionFromText("(" + boxedType.getCanonicalText() + ") " + expression.getText(), expression);
            PsiElement cast = expression.replace(castExpression);
            if (cast instanceof PsiTypeCastExpression && RedundantCastUtil.isCastRedundant((PsiTypeCastExpression)cast)) {
              RedundantCastUtil.removeCast((PsiTypeCastExpression)cast);
            }
          }
        }
      }
    }

    @NotNull
    public String getMessage() {
      return "Stream.collect(" + myCollector +
             "()) can be replaced with Stream." + myStreamSequenceStripped +
             (myChangeSemantics ? " (may change semantics when result is null)" : "");
    }
  }

  private static class ReplaceOptionalIsPresentChainFix implements CallChainFix {
    private final String myFindMethodName;

    ReplaceOptionalIsPresentChainFix(String findMethodName) {
      myFindMethodName = findMethodName;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return "Replace Stream.filter()." + myFindMethodName + "().isPresent() with Stream.anyMatch()";
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if (element instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression isPresentCall = (PsiMethodCallExpression)element;
        PsiExpression isPresentQualifier = isPresentCall.getMethodExpression().getQualifierExpression();
        if(isPresentQualifier instanceof PsiMethodCallExpression) {
          PsiMethodCallExpression findCall = (PsiMethodCallExpression)isPresentQualifier;
          PsiExpression findQualifier = findCall.getMethodExpression().getQualifierExpression();
          if(findQualifier instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression filterCall = (PsiMethodCallExpression)findQualifier;
            PsiElement replacement = element.replace(filterCall);
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiElement filterName = ((PsiMethodCallExpression)replacement).getMethodExpression().getReferenceNameElement();
            LOG.assertTrue(filterName != null);
            filterName.replace(factory.createIdentifier(ANY_MATCH_METHOD));
          }
        }
      }
    }

    @NotNull
    public String getMessage() {
      return "Stream.filter()." + myFindMethodName + "().isPresent() can be replaced with Stream.anyMatch()";
    }
  }

  private static class SimplifyMatchNegationFix implements CallChainFix {
    private final String myFrom, myTo;

    private SimplifyMatchNegationFix(String from, String to) {
      myFrom = from;
      myTo = to;
    }

    @Override
    public String getName() {
      return "Replace "+myFrom+" with "+myTo+"(...)";
    }

    public String getMessage() {
      return myFrom+" can be replaced with "+myTo+"(...)";
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if(element instanceof PsiIdentifier) {
        String from = element.getText();
        boolean removeParentNegation;
        boolean removeLambdaNegation;
        switch(from) {
          case ALL_MATCH_METHOD:
            removeLambdaNegation = true;
            removeParentNegation = myTo.equals(ANY_MATCH_METHOD);
            break;
          case ANY_MATCH_METHOD:
            removeParentNegation = true;
            removeLambdaNegation = myTo.equals(ALL_MATCH_METHOD);
            break;
          case NONE_MATCH_METHOD:
            removeParentNegation = myTo.equals(ANY_MATCH_METHOD);
            removeLambdaNegation = myTo.equals(ALL_MATCH_METHOD);
            break;
          default:
            return;
        }
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
        if (methodCall == null) return;
        if (removeParentNegation && !isParentNegated(methodCall)) return;
        if (removeLambdaNegation && !isArgumentLambdaNegated(methodCall)) return;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        element.replace(factory.createIdentifier(myTo));
        if (removeLambdaNegation) {
          // Casts and array bounds already checked in isArgumentLambdaNegated
          PsiExpression body = (PsiExpression)((PsiLambdaExpression)methodCall.getArgumentList().getExpressions()[0]).getBody();
          PsiExpression negated = BoolUtils.getNegated(body);
          LOG.assertTrue(negated != null);
          body.replace(negated);
        }
        if (removeParentNegation) {
          PsiUtil.skipParenthesizedExprUp(methodCall.getParent()).replace(methodCall);
        }
      }
    }
  }

  private static class SimplifyCollectionCreationFix implements CallChainFix {
    private String myReplacement;

    public SimplifyCollectionCreationFix(String replacement) {
      myReplacement = replacement;
    }

    @Override
    public String getName() {
      return "Replace with '"+myReplacement+"' constructor";
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if(!(element instanceof PsiMethodCallExpression)) return;
      PsiMethodCallExpression collectCall = (PsiMethodCallExpression)element;
      PsiType type = collectCall.getType();
      PsiClass resolvedType = PsiUtil.resolveClassInClassTypeOnly(type);
      if(resolvedType == null || resolvedType instanceof PsiTypeParameter) return;
      PsiMethodCallExpression streamCall = getQualifierMethodCall(collectCall);
      if(streamCall == null) return;
      PsiExpression collectionExpression = streamCall.getMethodExpression().getQualifierExpression();
      if(collectionExpression == null) return;
      String typeText = type.getCanonicalText();
      if(CommonClassNames.JAVA_UTIL_LIST.equals(resolvedType.getQualifiedName()) ||
         CommonClassNames.JAVA_UTIL_SET.equals(resolvedType.getQualifiedName())) {
        PsiType[] parameters = ((PsiClassType)type).getParameters();
        if(parameters.length != 1) return;
        typeText = myReplacement + "<" + parameters[0].getCanonicalText() + ">";
      }
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      PsiExpression result = factory
        .createExpressionFromText("new " + typeText + "(" + collectionExpression.getText() + ")", element);
      PsiNewExpression newExpression = (PsiNewExpression)element.replace(result);
      PsiJavaCodeReferenceElement classReference = newExpression.getClassOrAnonymousClassReference();
      LOG.assertTrue(classReference != null);
      JavaCodeStyleManager.getInstance(project).shortenClassReferences(classReference);
      if (PsiDiamondTypeUtil.canCollapseToDiamond(newExpression, newExpression, null)) {
        PsiDiamondTypeUtil.replaceExplicitWithDiamond(classReference.getParameterList());
      }
      CodeStyleManager.getInstance(project).reformat(newExpression);
    }
  }

  private static class ReplaceWithBoxedFix implements CallChainFix {
    @Override
    public String getName() {
      return "Replace with 'boxed'";
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      if(!(element instanceof PsiIdentifier)) return;
      PsiElement parent = element.getParent();
      if(!(parent instanceof PsiReferenceExpression)) return;
      PsiElement grandParent = parent.getParent();
      if(!(grandParent instanceof PsiMethodCallExpression)) return;
      PsiExpression[] args = ((PsiMethodCallExpression)grandParent).getArgumentList().getExpressions();
      if(args.length != 1) return;
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      element.replace(factory.createIdentifier("boxed"));
      args[0].delete();
      ((PsiMethodCallExpression)grandParent).getTypeArgumentList().delete();
    }
  }

  private static class ReplaceWithToArrayFix implements CallChainFix {
    private final String myReplacement;

    private ReplaceWithToArrayFix(String replacement) {
      myReplacement = replacement;
    }

    @Override
    public String getName() {
      return "Replace 'collection.stream().toArray()' with 'collection.toArray()'";
    }

    @Override
    public void applyFix(@NotNull Project project, PsiElement element) {
      PsiMethodCallExpression toArrayCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
      if (toArrayCall == null) return;
      PsiExpression qualifier = toArrayCall.getMethodExpression().getQualifierExpression();
      if(!(qualifier instanceof PsiMethodCallExpression)) return;
      PsiMethodCallExpression streamCall = (PsiMethodCallExpression)qualifier;
      PsiExpression collectionExpression = streamCall.getMethodExpression().getQualifierExpression();
      if(collectionExpression == null) return;
      CommentTracker ct = new CommentTracker();
      PsiElement result = ct.replaceAndRestoreComments(toArrayCall, ct.text(collectionExpression) + ".toArray(" + myReplacement + ")");
      CodeStyleManager.getInstance(project).reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(result));
    }
  }
}
