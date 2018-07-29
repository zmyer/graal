/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.interop.java;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;

@SuppressWarnings("deprecation")
abstract class ExecuteMethodNode extends Node {
    static final int LIMIT = 3;
    private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

    ExecuteMethodNode() {
    }

    static ExecuteMethodNode create() {
        return ExecuteMethodNodeGen.create();
    }

    public final Object execute(JavaMethodDesc method, Object obj, Object[] args, Object languageContext) {
        try {
            return executeImpl(method, obj, args, languageContext);
        } catch (ClassCastException | NullPointerException e) {
            // conversion failed by ToJavaNode
            throw UnsupportedTypeException.raise(e, args);
        } catch (InteropException e) {
            throw e.raise();
        } catch (Throwable e) {
            throw JavaInteropReflect.rethrow(JavaInterop.wrapHostException(languageContext, e));
        }
    }

    protected abstract Object executeImpl(JavaMethodDesc method, Object obj, Object[] args, Object languageContext) throws InteropException;

    static ToJavaNode[] createToJava(int argsLength) {
        ToJavaNode[] toJava = new ToJavaNode[argsLength];
        for (int i = 0; i < argsLength; i++) {
            toJava[i] = ToJavaNode.create();
        }
        return toJava;
    }

    @SuppressWarnings("unused")
    @ExplodeLoop
    @Specialization(guards = {"!method.isVarArgs()", "method == cachedMethod"}, limit = "LIMIT")
    Object doFixed(SingleMethodDesc method, Object obj, Object[] args, Object languageContext,
                    @Cached("method") SingleMethodDesc cachedMethod,
                    @Cached("createToJava(method.getParameterCount())") ToJavaNode[] toJavaNodes,
                    @Cached("createClassProfile()") ValueProfile receiverProfile) {
        int arity = cachedMethod.getParameterCount();
        if (args.length != arity) {
            throw ArityException.raise(arity, args.length);
        }
        Class<?>[] types = cachedMethod.getParameterTypes();
        Type[] genericTypes = cachedMethod.getGenericParameterTypes();
        Object[] convertedArguments = new Object[args.length];
        for (int i = 0; i < toJavaNodes.length; i++) {
            convertedArguments[i] = toJavaNodes[i].execute(args[i], types[i], genericTypes[i], languageContext);
        }

        return doInvoke(cachedMethod, receiverProfile.profile(obj), convertedArguments, languageContext);
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"method.isVarArgs()", "method == cachedMethod"}, limit = "LIMIT")
    Object doVarArgs(SingleMethodDesc method, Object obj, Object[] args, Object languageContext,
                    @Cached("method") SingleMethodDesc cachedMethod,
                    @Cached("create()") ToJavaNode toJavaNode,
                    @Cached("createClassProfile()") ValueProfile receiverProfile) {
        int parameterCount = cachedMethod.getParameterCount();
        int minArity = parameterCount - 1;
        if (args.length < minArity) {
            throw ArityException.raise(minArity, args.length);
        }
        Class<?>[] types = cachedMethod.getParameterTypes();
        Type[] genericTypes = cachedMethod.getGenericParameterTypes();
        Object[] convertedArguments = new Object[args.length];
        for (int i = 0; i < minArity; i++) {
            convertedArguments[i] = toJavaNode.execute(args[i], types[i], genericTypes[i], languageContext);
        }
        if (asVarArgs(args, cachedMethod)) {
            for (int i = minArity; i < args.length; i++) {
                Class<?> expectedType = types[minArity].getComponentType();
                Type expectedGenericType = getGenericComponentType(genericTypes[minArity]);
                convertedArguments[i] = toJavaNode.execute(args[i], expectedType, expectedGenericType, languageContext);
            }
            convertedArguments = createVarArgsArray(cachedMethod, convertedArguments, parameterCount);
        } else {
            convertedArguments[minArity] = toJavaNode.execute(args[minArity], types[minArity], genericTypes[minArity], languageContext);
        }
        return doInvoke(cachedMethod, receiverProfile.profile(obj), convertedArguments, languageContext);
    }

    @Specialization(replaces = {"doFixed", "doVarArgs"})
    Object doSingleUncached(SingleMethodDesc method, Object obj, Object[] args, Object languageContext,
                    @Cached("create()") ToJavaNode toJavaNode,
                    @Cached("createBinaryProfile()") ConditionProfile isVarArgsProfile) {
        int parameterCount = method.getParameterCount();
        int minArity = method.isVarArgs() ? parameterCount - 1 : parameterCount;
        if (args.length < minArity) {
            throw ArityException.raise(minArity, args.length);
        }
        Object[] convertedArguments = prepareArgumentsUncached(method, args, languageContext, toJavaNode, isVarArgsProfile);
        return doInvoke(method, obj, convertedArguments, languageContext);
    }

    // Note: checkArgTypes must be evaluated after selectOverload.
    @SuppressWarnings("unused")
    @ExplodeLoop
    @Specialization(guards = {"method == cachedMethod", "checkArgTypes(args, cachedArgTypes, toJavaNode, asVarArgs)"}, limit = "LIMIT")
    Object doOverloadedCached(OverloadedMethodDesc method, Object obj, Object[] args, Object languageContext,
                    @Cached("method") OverloadedMethodDesc cachedMethod,
                    @Cached("create()") ToJavaNode toJavaNode,
                    @Cached(value = "createArgTypesArray(args)", dimensions = 1) Type[] cachedArgTypes,
                    @Cached("selectOverload(method, args, languageContext, cachedArgTypes)") SingleMethodDesc overload,
                    @Cached("asVarArgs(args, overload)") boolean asVarArgs,
                    @Cached("createClassProfile()") ValueProfile receiverProfile) {
        assert overload == selectOverload(method, args, languageContext);
        Class<?>[] types = overload.getParameterTypes();
        Type[] genericTypes = overload.getGenericParameterTypes();
        Object[] convertedArguments = new Object[cachedArgTypes.length];
        if (asVarArgs) {
            assert overload.isVarArgs();
            int parameterCount = overload.getParameterCount();
            for (int i = 0; i < cachedArgTypes.length; i++) {
                Class<?> expectedType = i < parameterCount - 1 ? types[i] : types[parameterCount - 1].getComponentType();
                Type expectedGenericType = i < parameterCount - 1 ? genericTypes[i] : getGenericComponentType(genericTypes[parameterCount - 1]);
                convertedArguments[i] = toJavaNode.execute(args[i], expectedType, expectedGenericType, languageContext);
            }
            convertedArguments = createVarArgsArray(overload, convertedArguments, parameterCount);
        } else {
            for (int i = 0; i < cachedArgTypes.length; i++) {
                convertedArguments[i] = toJavaNode.execute(args[i], types[i], genericTypes[i], languageContext);
            }
        }
        return doInvoke(overload, receiverProfile.profile(obj), convertedArguments, languageContext);
    }

    @Specialization(replaces = "doOverloadedCached")
    Object doOverloadedUncached(OverloadedMethodDesc method, Object obj, Object[] args, Object languageContext,
                    @Cached("create()") ToJavaNode toJavaNode,
                    @Cached("createBinaryProfile()") ConditionProfile isVarArgsProfile) {
        SingleMethodDesc overload = selectOverload(method, args, languageContext);
        Object[] convertedArguments = prepareArgumentsUncached(overload, args, languageContext, toJavaNode, isVarArgsProfile);
        return doInvoke(overload, obj, convertedArguments, languageContext);
    }

    private static Object[] prepareArgumentsUncached(SingleMethodDesc method, Object[] args, Object languageContext, ToJavaNode toJavaNode, ConditionProfile isVarArgsProfile) {
        Class<?>[] types = method.getParameterTypes();
        Type[] genericTypes = method.getGenericParameterTypes();
        Object[] convertedArguments = new Object[args.length];
        if (isVarArgsProfile.profile(method.isVarArgs()) && asVarArgs(args, method)) {
            int parameterCount = method.getParameterCount();
            for (int i = 0; i < args.length; i++) {
                Class<?> expectedType = i < parameterCount - 1 ? types[i] : types[parameterCount - 1].getComponentType();
                Type expectedGenericType = i < parameterCount - 1 ? genericTypes[i] : getGenericComponentType(genericTypes[parameterCount - 1]);
                convertedArguments[i] = toJavaNode.execute(args[i], expectedType, expectedGenericType, languageContext);
            }
            convertedArguments = createVarArgsArray(method, convertedArguments, parameterCount);
        } else {
            for (int i = 0; i < args.length; i++) {
                convertedArguments[i] = toJavaNode.execute(args[i], types[i], genericTypes[i], languageContext);
            }
        }
        return convertedArguments;
    }

    static Type[] createArgTypesArray(Object[] args) {
        return new Type[args.length];
    }

    private static void fillArgTypesArray(Object[] args, Type[] cachedArgTypes, SingleMethodDesc selected, boolean varArgs, List<SingleMethodDesc> applicable, int priority) {
        if (cachedArgTypes == null) {
            return;
        }
        boolean multiple = applicable.size() > 1;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            Class<?> targetType = getParameterType(selected.getParameterTypes(), i, varArgs);

            Type argType;
            if (arg == null) {
                argType = null;
            } else if (multiple && ToJavaNode.isAssignableFromTrufflePrimitiveType(targetType)) {
                Class<?> currentTargetType = targetType;

                Collection<Class<?>> otherPossibleTypes = new ArrayList<>();
                for (SingleMethodDesc other : applicable) {
                    if (other == selected) {
                        continue;
                    }
                    if (other.isVarArgs() != varArgs) {
                        continue;
                    }
                    Class<?> paramType = getParameterType(other.getParameterTypes(), i, varArgs);
                    if (paramType == targetType) {
                        continue;
                    } else if (otherPossibleTypes.contains(paramType)) {
                        continue;
                    }
                    /*
                     * If the other param type is a subtype of this param type, and the argument is
                     * not already a subtype of it, another value may change the outcome of overload
                     * resolution, so we have to guard against it. If the argument is already a
                     * subtype of the other param type, we must not guard against the other param
                     * type, and we do not have to as this overload was better fit regardless.
                     */
                    if ((ToJavaNode.isAssignableFromTrufflePrimitiveType(paramType) || ToJavaNode.isAssignableFromTrufflePrimitiveType(targetType)) &&
                                    isAssignableFrom(targetType, paramType) && !isSubtypeOf(arg, paramType)) {
                        otherPossibleTypes.add(paramType);
                    }
                }

                argType = new PrimitiveType(currentTargetType, otherPossibleTypes.toArray(EMPTY_CLASS_ARRAY), priority);
            } else if (arg instanceof JavaObject) {
                argType = new JavaObjectType(((JavaObject) arg).getObjectClass());
            } else {
                argType = arg.getClass();
            }

            cachedArgTypes[i] = argType;
        }

        assert checkArgTypes(args, cachedArgTypes, ToJavaNode.create(), false) : Arrays.toString(cachedArgTypes);
    }

    @ExplodeLoop
    static boolean checkArgTypes(Object[] args, Type[] argTypes, ToJavaNode toJavaNode, @SuppressWarnings("unused") boolean dummy) {
        if (args.length != argTypes.length) {
            return false;
        }
        for (int i = 0; i < argTypes.length; i++) {
            Type argType = argTypes[i];
            Object arg = args[i];
            if (argType == null) {
                if (arg != null) {
                    return false;
                }
            } else {
                if (arg == null) {
                    return false;
                }
                if (argType instanceof Class<?>) {
                    if (arg.getClass() != argType) {
                        return false;
                    }
                } else if (argType instanceof JavaObjectType) {
                    if (!(arg instanceof JavaObject && ((JavaObject) arg).getObjectClass() == ((JavaObjectType) argType).clazz)) {
                        return false;
                    }
                } else if (argType instanceof PrimitiveType) {
                    if (!((PrimitiveType) argType).test(arg, toJavaNode)) {
                        return false;
                    }
                } else {
                    CompilerDirectives.transferToInterpreter();
                    throw new IllegalArgumentException(String.valueOf(argType));
                }
            }
        }
        return true;
    }

    @TruffleBoundary
    static boolean asVarArgs(Object[] args, SingleMethodDesc overload) {
        if (overload.isVarArgs()) {
            int parameterCount = overload.getParameterCount();
            if (args.length == parameterCount) {
                return !isSubtypeOf(args[parameterCount - 1], overload.getParameterTypes()[parameterCount - 1]);
            } else {
                assert args.length != parameterCount;
                return true;
            }
        } else {
            return false;
        }
    }

    static Class<?> primitiveTypeToBoxedType(Class<?> primitiveType) {
        assert primitiveType.isPrimitive();
        if (primitiveType == boolean.class) {
            return Boolean.class;
        } else if (primitiveType == byte.class) {
            return Byte.class;
        } else if (primitiveType == short.class) {
            return Short.class;
        } else if (primitiveType == char.class) {
            return Character.class;
        } else if (primitiveType == int.class) {
            return Integer.class;
        } else if (primitiveType == long.class) {
            return Long.class;
        } else if (primitiveType == float.class) {
            return Float.class;
        } else if (primitiveType == double.class) {
            return Double.class;
        } else {
            throw new IllegalArgumentException();
        }
    }

    static Class<?> boxedTypeToPrimitiveType(Class<?> primitiveType) {
        if (primitiveType == Boolean.class) {
            return boolean.class;
        } else if (primitiveType == Byte.class) {
            return byte.class;
        } else if (primitiveType == Short.class) {
            return short.class;
        } else if (primitiveType == Character.class) {
            return char.class;
        } else if (primitiveType == Integer.class) {
            return int.class;
        } else if (primitiveType == Long.class) {
            return long.class;
        } else if (primitiveType == Float.class) {
            return float.class;
        } else if (primitiveType == Double.class) {
            return double.class;
        } else {
            return null;
        }
    }

    @TruffleBoundary
    static SingleMethodDesc selectOverload(OverloadedMethodDesc method, Object[] args, Object languageContext) {
        return selectOverload(method, args, languageContext, null);
    }

    @TruffleBoundary
    static SingleMethodDesc selectOverload(OverloadedMethodDesc method, Object[] args, Object languageContext, Type[] cachedArgTypes) {
        ToJavaNode toJavaNode = ToJavaNode.create();
        SingleMethodDesc[] overloads = method.getOverloads();
        List<SingleMethodDesc> applicableByArity = new ArrayList<>();
        int minOverallArity = Integer.MAX_VALUE;
        int maxOverallArity = 0;
        boolean anyVarArgs = false;
        for (SingleMethodDesc overload : overloads) {
            int paramCount = overload.getParameterCount();
            if (!overload.isVarArgs()) {
                if (args.length != paramCount) {
                    minOverallArity = Math.min(minOverallArity, paramCount);
                    maxOverallArity = Math.max(maxOverallArity, paramCount);
                    continue;
                }
            } else {
                anyVarArgs = true;
                int fixedParamCount = paramCount - 1;
                if (args.length < fixedParamCount) {
                    minOverallArity = Math.min(minOverallArity, fixedParamCount);
                    maxOverallArity = Math.max(maxOverallArity, fixedParamCount);
                    continue;
                }
            }
            applicableByArity.add(overload);
        }
        if (applicableByArity.isEmpty()) {
            throw ArityException.raise((args.length > maxOverallArity ? maxOverallArity : minOverallArity), args.length);
        }

        SingleMethodDesc best;
        for (int priority : ToJavaNode.PRIORITIES) {
            best = findBestCandidate(applicableByArity, args, languageContext, toJavaNode, false, priority, cachedArgTypes);
            if (best != null) {
                return best;
            }
        }
        if (anyVarArgs) {
            for (int priority : ToJavaNode.PRIORITIES) {
                best = findBestCandidate(applicableByArity, args, languageContext, toJavaNode, true, priority, cachedArgTypes);
                if (best != null) {
                    return best;
                }
            }
        }

        throw noApplicableOverloadsException(overloads, args);
    }

    private static SingleMethodDesc findBestCandidate(List<SingleMethodDesc> applicableByArity, Object[] args, Object languageContext, ToJavaNode toJavaNode, boolean varArgs, int priority,
                    Type[] cachedArgTypes) {
        List<SingleMethodDesc> candidates = new ArrayList<>();

        if (!varArgs) {
            for (SingleMethodDesc candidate : applicableByArity) {
                int paramCount = candidate.getParameterCount();
                if (!candidate.isVarArgs() || paramCount == args.length) {
                    assert paramCount == args.length;
                    Class<?>[] parameterTypes = candidate.getParameterTypes();
                    Type[] genericParameterTypes = candidate.getGenericParameterTypes();
                    boolean applicable = true;
                    for (int i = 0; i < paramCount; i++) {
                        if (!isSubtypeOf(args[i], parameterTypes[i]) && !toJavaNode.canConvert(args[i], parameterTypes[i], genericParameterTypes[i], languageContext, priority)) {
                            applicable = false;
                            break;
                        }
                    }
                    if (applicable) {
                        candidates.add(candidate);
                    }
                }
            }
        } else {
            for (SingleMethodDesc candidate : applicableByArity) {
                if (candidate.isVarArgs()) {
                    int parameterCount = candidate.getParameterCount();
                    Class<?>[] parameterTypes = candidate.getParameterTypes();
                    Type[] genericParameterTypes = candidate.getGenericParameterTypes();
                    boolean applicable = true;
                    for (int i = 0; i < parameterCount - 1; i++) {
                        if (!isSubtypeOf(args[i], parameterTypes[i]) && !toJavaNode.canConvert(args[i], parameterTypes[i], genericParameterTypes[i], languageContext, priority)) {
                            applicable = false;
                            break;
                        }
                    }
                    if (applicable) {
                        Class<?> varArgsComponentType = parameterTypes[parameterCount - 1].getComponentType();
                        Type varArgsGenericComponentType = genericParameterTypes[parameterCount - 1];
                        if (varArgsGenericComponentType instanceof GenericArrayType) {
                            final GenericArrayType arrayType = (GenericArrayType) varArgsGenericComponentType;
                            varArgsGenericComponentType = arrayType.getGenericComponentType();
                        } else {
                            varArgsGenericComponentType = varArgsComponentType;
                        }
                        for (int i = parameterCount - 1; i < args.length; i++) {
                            if (!isSubtypeOf(args[i], varArgsComponentType) && !toJavaNode.canConvert(args[i], varArgsComponentType, varArgsGenericComponentType, languageContext, priority)) {
                                applicable = false;
                                break;
                            }
                        }
                        if (applicable) {
                            candidates.add(candidate);
                        }
                    }
                }
            }
        }

        if (!candidates.isEmpty()) {
            if (candidates.size() == 1) {
                SingleMethodDesc best = candidates.get(0);

                if (cachedArgTypes != null) {
                    fillArgTypesArray(args, cachedArgTypes, best, varArgs, applicableByArity, priority);
                }

                return best;
            } else {
                SingleMethodDesc best = findMostSpecificOverload(candidates, args, varArgs, priority, toJavaNode);
                if (best != null) {
                    if (cachedArgTypes != null) {
                        fillArgTypesArray(args, cachedArgTypes, best, varArgs, applicableByArity, priority);
                    }

                    return best;
                }
                throw ambiguousOverloadsException(candidates, args);
            }
        }
        return null;
    }

    private static SingleMethodDesc findMostSpecificOverload(List<SingleMethodDesc> candidates, Object[] args, boolean varArgs, int priority, ToJavaNode toJavaNode) {
        assert candidates.size() >= 2;
        if (candidates.size() == 2) {
            int res = compareOverloads(candidates.get(0), candidates.get(1), args, varArgs, priority, toJavaNode);
            return res == 0 ? null : (res < 0 ? candidates.get(0) : candidates.get(1));
        }

        Iterator<SingleMethodDesc> candIt = candidates.iterator();
        List<SingleMethodDesc> best = new LinkedList<>();
        best.add(candIt.next());

        while (candIt.hasNext()) {
            SingleMethodDesc cand = candIt.next();
            boolean add = false;
            for (Iterator<SingleMethodDesc> bestIt = best.iterator(); bestIt.hasNext();) {
                int res = compareOverloads(cand, bestIt.next(), args, varArgs, priority, toJavaNode);
                if (res == 0) {
                    add = true;
                } else if (res < 0) {
                    bestIt.remove();
                    add = true;
                } else {
                    assert res > 0;
                }
            }
            if (add) {
                best.add(cand);
            }
        }

        assert !best.isEmpty();
        if (best.size() == 1) {
            return best.get(0);
        }
        return null; // ambiguous
    }

    private static int compareOverloads(SingleMethodDesc m1, SingleMethodDesc m2, Object[] args, boolean varArgs, int priority, ToJavaNode toJavaNode) {
        int res = 0;
        int maxParamCount = Math.max(m1.getParameterCount(), m2.getParameterCount());
        assert !varArgs || m1.isVarArgs() && m2.isVarArgs();
        assert varArgs || (m1.getParameterCount() == m2.getParameterCount() && args.length == m1.getParameterCount());
        assert maxParamCount <= args.length;
        for (int i = 0; i < maxParamCount; i++) {
            Class<?> t1 = getParameterType(m1.getParameterTypes(), i, varArgs);
            Class<?> t2 = getParameterType(m2.getParameterTypes(), i, varArgs);
            if (t1 == t2) {
                continue;
            }
            int r = compareByPriority(t1, t2, args[i], priority, toJavaNode);
            if (r == 0) {
                r = compareAssignable(t1, t2);
                if (r == 0) {
                    continue;
                }
            }
            if (res == 0) {
                res = r;
            } else if (res != r) {
                // cannot determine definite ranking between these two overloads
                res = 0;
                break;
            }
        }
        return res;
    }

    private static Class<?> getParameterType(Class<?>[] parameterTypes, int i, boolean varArgs) {
        return varArgs && i >= parameterTypes.length - 1 ? parameterTypes[parameterTypes.length - 1].getComponentType() : parameterTypes[i];
    }

    private static int compareByPriority(Class<?> t1, Class<?> t2, Object arg, int priority, ToJavaNode toJavaNode) {
        if (priority <= ToJavaNode.STRICT) {
            return 0;
        }
        for (int p : ToJavaNode.PRIORITIES) {
            if (p > priority) {
                break;
            }
            boolean p1 = toJavaNode.canConvert(arg, t1, p);
            boolean p2 = toJavaNode.canConvert(arg, t2, p);
            if (p1 != p2) {
                return p1 ? -1 : 1;
            }
        }
        return 0;
    }

    private static int compareAssignable(Class<?> t1, Class<?> t2) {
        if (isAssignableFrom(t1, t2)) {
            // t1 > t2 (t2 more specific)
            return 1;
        } else if (isAssignableFrom(t2, t1)) {
            // t1 < t2 (t1 more specific)
            return -1;
        } else {
            return 0;
        }
    }

    private static boolean isAssignableFrom(Class<?> toType, Class<?> fromType) {
        if (toType.isAssignableFrom(fromType)) {
            return true;
        }
        boolean fromIsPrimitive = fromType.isPrimitive();
        boolean toIsPrimitive = toType.isPrimitive();
        Class<?> fromAsPrimitive = fromIsPrimitive ? fromType : boxedTypeToPrimitiveType(fromType);
        Class<?> toAsPrimitive = toIsPrimitive ? toType : boxedTypeToPrimitiveType(toType);
        if (toAsPrimitive != null && fromAsPrimitive != null) {
            if (toAsPrimitive == fromAsPrimitive) {
                assert fromIsPrimitive != toIsPrimitive;
                // primitive <: boxed
                return fromIsPrimitive;
            } else if (isWideningPrimitiveConversion(toAsPrimitive, fromAsPrimitive)) {
                // primitive|boxed <: wider primitive|boxed
                return true;
            }
        } else if (fromAsPrimitive == char.class && (toType == String.class || toType == CharSequence.class)) {
            // char|Character <: String|CharSequence
            return true;
        } else if (toAsPrimitive == null && fromAsPrimitive != null && toType.isAssignableFrom(primitiveTypeToBoxedType(fromAsPrimitive))) {
            // primitive|boxed <: Number et al
            return true;
        }
        return false;
    }

    private static boolean isSubtypeOf(Object argument, Class<?> parameterType) {
        Object value = argument;
        if (argument instanceof JavaObject) {
            value = ((JavaObject) argument).obj;
        }
        if (!parameterType.isPrimitive()) {
            return value == null || (parameterType.isInstance(value) && !(value instanceof TruffleObject));
        } else {
            if (value != null) {
                Class<?> boxedToPrimitive = boxedTypeToPrimitiveType(value.getClass());
                if (boxedToPrimitive != null) {
                    return (boxedToPrimitive == parameterType || isWideningPrimitiveConversion(parameterType, boxedToPrimitive));
                }
            }
        }
        return false;
    }

    private static boolean isWideningPrimitiveConversion(Class<?> toType, Class<?> fromType) {
        assert toType.isPrimitive();
        if (fromType == byte.class) {
            return toType == short.class || toType == int.class || toType == long.class || toType == float.class || toType == double.class;
        } else if (fromType == short.class) {
            return toType == int.class || toType == long.class || toType == float.class || toType == double.class;
        } else if (fromType == char.class) {
            return toType == int.class || toType == long.class || toType == float.class || toType == double.class;
        } else if (fromType == int.class) {
            return toType == long.class || toType == float.class || toType == double.class;
        } else if (fromType == long.class) {
            return toType == float.class || toType == double.class;
        } else if (fromType == float.class) {
            return toType == double.class;
        } else {
            return false;
        }
    }

    private static RuntimeException ambiguousOverloadsException(List<SingleMethodDesc> candidates, Object[] args) {
        String message = String.format("Multiple applicable overloads found for method name %s (candidates: %s, arguments: %s)", candidates.get(0).getName(), candidates, arrayToStringWithTypes(args));
        return UnsupportedTypeException.raise(new IllegalArgumentException(message), args);
    }

    private static RuntimeException noApplicableOverloadsException(SingleMethodDesc[] overloads, Object[] args) {
        String message = String.format("no applicable overload found (overloads: %s, arguments: %s)", Arrays.toString(overloads), arrayToStringWithTypes(args));
        return UnsupportedTypeException.raise(new IllegalArgumentException(message), args);
    }

    private static Type getGenericComponentType(Type type) {
        return type instanceof GenericArrayType ? ((GenericArrayType) type).getGenericComponentType() : ((Class<?>) type).getComponentType();
    }

    @TruffleBoundary
    private static Object[] createVarArgsArray(SingleMethodDesc method, Object[] args, int parameterCount) {
        Object[] arguments;
        Class<?>[] parameterTypes = method.getParameterTypes();
        arguments = new Object[parameterCount];
        for (int i = 0; i < parameterCount - 1; i++) {
            arguments[i] = args[i];
        }
        Class<?> varArgsType = parameterTypes[parameterCount - 1].getComponentType();
        Object varArgs = Array.newInstance(varArgsType, args.length - parameterCount + 1);
        for (int i = parameterCount - 1, j = 0; i < args.length; i++, j++) {
            Array.set(varArgs, j, args[i]);
        }
        arguments[parameterCount - 1] = varArgs;
        return arguments;
    }

    private static Object doInvoke(SingleMethodDesc method, Object obj, Object[] arguments, Object languageContext) {
        assert arguments.length == method.getParameterCount();
        Object ret;
        try {
            ret = method.invoke(obj, arguments);
        } catch (Throwable e) {
            throw JavaInteropReflect.rethrow(JavaInterop.wrapHostException(languageContext, e));
        }
        return JavaInterop.toGuestValue(ret, languageContext);
    }

    private static String arrayToStringWithTypes(Object[] args) {
        StringJoiner sj = new StringJoiner(", ", "[", "]");
        for (Object arg : args) {
            sj.add(arg == null ? null : arg.toString() + " (" + arg.getClass().getSimpleName() + ")");
        }
        return sj.toString();
    }

    static class JavaObjectType implements Type {
        final Class<?> clazz;

        JavaObjectType(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public int hashCode() {
            return ((clazz == null) ? 0 : clazz.hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof JavaObjectType)) {
                return false;
            }
            JavaObjectType other = (JavaObjectType) obj;
            return Objects.equals(this.clazz, other.clazz);
        }

        @Override
        public String toString() {
            return "JavaObject[" + clazz.getTypeName() + "]";
        }
    }

    static class PrimitiveType implements Type {
        final Class<?> targetType;
        @CompilationFinal(dimensions = 1) final Class<?>[] otherTypes;
        final int priority;

        PrimitiveType(Class<?> targetType, Class<?>[] otherTypes, int priority) {
            this.targetType = targetType;
            this.otherTypes = otherTypes;
            this.priority = priority;
        }

        @Override
        public int hashCode() {
            return ((targetType == null) ? 0 : targetType.hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PrimitiveType)) {
                return false;
            }
            PrimitiveType other = (PrimitiveType) obj;
            return Objects.equals(this.targetType, other.targetType) && Arrays.equals(this.otherTypes, other.otherTypes);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Primitive[");
            sb.append(targetType.getTypeName());
            if (otherTypes.length > 0) {
                for (Class<?> otherType : otherTypes) {
                    sb.append(", !");
                    sb.append(otherType.getTypeName());
                }
            }
            sb.append(']');
            return sb.toString();
        }

        @ExplodeLoop
        public boolean test(Object value, ToJavaNode toJavaNode) {
            for (Class<?> otherType : otherTypes) {
                if (toJavaNode.canConvertToPrimitive(value, otherType, priority)) {
                    return false;
                }
            }
            return toJavaNode.canConvertToPrimitive(value, targetType, priority);
        }
    }
}
