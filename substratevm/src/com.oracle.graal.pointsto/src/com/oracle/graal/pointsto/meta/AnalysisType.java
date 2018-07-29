/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.meta;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.graalvm.compiler.graph.Node;
import org.graalvm.word.WordBase;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.BigBang.ConstantObjectsProfiler;
import com.oracle.graal.pointsto.api.DefaultUnsafePartition;
import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.api.UnsafePartitionKind;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.flow.context.object.AnalysisObject;
import com.oracle.graal.pointsto.flow.context.object.ConstantContextSensitiveObject;
import com.oracle.graal.pointsto.infrastructure.OriginalClassProvider;
import com.oracle.graal.pointsto.infrastructure.WrappedJavaType;
import com.oracle.graal.pointsto.typestate.TypeState;

import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.Assumptions.AssumptionResult;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class AnalysisType implements WrappedJavaType, OriginalClassProvider, Comparable<AnalysisType> {

    @SuppressWarnings("rawtypes")//
    private static final AtomicReferenceFieldUpdater<AnalysisType, ConcurrentHashMap> UNSAFE_ACCESS_FIELDS_UPDATER = //
                    AtomicReferenceFieldUpdater.newUpdater(AnalysisType.class, ConcurrentHashMap.class, "unsafeAccessedFields");

    private static final AtomicReferenceFieldUpdater<AnalysisType, ConstantContextSensitiveObject> UNIQUE_CONSTANT_UPDATER = //
                    AtomicReferenceFieldUpdater.newUpdater(AnalysisType.class, ConstantContextSensitiveObject.class, "uniqueConstant");

    private final AnalysisUniverse universe;
    private final ResolvedJavaType wrapped;

    private boolean isInHeap;
    private boolean isAllocated;
    private boolean isInTypeCheck;
    private boolean unsafeFieldsRecomputed;
    private boolean unsafeAccessedFieldsRegistered;

    /**
     * Unsafe accessed fields for this type.
     *
     * This field can be initialized during the multithreaded analysis phase in case of the computed
     * value fields, thus we use the UNSAFE_ACCESS_FIELDS_UPDATER to initialize it.
     */
    private volatile ConcurrentHashMap<UnsafePartitionKind, Collection<AnalysisField>> unsafeAccessedFields;

    AnalysisType[] subTypes;

    private final int id;

    private final JavaKind storageKind;

    /** The unique context insensitive analysis object for this type. */
    private AnalysisObject contextInsensitiveAnalysisObject;
    /** Mapping from JavaConstant to the analysis ConstantObject. */
    private ConcurrentMap<Constant, ConstantContextSensitiveObject> constantObjectsCache;
    /**
     * A unique ConstantObject per analysis type. When the size of {@link #constantObjectsCache} is
     * above a threshold all the ConstantObject recorded until that moment are merged in the
     * {@link #uniqueConstant}.
     */
    private volatile ConstantContextSensitiveObject uniqueConstant;

    /**
     * Keeps track of the referenced types, i.e., concrete field types or array elements types
     * discovered by the static analysis.
     *
     * This list is not update during the analysis and is filled lazily when requested through
     * {@link #getReferencedTypes(BigBang)}. For complete results
     * {@link #getReferencedTypes(BigBang)} should only be called when the base analysis has
     * finished.
     */
    private List<AnalysisType> referencedTypes;

    /**
     * Cache for the resolved methods.
     *
     * Map ResolvedJavaMethod to Object and not AnalysisMethod because when the type doesn't
     * implement the method the value stored is {@link AnalysisType#NULL_METHOD}.
     */
    private final ConcurrentHashMap<ResolvedJavaMethod, Object> resolvedMethods = new ConcurrentHashMap<>(AnalysisUniverse.ESTIMATED_METHODS_PER_TYPE);

    /**
     * Marker used in the {@link AnalysisType#resolvedMethods} map to signal that the type doesn't
     * implement a method.
     */
    private static final Object NULL_METHOD = new Object();

    private final AnalysisType componentType;
    private final AnalysisType elementalType;

    private final AnalysisType[] interfaces;

    /* isArray is an expensive operation so we eagerly compute it */
    private boolean isArray;

    AnalysisType(AnalysisUniverse universe, ResolvedJavaType javaType, JavaKind storageKind, AnalysisType objectType) {
        this.universe = universe;
        this.wrapped = javaType;
        isArray = wrapped.isArray();
        this.storageKind = storageKind;
        this.unsafeAccessedFieldsRegistered = false;
        if (universe.hostVM.analysisPolicy().needsConstantCache()) {
            this.constantObjectsCache = new ConcurrentHashMap<>();
        }

        /*
         * Eagerly ask the wrapped type to lookup the declared constructors. This will discover
         * early any class resolution problems caused by missing parameter types. We cannot cache
         * the result as AnalysisMethod[], i.e., by calling universe.lookup(JavaMethod[]), because
         * that would lead to a deadlock, but we could cache it as ResolvedJavaMethod[] which we can
         * later use in AnalysisType.getDeclaredConstructors().
         */
        wrapped.getDeclaredConstructors();
        /*
         * Eagerly resolve the enclosing type. It is possible that we are dealing with an incomplete
         * classpath. While normally JVM doesn't care about missing classes unless they are really
         * used the analysis is eager to load all reachable classes. The analysis client should deal
         * with type resolution problems.
         * 
         * We cannot cache the result as an AnalysisType, i.e., by calling
         * universe.lookup(JavaType), because that could lead to a deadlock.
         */
        wrapped.getEnclosingType();

        /* Ensure the super types as well as the component type (for arrays) is created too. */
        getSuperclass();
        interfaces = convertTypes(wrapped.getInterfaces());
        if (isArray()) {
            this.componentType = universe.lookup(wrapped.getComponentType());
            int dimension = 0;
            AnalysisType elemType = this;
            while (elemType.isArray()) {
                elemType = elemType.getComponentType();
                dimension++;
            }
            if (elemType.getSuperclass() != null) {
                elemType.getSuperclass().getArrayClass(dimension);
            }
            this.elementalType = elemType;
            if (dimension >= 2) {
                objectType.getArrayClass(dimension - 1);
            }
            for (AnalysisType interf : elemType.getInterfaces()) {
                interf.getArrayClass(dimension);
            }
        } else {
            this.componentType = null;
            this.elementalType = this;
        }

        /* Set id after accessing super types, so that all these types get a lower id number. */
        this.id = universe.nextTypeId.getAndIncrement();
        /* Set the context insensitive analysis object so that it has access to its type id. */
        this.contextInsensitiveAnalysisObject = new AnalysisObject(universe, this);
        this.referencedTypes = null;

        assert getSuperclass() == null || getId() > getSuperclass().getId();
    }

    private AnalysisType[] convertTypes(ResolvedJavaType[] originalTypes) {
        List<AnalysisType> result = new ArrayList<>(originalTypes.length);
        for (ResolvedJavaType originalType : originalTypes) {
            if (!universe.hostVM().platformSupported(originalType)) {
                /* Ignore types that are not in our platform (including hosted-only types). */
                continue;
            }
            result.add(universe.lookup(originalType));
        }
        return result.toArray(new AnalysisType[result.size()]);
    }

    public AnalysisType getArrayClass(int dimension) {
        AnalysisType result = this;
        for (int i = 0; i < dimension; i++) {
            result = result.getArrayClass();
        }
        return result;
    }

    public void cleanupAfterAnalysis() {
        assignableTypes = null;
        assignableTypesNonNull = null;
        contextInsensitiveAnalysisObject = null;
        constantObjectsCache = null;
        uniqueConstant = null;
        unsafeAccessedFields = null;
    }

    public int getId() {
        return id;
    }

    public AnalysisObject getContextInsensitiveAnalysisObject() {
        return contextInsensitiveAnalysisObject;
    }

    public AnalysisObject getCachedConstantObject(BigBang bb, JavaConstant constant) {

        /*
         * Constant caching is only used we certain analysis policies. Ideally we would store the
         * cache in the policy, but it is simpler to store the cache for each type.
         */
        assert bb.analysisPolicy().needsConstantCache() : "The analysis policy doesn't specify the need for a constants cache.";
        assert bb.trackConcreteAnalysisObjects(this);
        assert !(constant instanceof PrimitiveConstant) : "The analysis should not model PrimitiveConstant.";

        if (uniqueConstant != null) {
            // The constants have been merged, return the unique constant
            return uniqueConstant;
        }

        if (constantObjectsCache.size() > PointstoOptions.MaxConstantObjectsPerType.getValue(bb.getOptions())) {
            // The number of constant objects has increased above the limit,
            // merge the constants in the uniqueConstant and return it
            mergeConstantObjects(bb);
            return uniqueConstant;
        }

        // Get the analysis ConstantObject modeling the JavaConstant
        AnalysisObject result = constantObjectsCache.get(constant);
        if (result == null) {
            // Create a ConstantObject to model each JavaConstant
            ConstantContextSensitiveObject newValue = new ConstantContextSensitiveObject(bb, this, constant);
            ConstantContextSensitiveObject oldValue = constantObjectsCache.putIfAbsent(constant, newValue);
            result = oldValue != null ? oldValue : newValue;

            if (PointstoOptions.ProfileConstantObjects.getValue(bb.getOptions())) {
                ConstantObjectsProfiler.registerConstant(this);
                ConstantObjectsProfiler.maybeDumpConstantHistogram();
            }
        }

        return result;
    }

    private void mergeConstantObjects(BigBang bb) {
        ConstantContextSensitiveObject uConstant = new ConstantContextSensitiveObject(bb, this, null);
        if (UNIQUE_CONSTANT_UPDATER.compareAndSet(this, null, uConstant)) {
            constantObjectsCache.values().stream().forEach(constantObject -> {
                constantObject.mergeInstanceFieldsFlows(bb, uniqueConstant);
            });
        }
    }

    /**
     * Returns the list of referenced types, i.e., concrete field types or array elements types
     * discovered by the static analysis.
     *
     * Since this list is not updated during the analysis, for complete results this should only be
     * called when the base analysis has finished.
     */
    public List<AnalysisType> getReferencedTypes(BigBang bb) {

        if (referencedTypes == null) {

            Set<AnalysisType> referencedTypesSet = new HashSet<>();

            if (this.isArray()) {
                if (this.getContextInsensitiveAnalysisObject().isObjectArray()) {
                    /* Collect the types referenced through index store (for arrays). */
                    for (AnalysisType type : getContextInsensitiveAnalysisObject().getArrayElementsFlow(bb, false).getState().types()) {
                        /* Add the assignable types, as discovered by the static analysis. */
                        type.getTypeFlow(bb, false).getState().types().forEach(referencedTypesSet::add);
                    }
                }
            } else {
                /* Collect the field referenced types. */
                for (AnalysisField field : getInstanceFields(true)) {
                    TypeState state = field.getInstanceFieldTypeState();
                    if (!state.isUnknown()) {
                        /*
                         * If the field state is unknown we don't process the state. Unknown means
                         * that the state can contain any object of any type, but the core analysis
                         * guarantees that there is no path on which the objects of an unknown type
                         * state are converted to and used as java objects; they are just used as
                         * data.
                         */
                        for (AnalysisType type : state.types()) {
                            /* Add the assignable types, as discovered by the static analysis. */
                            type.getTypeFlow(bb, false).getState().types().forEach(referencedTypesSet::add);
                        }
                    }
                }

            }

            referencedTypes = new ArrayList<>(referencedTypesSet);
        }

        return referencedTypes;
    }

    public volatile AllInstantiatedTypeFlow assignableTypes;
    public volatile AllInstantiatedTypeFlow assignableTypesNonNull;

    public AllInstantiatedTypeFlow getTypeFlow(BigBang bb, boolean includeNull) {
        if (assignableTypes == null) {
            createTypeFlows(bb);
        }

        if (includeNull) {
            return assignableTypes;
        } else {
            return assignableTypesNonNull;
        }
    }

    private synchronized void createTypeFlows(BigBang bb) {
        if (assignableTypes != null) {
            return;
        }

        /*
         * Do not publish the new flows here, before they have been completely initialized. Other
         * threads must not pick up partially initialized type flows.
         */
        AllInstantiatedTypeFlow newAssignableTypes = new AllInstantiatedTypeFlow(this);
        AllInstantiatedTypeFlow newAssignableTypesNonNull = new AllInstantiatedTypeFlow(this);

        updateTypeFlows(bb, newAssignableTypes, newAssignableTypesNonNull);

        /* We perform the null-check on assignableTypes, so publish that one last. */
        assignableTypesNonNull = newAssignableTypesNonNull;
        assignableTypes = newAssignableTypes;

    }

    public static void updateAssignableTypes(BigBang bb) {
        /*
         * Update the assignable-state for all types. So do not post any update operations before
         * the computation is finished, because update operations must not see any intermediate
         * state.
         */
        List<AnalysisType> allTypes = bb.getUniverse().getTypes();
        List<TypeFlow<?>> changedFlows = new ArrayList<>();

        Map<Integer, BitSet> newAssignableTypes = new HashMap<>();
        for (AnalysisType type : allTypes) {
            if (type.isInstantiated()) {
                int arrayDimension = 0;
                AnalysisType elementalType = type;
                while (elementalType.isArray()) {
                    elementalType = elementalType.getComponentType();
                    arrayDimension++;
                }
                addTypeToAssignableLists(type.getId(), elementalType, arrayDimension, newAssignableTypes, true, bb);
                if (arrayDimension > 0) {
                    addTypeToAssignableLists(type.getId(), type, 0, newAssignableTypes, true, bb);
                }
                for (int i = 0; i < arrayDimension; i++) {
                    addTypeToAssignableLists(type.getId(), bb.getObjectType(), i, newAssignableTypes, false, bb);
                }
                if (!elementalType.isPrimitive()) {
                    addTypeToAssignableLists(type.getId(), bb.getObjectType(), arrayDimension, newAssignableTypes, false, bb);
                }
            }
        }
        for (AnalysisType type : allTypes) {
            if (type.assignableTypes != null) {
                TypeState assignableTypeState = TypeState.forNull();
                if (newAssignableTypes.get(type.getId()) != null) {
                    BitSet assignableTypes = newAssignableTypes.get(type.getId());
                    if (type.assignableTypes.getState().hasExactTypes(assignableTypes)) {
                        /* Avoid creation of the expensive type state. */
                        continue;
                    }
                    assignableTypeState = TypeState.forExactTypes(bb, newAssignableTypes.get(type.getId()), true);
                }

                updateFlow(bb, type.assignableTypes, assignableTypeState, changedFlows);
                updateFlow(bb, type.assignableTypesNonNull, assignableTypeState.forNonNull(bb), changedFlows);
            }
        }

        for (TypeFlow<?> changedFlow : changedFlows) {
            bb.postFlow(changedFlow);
        }
    }

    private static void addTypeToAssignableLists(int typeIdToAdd, AnalysisType elementalType, int arrayDimension, Map<Integer, BitSet> newAssignableTypes, boolean processSuperclass, BigBang bb) {
        if (elementalType == null) {
            return;
        }

        AnalysisType addTo = elementalType;
        for (int i = 0; i < arrayDimension; i++) {
            addTo = addTo.getArrayClass();
        }
        int addToId = addTo.getId();
        if (!newAssignableTypes.containsKey(addToId)) {
            newAssignableTypes.put(addToId, new BitSet());
        }
        newAssignableTypes.get(addToId).set(typeIdToAdd);

        if (processSuperclass) {
            addTypeToAssignableLists(typeIdToAdd, elementalType.getSuperclass(), arrayDimension, newAssignableTypes, true, bb);
        }
        for (AnalysisType interf : elementalType.getInterfaces()) {
            addTypeToAssignableLists(typeIdToAdd, interf, arrayDimension, newAssignableTypes, false, bb);
        }
    }

    /** Called when the list of assignable types of a type is first initialized. */
    private void updateTypeFlows(BigBang bb, TypeFlow<?> assignable, TypeFlow<?> assignableNonNull) {
        if (isPrimitive() || isJavaLangObject()) {
            return;
        }

        AnalysisType superType;
        if (isInterface()) {
            /*
             * For interfaces, we have to search all instantiated types, i.e., start at
             * java.lang.Object
             */
            superType = bb.getObjectType();
        } else {
            /*
             * Find the closest supertype that has assignable-information computed. That is the best
             * starting point.
             */
            superType = getSuperclass();
            while (superType.assignableTypes == null) {
                superType = superType.getSuperclass();
            }
        }

        TypeState superAssignableTypeState = superType.assignableTypes.getState();
        BitSet assignableTypesSet = new BitSet();
        for (AnalysisType type : superAssignableTypeState.types()) {
            if (this.isAssignableFrom(type)) {
                assignableTypesSet.set(type.getId());
            }
        }

        TypeState assignableTypeState = TypeState.forExactTypes(bb, assignableTypesSet, true);

        updateFlow(bb, assignable, assignableTypeState);
        updateFlow(bb, assignableNonNull, assignableTypeState.forNonNull(bb));
    }

    private static void updateFlow(BigBang bb, TypeFlow<?> flow, TypeState newState) {
        updateFlow(bb, flow, newState, null);
    }

    private static void updateFlow(BigBang bb, TypeFlow<?> flow, TypeState newState, List<TypeFlow<?>> changedFlows) {
        if (!flow.getState().equals(newState)) {
            flow.setState(bb, newState);
            if (changedFlows != null && (flow.getUses().size() > 0 || flow.getObservers().size() > 0)) {
                changedFlows.add(flow);
            }
        }
    }

    public void registerAsInHeap() {
        assert isArray() || (isInstanceClass() && !Modifier.isAbstract(getModifiers()));
        isInHeap = true;
    }

    /**
     * @param node For future use and debugging
     */
    public void registerAsAllocated(Node node) {
        assert isArray() || (isInstanceClass() && !Modifier.isAbstract(getModifiers())) : this;
        if (!isAllocated) {
            isAllocated = true;
        }
    }

    public void registerAsInTypeCheck() {
        isInTypeCheck = true;
    }

    /**
     * Says that all instance fields which hold offsets to unsafe field accesses are already
     * recomputed with the correct values from the substrate object layout and therefore don't need
     * a RecomputeFieldValue annotation.
     */
    public void registerUnsafeFieldsRecomputed() {
        unsafeFieldsRecomputed = true;
    }

    /**
     * Add the field to the collection of unsafe accessed fields declared by this type.
     *
     * A field can potentially be registered as unsafe accessed multiple times, depending on the
     * feature implementation, but we add it to the partition only once, when it is first accessed.
     * This is controlled by the isUnsafeAccessed flag in the AnalysField. Also, a field cannot be
     * part of more than one partitions.
     */
    public void registerUnsafeAccessedField(AnalysisField field, UnsafePartitionKind partitionKind) {

        unsafeAccessedFieldsRegistered = true;

        if (unsafeAccessedFields == null) {
            /* Lazily initialize the map, not all types have unsafe accessed fields. */
            UNSAFE_ACCESS_FIELDS_UPDATER.compareAndSet(this, null, new ConcurrentHashMap<>());
        }

        Collection<AnalysisField> unsafePartition = unsafeAccessedFields.get(partitionKind);
        if (unsafePartition == null) {
            /*
             * We use a thread safe collection to store an unsafe accessed fields partition. Since
             * elements can be added to it concurrently using a non thread safe collection, such as
             * an array list, can result in null being added to the list. Since we don't need index
             * access ConcurrentLinkedQueue is a good match.
             */
            Collection<AnalysisField> newPartition = new ConcurrentLinkedQueue<>();
            Collection<AnalysisField> oldPartition = unsafeAccessedFields.putIfAbsent(partitionKind, newPartition);
            unsafePartition = oldPartition != null ? oldPartition : newPartition;
        }

        assert !unsafePartition.contains(field) : "Field " + field + " already registered as unsafe accessed with " + this;
        unsafePartition.add(field);
    }

    private boolean hasUnsafeAccessedFields() {
        /*
         * Walk up the inheritance chain, as soon as we encounter a class that has unsafe accessed
         * fields we return true, otherwise we reach the top of the hierarchy and return false.
         *
         * Since unsafe accessed fields can be registered on the fly, i.e., during the analysis, we
         * cannot cache this result. If we cached the result and the result was false, i.e., no
         * unsafe accessed fields were registered yet, we would have to invalidate it when a field
         * is registered as unsafe during the analysis and then walk down the type hierarchy and
         * invalidate the cached value of all the sub-types.
         */
        return unsafeAccessedFieldsRegistered || (getSuperclass() != null ? getSuperclass().hasUnsafeAccessedFields() : false);
    }

    public List<AnalysisField> unsafeAccessedFields() {
        return unsafeAccessedFields(DefaultUnsafePartition.get());
    }

    public List<AnalysisField> unsafeAccessedFields(UnsafePartitionKind partitionKind) {
        if (!hasUnsafeAccessedFields()) {
            /*
             * Do a quick check if this type has unsafe accessed fields before constructing the data
             * structures holding all the unsafe accessed fields: the ones of this type and the ones
             * up its type hierarchy.
             */
            return Collections.emptyList();
        }
        return allUnsafeAccessedFields(partitionKind);
    }

    private List<AnalysisField> allUnsafeAccessedFields(UnsafePartitionKind partitionKind) {
        /*
         * Walk up the type hierarchy and build the unsafe partition containing all the unsafe
         * fields of the current type and all its super types. The unsafePartition collection
         * doesn't need to be thread safe since updates to it are only done on the current thread.
         *
         * The resulting list could be cached but the caching mechanism is complicated by
         * registering unsafe accessed fields during the analysis. When a field is registered as
         * unsafe on the fly it must be propagated to all the sub-types of its declaring class, but
         * we update the sub-types list only after each analysis macro-iteration. This can create
         * situations where some unsafe writes/reads to/from unsafe accessed fields will be missed.
         * Caching would still be possible, but it would be unnecessary complicated and prone to
         * race conditions.
         */
        List<AnalysisField> unsafePartition = new ArrayList<>();
        unsafePartition.addAll(unsafeAccessedFields != null && unsafeAccessedFields.containsKey(partitionKind) ? unsafeAccessedFields.get(partitionKind) : Collections.emptyList());
        if (getSuperclass() != null) {
            List<AnalysisField> superFileds = getSuperclass().allUnsafeAccessedFields(partitionKind);
            unsafePartition.addAll(superFileds);
        }

        return unsafePartition;
    }

    public boolean isInstantiated() {
        return isInHeap || isAllocated;
    }

    /**
     * Returns true if all instance fields which hold offsets to unsafe field accesses are already
     * recomputed with the correct values from the substrate object layout. Which means that those
     * fields don't need a RecomputeFieldValue annotation.
     */
    public boolean unsafeFieldsRecomputed() {
        return unsafeFieldsRecomputed;
    }

    public boolean isInTypeCheck() {
        return isInTypeCheck;
    }

    /**
     * The kind of the field in memory (in contrast to {@link #getJavaKind()}, which is the kind of
     * the field on the Java type system level). For example {@link WordBase word types} have a
     * {@link #getJavaKind} of {@link JavaKind#Object}, but a primitive {@link #storageKind}.
     */
    public final JavaKind getStorageKind() {
        return storageKind;
    }

    /**
     * Returns true if this type is part of the word type hierarchy, i.e, implements
     * {@link WordBase}.
     */
    public boolean isWordType() {
        /* Word types are currently the only types where kind and storageKind differ. */
        return getJavaKind() != getStorageKind();
    }

    @Override
    public ResolvedJavaType getWrapped() {
        return universe.substitutions.resolve(wrapped);
    }

    @Override
    public Class<?> getJavaClass() {
        return OriginalClassProvider.getJavaClass(universe.getOriginalSnippetReflection(), wrapped);
    }

    @Override
    public final String getName() {
        return wrapped.getName();
    }

    @Override
    public final JavaKind getJavaKind() {
        return wrapped.getJavaKind();
    }

    @Override
    public final AnalysisType resolve(ResolvedJavaType accessingClass) {
        return this;
    }

    @Override
    public final boolean hasFinalizer() {
        /* We just ignore finalizers. */
        return false;
    }

    @Override
    public final AssumptionResult<Boolean> hasFinalizableSubclass() {
        /* We just ignore finalizers. */
        return new AssumptionResult<>(false);
    }

    @Override
    public final boolean isInitialized() {
        assert wrapped.isInitialized();
        return true;
    }

    @Override
    public void initialize() {
        assert wrapped.isInitialized();
    }

    @Override
    public final AnalysisType getArrayClass() {
        return universe.lookup(wrapped.getArrayClass());
    }

    @Override
    public boolean isInterface() {
        return wrapped.isInterface();
    }

    @Override
    public boolean isEnum() {
        return wrapped.isEnum();
    }

    @Override
    public boolean isInstanceClass() {
        return wrapped.isInstanceClass();
    }

    @Override
    public boolean isArray() {
        return isArray;
    }

    @Override
    public boolean isPrimitive() {
        return wrapped.isPrimitive();
    }

    @Override
    public int getModifiers() {
        return wrapped.getModifiers();
    }

    @Override
    public boolean isAssignableFrom(ResolvedJavaType other) {
        ResolvedJavaType subst = universe.substitutions.resolve(((AnalysisType) other).wrapped);
        return wrapped.isAssignableFrom(subst);
    }

    @Override
    public boolean isInstance(JavaConstant obj) {
        return wrapped.isInstance(universe.toHosted(obj));
    }

    @Override
    public AnalysisType getSuperclass() {
        return universe.lookup(wrapped.getSuperclass());
    }

    @Override
    public AnalysisType[] getInterfaces() {
        return interfaces;
    }

    @Override
    public ResolvedJavaType getSingleImplementor() {
        /*
         * New classes can be loaded during the analysis, so we cannot guarantee a consistent and
         * correct result. So we need to conservatively say that there is no single implementor.
         */
        return this;
    }

    @Override
    public AnalysisType findLeastCommonAncestor(ResolvedJavaType otherType) {
        ResolvedJavaType subst = universe.substitutions.resolve(((AnalysisType) otherType).wrapped);
        return universe.lookup(wrapped.findLeastCommonAncestor(subst));
    }

    @Override
    public AssumptionResult<ResolvedJavaType> findLeafConcreteSubtype() {
        AssumptionResult<ResolvedJavaType> wrappedResult = wrapped.findLeafConcreteSubtype();
        if (wrappedResult != null && wrappedResult.isAssumptionFree()) {
            return new AssumptionResult<>(universe.lookup(wrappedResult.getResult()));
        }
        return null;
    }

    @Override
    public AnalysisType getComponentType() {
        return componentType;
    }

    @Override
    public ResolvedJavaType getElementalType() {
        return elementalType;
    }

    @Override
    public AnalysisMethod resolveMethod(ResolvedJavaMethod method, ResolvedJavaType callerType) {
        Object resolvedMethod = resolvedMethods.get(method);
        if (resolvedMethod == null) {
            ResolvedJavaMethod substMethod = universe.substitutions.resolve(((AnalysisMethod) method).wrapped);
            /*
             * We do not want any access checks to be performed, so we use the method's declaring
             * class as the caller type.
             */
            ResolvedJavaType substCallerType = substMethod.getDeclaringClass();

            Object newResolvedMethod = universe.lookup(wrapped.resolveMethod(substMethod, substCallerType));
            if (newResolvedMethod == null) {
                newResolvedMethod = NULL_METHOD;
            }
            Object oldResolvedMethod = resolvedMethods.putIfAbsent(method, newResolvedMethod);
            resolvedMethod = oldResolvedMethod != null ? oldResolvedMethod : newResolvedMethod;
        }
        return resolvedMethod == NULL_METHOD ? null : (AnalysisMethod) resolvedMethod;
    }

    @Override
    public AnalysisMethod resolveConcreteMethod(ResolvedJavaMethod method, ResolvedJavaType callerType) {
        return (AnalysisMethod) WrappedJavaType.super.resolveConcreteMethod(method, callerType);
    }

    @Override
    public AssumptionResult<ResolvedJavaMethod> findUniqueConcreteMethod(ResolvedJavaMethod method) {
        // ResolvedJavaMethod subst = universe.substitutions.resolve(((AnalysisMethod)
        // method).wrapped);
        // return universe.lookup(wrapped.findUniqueConcreteMethod(subst));
        return null;
    }

    @Override
    public ResolvedJavaField findInstanceFieldWithOffset(long offset, JavaKind expectedKind) {
        /*
         * In the analysis universe, we still use the hosted field offsets, so we can just delegate
         * to the wrapped type.
         */
        return universe.lookup(wrapped.findInstanceFieldWithOffset(offset, expectedKind));
    }

    private final AnalysisField[][] instanceFieldsCache = new AnalysisField[2][];

    @Override
    public AnalysisField[] getInstanceFields(boolean includeSuperclasses) {
        int cacheIdx = includeSuperclasses ? 1 : 0;
        AnalysisField[] result = instanceFieldsCache[cacheIdx];
        if (result != null) {
            return result;
        } else {
            return instanceFieldsCache[cacheIdx] = convertInstanceFields(includeSuperclasses);
        }
    }

    private AnalysisField[] convertInstanceFields(boolean includeSupeclasses) {
        List<AnalysisField> list = new ArrayList<>();
        if (includeSupeclasses && getSuperclass() != null) {
            list.addAll(Arrays.asList(getSuperclass().getInstanceFields(true)));
        }
        return convertFields(wrapped.getInstanceFields(false), list, includeSupeclasses);
    }

    private AnalysisField[] convertFields(ResolvedJavaField[] original, List<AnalysisField> list, boolean listIncludesSuperClassesFields) {
        for (int i = 0; i < original.length; i++) {
            if (!original[i].isInternal()) {
                try {
                    AnalysisField aField = universe.lookup(original[i]);
                    if (aField != null) {
                        if (listIncludesSuperClassesFields) {
                            /*
                             * If the list includes the super classes fields, register the position.
                             */
                            aField.setPosition(list.size());
                        }
                        list.add(aField);
                    }
                } catch (UnsupportedFeatureException ex) {
                    // Ignore deleted fields and fields of deleted types.
                }
            }
        }
        return list.toArray(new AnalysisField[list.size()]);
    }

    @Override
    public AnalysisField[] getStaticFields() {
        return convertFields(wrapped.getStaticFields(), new ArrayList<>(), false);
    }

    @Override
    public Annotation[] getAnnotations() {
        return wrapped.getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return wrapped.getDeclaredAnnotations();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return wrapped.getAnnotation(annotationClass);
    }

    @Override
    public String getSourceFileName() {
        // getSourceFileName is not implemented for primitive types
        return wrapped.isPrimitive() ? null : wrapped.getSourceFileName();
    }

    @Override
    public String toString() {
        return "AnalysisType<" + toJavaName(true) + ", allocated: " + isAllocated + ", inHeap: " + isInHeap + ", inTypeCheck: " + isInTypeCheck + ">";
    }

    @Override
    public boolean isLocal() {
        /*
         * Meta programs and languages often get the naming of their anonymous classes wrong. This
         * makes, getSimpleName in isLocal to fail and prevents us from compiling those bytecodes.
         * Since, isLocal is not very important for anonymous classes we can ignore this failure.
         */
        try {
            return wrapped.isLocal();
        } catch (InternalError e) {
            universe.getHostVM().warn("unknown locality of class " + wrapped.getName() + ", assuming class is not local. To remove the warning report an issue " +
                            "to the library or language author. The issue is caused by " + wrapped.getName() + " which is not following the naming convention.");
            return false;
        }
    }

    @Override
    public boolean isMember() {
        return wrapped.isMember();
    }

    @Override
    public AnalysisType getEnclosingType() {
        return universe.lookup(wrapped.getEnclosingType());
    }

    @Override
    public AnalysisMethod[] getDeclaredConstructors() {
        return universe.lookup(wrapped.getDeclaredConstructors());
    }

    @Override
    public AnalysisMethod[] getDeclaredMethods() {
        return universe.lookup(wrapped.getDeclaredMethods());
    }

    @Override
    public ResolvedJavaMethod getClassInitializer() {
        return universe.lookup(wrapped.getClassInitializer());
    }

    @Override
    public boolean isLinked() {
        assert wrapped.isLinked();
        return true;
    }

    @Override
    public boolean isCloneableWithAllocation() {
        throw JVMCIError.unimplemented();
    }

    @Override
    public ResolvedJavaType getHostClass() {
        return universe.lookup(wrapped.getHostClass());
    }

    @Override
    public int compareTo(AnalysisType other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    /* Value copied from java.lang.Class. */
    private static final int ANNOTATION = 0x00002000;

    /* Method copied from java.lang.Class. */
    public boolean isAnnotation() {
        return (getModifiers() & ANNOTATION) != 0;
    }

}
