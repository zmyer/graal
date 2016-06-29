/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.PRESERVES_REGISTERS;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.SAFEPOINT;
import static jdk.vm.ci.hotspot.HotSpotCallingConventionType.JavaCall;
import static jdk.vm.ci.hotspot.HotSpotCallingConventionType.JavaCallee;

import java.util.HashMap;
import java.util.Map;

import com.oracle.graal.compiler.common.LIRKind;
import com.oracle.graal.compiler.common.LocationIdentity;
import com.oracle.graal.compiler.common.spi.ForeignCallDescriptor;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkage;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkageImpl;
import com.oracle.graal.hotspot.HotSpotGraalRuntimeProvider;
import com.oracle.graal.hotspot.stubs.ForeignCallStub;
import com.oracle.graal.hotspot.stubs.Stub;
import com.oracle.graal.word.Word;
import com.oracle.graal.word.WordTypes;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;

/**
 * HotSpot implementation of {@link HotSpotForeignCallsProvider}.
 */
public abstract class HotSpotForeignCallsProviderImpl implements HotSpotForeignCallsProvider {

    public static final ForeignCallDescriptor OSR_MIGRATION_END = new ForeignCallDescriptor("OSR_migration_end", void.class, long.class);
    public static final ForeignCallDescriptor IDENTITY_HASHCODE = new ForeignCallDescriptor("identity_hashcode", int.class, Object.class);
    public static final ForeignCallDescriptor VERIFY_OOP = new ForeignCallDescriptor("verify_oop", Object.class, Object.class);
    public static final ForeignCallDescriptor LOAD_AND_CLEAR_EXCEPTION = new ForeignCallDescriptor("load_and_clear_exception", Object.class, Word.class);

    public static final ForeignCallDescriptor TEST_DEOPTIMIZE_CALL_INT = new ForeignCallDescriptor("test_deoptimize_call_int", int.class, int.class);

    public static final ForeignCallDescriptor ARITHMETIC_SIN_STUB = new ForeignCallDescriptor("arithmeticSinStub", double.class, double.class);
    public static final ForeignCallDescriptor ARITHMETIC_COS_STUB = new ForeignCallDescriptor("arithmeticCosStub", double.class, double.class);
    public static final ForeignCallDescriptor ARITHMETIC_TAN_STUB = new ForeignCallDescriptor("arithmeticTanStub", double.class, double.class);
    public static final ForeignCallDescriptor ARITHMETIC_EXP_STUB = new ForeignCallDescriptor("arithmeticExpStub", double.class, double.class);
    public static final ForeignCallDescriptor ARITHMETIC_LOG_STUB = new ForeignCallDescriptor("arithmeticLogStub", double.class, double.class);
    public static final ForeignCallDescriptor ARITHMETIC_LOG10_STUB = new ForeignCallDescriptor("arithmeticLog10Stub", double.class, double.class);

    protected final HotSpotJVMCIRuntimeProvider jvmciRuntime;
    protected final HotSpotGraalRuntimeProvider runtime;

    protected final Map<ForeignCallDescriptor, HotSpotForeignCallLinkage> foreignCalls = new HashMap<>();
    protected final MetaAccessProvider metaAccess;
    protected final CodeCacheProvider codeCache;
    protected final WordTypes wordTypes;

    public HotSpotForeignCallsProviderImpl(HotSpotJVMCIRuntimeProvider jvmciRuntime, HotSpotGraalRuntimeProvider runtime, MetaAccessProvider metaAccess, CodeCacheProvider codeCache,
                    WordTypes wordTypes) {
        this.jvmciRuntime = jvmciRuntime;
        this.runtime = runtime;
        this.metaAccess = metaAccess;
        this.codeCache = codeCache;
        this.wordTypes = wordTypes;
    }

    /**
     * Registers the linkage for a foreign call.
     */
    public HotSpotForeignCallLinkage register(HotSpotForeignCallLinkage linkage) {
        assert !foreignCalls.containsKey(linkage.getDescriptor()) : "already registered linkage for " + linkage.getDescriptor();
        foreignCalls.put(linkage.getDescriptor(), linkage);
        return linkage;
    }

    /**
     * Return true if the descriptor has already been registered.
     */
    public boolean isRegistered(ForeignCallDescriptor descriptor) {
        return foreignCalls.containsKey(descriptor);
    }

    /**
     * Creates and registers the details for linking a foreign call to a {@link Stub}.
     *
     * @param descriptor the signature of the call to the stub
     * @param reexecutable specifies if the stub call can be re-executed without (meaningful) side
     *            effects. Deoptimization will not return to a point before a stub call that cannot
     *            be re-executed.
     * @param transition specifies if this is a {@linkplain Transition#LEAF leaf} call
     * @param killedLocations the memory locations killed by the stub call
     */
    public HotSpotForeignCallLinkage registerStubCall(ForeignCallDescriptor descriptor, boolean reexecutable, Transition transition, LocationIdentity... killedLocations) {
        return register(HotSpotForeignCallLinkageImpl.create(metaAccess, codeCache, wordTypes, this, descriptor, 0L, PRESERVES_REGISTERS, JavaCall, JavaCallee, transition, reexecutable,
                        killedLocations));
    }

    /**
     * Creates and registers the linkage for a foreign call.
     *
     * @param descriptor the signature of the foreign call
     * @param address the address of the code to call
     * @param outgoingCcType outgoing (caller) calling convention type
     * @param effect specifies if the call destroys or preserves all registers (apart from
     *            temporaries which are always destroyed)
     * @param transition specifies if this is a {@linkplain Transition#LEAF leaf} call
     * @param reexecutable specifies if the foreign call can be re-executed without (meaningful)
     *            side effects. Deoptimization will not return to a point before a foreign call that
     *            cannot be re-executed.
     * @param killedLocations the memory locations killed by the foreign call
     */
    public HotSpotForeignCallLinkage registerForeignCall(ForeignCallDescriptor descriptor, long address, CallingConvention.Type outgoingCcType, RegisterEffect effect, Transition transition,
                    boolean reexecutable, LocationIdentity... killedLocations) {
        Class<?> resultType = descriptor.getResultType();
        assert address != 0;
        assert transition != SAFEPOINT || resultType.isPrimitive() || Word.class.isAssignableFrom(resultType) : "non-leaf foreign calls must return objects in thread local storage: " + descriptor;
        return register(HotSpotForeignCallLinkageImpl.create(metaAccess, codeCache, wordTypes, this, descriptor, address, effect, outgoingCcType, null, transition, reexecutable, killedLocations));
    }

    /**
     * Creates a {@linkplain ForeignCallStub stub} for a foreign call.
     *
     * @param descriptor the signature of the call to the stub
     * @param address the address of the foreign code to call
     * @param prependThread true if the JavaThread value for the current thread is to be prepended
     *            to the arguments for the call to {@code address}
     * @param transition specifies if this is a {@linkplain Transition#LEAF leaf} call
     * @param reexecutable specifies if the foreign call can be re-executed without (meaningful)
     *            side effects. Deoptimization will not return to a point before a foreign call that
     *            cannot be re-executed.
     * @param killedLocations the memory locations killed by the foreign call
     */
    public void linkForeignCall(HotSpotProviders providers, ForeignCallDescriptor descriptor, long address, boolean prependThread, Transition transition, boolean reexecutable,
                    LocationIdentity... killedLocations) {
        ForeignCallStub stub = new ForeignCallStub(jvmciRuntime, providers, address, descriptor, prependThread, transition, reexecutable, killedLocations);
        HotSpotForeignCallLinkage linkage = stub.getLinkage();
        HotSpotForeignCallLinkage targetLinkage = stub.getTargetLinkage();
        linkage.setCompiledStub(stub);
        register(linkage);
        register(targetLinkage);
    }

    public static final boolean PREPEND_THREAD = true;
    public static final boolean DONT_PREPEND_THREAD = !PREPEND_THREAD;

    public static final boolean REEXECUTABLE = true;
    public static final boolean NOT_REEXECUTABLE = !REEXECUTABLE;

    public static final LocationIdentity[] NO_LOCATIONS = {};

    @Override
    public HotSpotForeignCallLinkage lookupForeignCall(ForeignCallDescriptor descriptor) {
        assert foreignCalls != null : descriptor;
        HotSpotForeignCallLinkage callTarget = foreignCalls.get(descriptor);
        callTarget.finalizeAddress(runtime.getHostBackend());
        return callTarget;
    }

    @Override
    public boolean isReexecutable(ForeignCallDescriptor descriptor) {
        assert foreignCalls.containsKey(descriptor) : "unknown foreign call: " + descriptor;
        return foreignCalls.get(descriptor).isReexecutable();
    }

    @Override
    public boolean canDeoptimize(ForeignCallDescriptor descriptor) {
        assert foreignCalls.containsKey(descriptor) : "unknown foreign call: " + descriptor;
        return foreignCalls.get(descriptor).needsDebugInfo();
    }

    @Override
    public boolean isGuaranteedSafepoint(ForeignCallDescriptor descriptor) {
        assert foreignCalls.containsKey(descriptor) : "unknown foreign call: " + descriptor;
        return foreignCalls.get(descriptor).isGuaranteedSafepoint();
    }

    @Override
    public LocationIdentity[] getKilledLocations(ForeignCallDescriptor descriptor) {
        assert foreignCalls.containsKey(descriptor) : "unknown foreign call: " + descriptor;
        return foreignCalls.get(descriptor).getKilledLocations();
    }

    @Override
    public LIRKind getValueKind(JavaKind javaKind) {
        return LIRKind.fromJavaKind(codeCache.getTarget().arch, javaKind);
    }
}
