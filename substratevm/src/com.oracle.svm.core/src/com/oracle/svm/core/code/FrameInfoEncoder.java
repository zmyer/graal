/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.code;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.util.FrequencyEncoder;
import org.graalvm.compiler.core.common.util.TypeConversion;
import org.graalvm.compiler.core.common.util.UnsafeArrayTypeWriter;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.code.CodeInfoEncoder.Counters;
import com.oracle.svm.core.code.FrameInfoQueryResult.ValueInfo;
import com.oracle.svm.core.code.FrameInfoQueryResult.ValueType;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.heap.PinnedAllocator;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.meta.SharedField;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SharedType;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.ByteArrayReader;

import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class FrameInfoEncoder {

    public abstract static class Customization {
        protected boolean shouldStoreMethod() {
            return true;
        }

        /**
         * Returns true if the given debugInfo should be encoded.
         *
         * @param method The method that contains the debugInfo.
         * @param infopoint The infopoint whose debugInfo that is considered for inclusion.
         */
        protected boolean shouldInclude(ResolvedJavaMethod method, Infopoint infopoint) {
            return true;
        }

        /**
         * Returns true if the given debugInfo is a valid entry point for deoptimization (and not
         * just frame information for the purpose of debugging).
         *
         * @param method The method that contains the debugInfo.
         * @param infopoint The infopoint whose debugInfo that is considered for inclusion.
         */
        protected boolean isDeoptEntry(ResolvedJavaMethod method, Infopoint infopoint) {
            return false;
        }

        /**
         * Fills the FrameInfoQueryResult.source* and {@link ValueInfo#name} fields.
         */
        protected abstract void fillDebugNames(BytecodeFrame bytecodeFrame, FrameInfoQueryResult resultFrameInfo, boolean fillValueNames);
    }

    public static class NamesFromMethod extends Customization {
        @Override
        protected void fillDebugNames(BytecodeFrame bytecodeFrame, FrameInfoQueryResult resultFrameInfo, boolean fillValueNames) {
            final ResolvedJavaMethod method = bytecodeFrame.getMethod();

            final StackTraceElement source = method.asStackTraceElement(bytecodeFrame.getBCI());
            resultFrameInfo.sourceClassName = source.getClassName();
            resultFrameInfo.sourceMethodName = source.getMethodName();
            resultFrameInfo.sourceFileName = source.getFileName();
            resultFrameInfo.sourceLineNumber = source.getLineNumber();

            if (fillValueNames) {
                final LocalVariableTable localVariableTable = bytecodeFrame.getMethod().getLocalVariableTable();
                if (localVariableTable != null) {
                    Local[] locals = localVariableTable.getLocalsAt(bytecodeFrame.getBCI());
                    if (locals != null) {
                        for (Local local : locals) {
                            if (local.getSlot() < resultFrameInfo.valueInfos.length) {
                                resultFrameInfo.valueInfos[local.getSlot()].name = local.getName();
                            } else {
                                assert ValueUtil.isIllegalJavaValue(bytecodeFrame.values[local.getSlot()]);
                            }
                        }
                    }
                }
            }
        }
    }

    public static class NamesFromImage extends Customization {
        @Override
        protected void fillDebugNames(BytecodeFrame bytecodeFrame, FrameInfoQueryResult resultFrameInfo, boolean fillValueNames) {
            final int deoptOffsetInImage = ((SharedMethod) bytecodeFrame.getMethod()).getDeoptOffsetInImage();
            if (deoptOffsetInImage != 0) {
                CodeInfoQueryResult targetCodeInfo = CodeInfoTable.lookupDeoptimizationEntrypoint(deoptOffsetInImage, resultFrameInfo.encodedBci);
                if (targetCodeInfo != null) {
                    final FrameInfoQueryResult targetFrameInfo = targetCodeInfo.getFrameInfo();
                    assert targetFrameInfo != null;

                    resultFrameInfo.sourceClassName = targetFrameInfo.sourceClassName;
                    resultFrameInfo.sourceMethodName = targetFrameInfo.sourceMethodName;
                    resultFrameInfo.sourceFileName = targetFrameInfo.sourceFileName;
                    resultFrameInfo.sourceLineNumber = targetFrameInfo.sourceLineNumber;

                    if (fillValueNames) {
                        final int minLength = Math.min(resultFrameInfo.valueInfos.length, targetFrameInfo.valueInfos.length);
                        for (int i = 0; i < minLength; i++) {
                            resultFrameInfo.valueInfos[i].name = targetFrameInfo.valueInfos[i].name;
                        }
                    }
                }
            }
        }
    }

    static class FrameData {
        protected DebugInfo debugInfo;
        protected int totalFrameSize;
        protected ValueInfo[][] virtualObjects;
        protected FrameInfoQueryResult frame;
        protected long indexInEncodings;
    }

    private final Customization customization;
    private final PinnedAllocator allocator;

    private final List<FrameData> allDebugInfos;
    private final FrequencyEncoder<JavaConstant> objectConstants;
    private final FrequencyEncoder<String> sourceClassNames;
    private final FrequencyEncoder<String> sourceMethodNames;
    private final FrequencyEncoder<String> sourceFileNames;
    private final FrequencyEncoder<String> names;

    protected byte[] frameInfoEncodings;
    protected Object[] frameInfoObjectConstants;
    protected String[] frameInfoSourceClassNames;
    protected String[] frameInfoSourceMethodNames;
    protected String[] frameInfoSourceFileNames;
    protected String[] frameInfoNames;

    protected FrameInfoEncoder(Customization customization, PinnedAllocator allocator) {
        this.customization = customization;
        this.allocator = allocator;
        this.allDebugInfos = new ArrayList<>();
        this.objectConstants = FrequencyEncoder.createEqualityEncoder();
        if (FrameInfoDecoder.encodeDebugNames() || FrameInfoDecoder.encodeSourceReferences()) {
            this.sourceClassNames = FrequencyEncoder.createEqualityEncoder();
            this.sourceMethodNames = FrequencyEncoder.createEqualityEncoder();
            this.sourceFileNames = FrequencyEncoder.createEqualityEncoder();
            this.names = FrequencyEncoder.createEqualityEncoder();
        } else {
            this.sourceClassNames = null;
            this.sourceMethodNames = null;
            this.sourceFileNames = null;
            this.names = null;
        }
    }

    protected FrameData addDebugInfo(ResolvedJavaMethod method, Infopoint infopoint, int totalFrameSize) {
        final boolean shouldIncludeMethod = customization.shouldInclude(method, infopoint);
        final boolean encodeSourceReferences = FrameInfoDecoder.encodeSourceReferences();
        if (!shouldIncludeMethod && !encodeSourceReferences) {
            return null;
        }

        final DebugInfo debugInfo = infopoint.debugInfo;
        final FrameData data = new FrameData();
        data.debugInfo = debugInfo;
        data.totalFrameSize = totalFrameSize;
        data.virtualObjects = new ValueInfo[countVirtualObjects(debugInfo)][];
        data.frame = addFrame(data, debugInfo.frame(), customization.isDeoptEntry(method, infopoint), shouldIncludeMethod);

        final boolean encodeDebugNames = shouldIncludeMethod && FrameInfoDecoder.encodeDebugNames();
        if (encodeDebugNames || FrameInfoDecoder.encodeSourceReferences()) {
            BytecodeFrame bytecodeFrame = data.debugInfo.frame();
            for (FrameInfoQueryResult resultFrame = data.frame; bytecodeFrame != null; resultFrame = resultFrame.caller) {
                customization.fillDebugNames(bytecodeFrame, resultFrame, encodeDebugNames && shouldIncludeMethod);
                bytecodeFrame = bytecodeFrame.caller();
            }

            for (FrameInfoQueryResult cur = data.frame; cur != null; cur = cur.caller) {
                sourceClassNames.addObject(cur.sourceClassName);
                sourceMethodNames.addObject(cur.sourceMethodName);
                sourceFileNames.addObject(cur.sourceFileName);

                if (encodeDebugNames) {
                    for (ValueInfo valueInfo : cur.valueInfos) {
                        if (valueInfo.name == null) {
                            valueInfo.name = "";
                        }
                        names.addObject(valueInfo.name);
                    }
                }
            }
        }

        allDebugInfos.add(data);
        return data;
    }

    private static int countVirtualObjects(DebugInfo debugInfo) {
        /*
         * We want to know the highest virtual object id in use in this DebugInfo. For that, we have
         * to recursively visited all VirtualObject in all frames.
         */
        BitSet visitedVirtualObjects = new BitSet();
        for (BytecodeFrame frame = debugInfo.frame(); frame != null; frame = frame.caller()) {
            countVirtualObjects(frame.values, visitedVirtualObjects);
        }
        /* The highest set bit in the bitset is the maximum virtual object ID. */
        return visitedVirtualObjects.length();
    }

    private static void countVirtualObjects(JavaValue[] values, BitSet visitedVirtualObjects) {
        for (JavaValue value : values) {
            if (value instanceof VirtualObject) {
                VirtualObject virtualObject = (VirtualObject) value;
                if (!visitedVirtualObjects.get(virtualObject.getId())) {
                    visitedVirtualObjects.set(virtualObject.getId());
                    countVirtualObjects(virtualObject.getValues(), visitedVirtualObjects);
                }
            }
        }
    }

    private FrameInfoQueryResult addFrame(FrameData data, BytecodeFrame frame, boolean isDeoptEntry, boolean needLocalValues) {
        FrameInfoQueryResult result = new FrameInfoQueryResult();
        if (frame.caller() != null) {
            assert !isDeoptEntry : "Deoptimization entry point information for caller frames is not encoded";
            result.caller = addFrame(data, frame.caller(), false, needLocalValues);
        }
        result.virtualObjects = data.virtualObjects;
        result.encodedBci = encodeBci(frame.getBCI(), frame.duringCall, frame.rethrowException);
        result.isDeoptEntry = isDeoptEntry;
        result.needLocalValues = needLocalValues;

        SharedMethod method = (SharedMethod) frame.getMethod();
        if (customization.shouldStoreMethod()) {
            result.deoptMethod = method;
            objectConstants.addObject(SubstrateObjectConstant.forObject(method));
        }
        result.deoptMethodOffset = method.getDeoptOffsetInImage();

        result.numLocals = frame.numLocals;
        result.numStack = frame.numStack;
        result.numLocks = frame.numLocks;

        ValueInfo[] valueInfos = null;
        if (needLocalValues) {
            JavaValue[] values = frame.values;
            int numValues = 0;
            for (int i = values.length; --i >= 0;) {
                if (!ValueUtil.isIllegalJavaValue(values[i])) {
                    // Found the last non-illegal value, i.e., the last value we have to encode.
                    numValues = i + 1;
                    break;
                }
            }

            valueInfos = new ValueInfo[numValues];
            for (int i = 0; i < numValues; i++) {
                valueInfos[i] = makeValueInfo(data, getFrameValueKind(frame, i), values[i]);
            }
        }
        result.valueInfos = valueInfos;

        ImageSingletons.lookup(Counters.class).frameCount.inc();

        return result;
    }

    public static JavaKind getFrameValueKind(BytecodeFrame frame, int valueIndex) {
        if (valueIndex < frame.numLocals) {
            return frame.getLocalValueKind(valueIndex);
        } else if (valueIndex - frame.numLocals < frame.numStack) {
            return frame.getStackValueKind(valueIndex - frame.numLocals);
        } else {
            assert valueIndex - frame.numLocals - frame.numStack < frame.numLocks;
            return JavaKind.Object;
        }
    }

    private ValueInfo makeValueInfo(FrameData data, JavaKind kind, JavaValue value) {
        ValueInfo result = new ValueInfo();
        result.kind = kind;
        if (ValueUtil.isIllegalJavaValue(value)) {
            result.type = ValueType.Illegal;
            assert result.kind == JavaKind.Illegal;

        } else if (value instanceof StackSlot) {
            StackSlot stackSlot = (StackSlot) value;
            result.type = ValueType.StackSlot;
            result.data = stackSlot.getOffset(data.totalFrameSize);
            result.isCompressedReference = isCompressedReference(stackSlot);
            ImageSingletons.lookup(Counters.class).stackValueCount.inc();

        } else if (value instanceof JavaConstant) {
            JavaConstant constant = (JavaConstant) value;
            result.value = constant;
            if (constant.isDefaultForKind()) {
                result.type = ValueType.DefaultConstant;
            } else {
                result.type = ValueType.Constant;
                if (constant.getJavaKind() == JavaKind.Object) {
                    /*
                     * Collect all Object constants, which will be stored in a separate Object[]
                     * array so that the GC can visit them.
                     */
                    objectConstants.addObject(constant);
                }
            }
            ImageSingletons.lookup(Counters.class).constantValueCount.inc();

        } else if (ValueUtil.isVirtualObject(value)) {
            VirtualObject virtualObject = (VirtualObject) value;
            result.type = ValueType.VirtualObject;
            result.data = virtualObject.getId();
            makeVirtualObject(data, virtualObject);
        } else {
            throw shouldNotReachHere();
        }
        return result;
    }

    private static boolean isCompressedReference(AllocatableValue value) {
        assert value.getPlatformKind().getVectorLength() == 1 : "Only scalar types supported";
        return value.getValueKind(LIRKind.class).isCompressedReference(0);
    }

    private static final ValueInfo[] MARKER = new ValueInfo[0];

    private void makeVirtualObject(FrameData data, VirtualObject virtualObject) {
        int id = virtualObject.getId();
        if (data.virtualObjects[id] != null) {
            return;
        }
        /* Install a non-null value to support recursive VirtualObjects. */
        data.virtualObjects[id] = MARKER;

        List<ValueInfo> valueList = new ArrayList<>(virtualObject.getValues().length + 4);
        SharedType type = (SharedType) virtualObject.getType();
        /* The first element is the hub of the virtual object. */
        valueList.add(makeValueInfo(data, JavaKind.Object, SubstrateObjectConstant.forObject(type.getHub())));

        ObjectLayout objectLayout = ConfigurationValues.getObjectLayout();
        assert type.isArray() == LayoutEncoding.isArray(type.getHub().getLayoutEncoding()) : "deoptimization code uses layout encoding to determine if type is an array";
        if (type.isArray()) {
            /* We do not know the final length yet, so add a placeholder. */
            valueList.add(null);
            int length = 0;

            JavaKind kind = ((SharedType) type.getComponentType()).getStorageKind();
            for (int i = 0; i < virtualObject.getValues().length; i++) {
                JavaValue value = virtualObject.getValues()[i];
                JavaKind valueKind = virtualObject.getSlotKind(i);
                if (objectLayout.sizeInBytes(kind) == 4 && objectLayout.sizeInBytes(valueKind) == 8) {
                    /*
                     * Truffle uses arrays in a non-standard way: it declares an int[] array and
                     * uses it to also store long and double values. These values span two array
                     * elements - so we have to write this element with the actual value kind and
                     * add 2 to the length.
                     */
                    valueList.add(makeValueInfo(data, valueKind, value));
                    length += 2;

                } else {
                    assert objectLayout.sizeInBytes(valueKind.getStackKind()) <= objectLayout.sizeInBytes(kind.getStackKind());
                    valueList.add(makeValueInfo(data, kind, value));
                    length++;
                }

                assert objectLayout.getArrayElementOffset(kind, length) == objectLayout.getArrayBaseOffset(kind) + computeOffset(valueList.subList(2, valueList.size()));
            }

            assert valueList.get(1) == null;
            valueList.set(1, makeValueInfo(data, JavaKind.Int, JavaConstant.forInt(length)));

        } else {
            /*
             * We must add filling constants for padding, so that values are contiguous. The
             * deoptimization code does not have access to field information.
             */
            SharedField[] fields = (SharedField[]) type.getInstanceFields(true);

            long curOffset = objectLayout.getFirstFieldOffset();
            int fieldIdx = 0;
            int valueIdx = 0;
            while (valueIdx < virtualObject.getValues().length) {
                SharedField field = fields[fieldIdx];
                fieldIdx += 1;
                JavaValue value = virtualObject.getValues()[valueIdx];
                JavaKind valueKind = virtualObject.getSlotKind(valueIdx);
                valueIdx += 1;

                JavaKind kind = field.getStorageKind();
                if (objectLayout.sizeInBytes(kind) == 4 && objectLayout.sizeInBytes(valueKind) == 8) {
                    /*
                     * Truffle uses fields in a non-standard way: it declares a couple of
                     * (consecutive) int fields, and uses them to also store long and double values.
                     * These values span two fields - so we have to ignore a field.
                     */
                    kind = valueKind;
                    assert fields[fieldIdx].getJavaKind() == field.getJavaKind();
                    fieldIdx++;
                }

                if (field.getLocation() >= 0) {
                    assert curOffset <= field.getLocation();
                    while (curOffset + 7 < field.getLocation()) {
                        valueList.add(makeValueInfo(data, JavaKind.Long, JavaConstant.LONG_0));
                        curOffset += 8;
                    }
                    if (curOffset + 3 < field.getLocation()) {
                        valueList.add(makeValueInfo(data, JavaKind.Int, JavaConstant.INT_0));
                        curOffset += 4;
                    }
                    if (curOffset + 1 < field.getLocation()) {
                        valueList.add(makeValueInfo(data, JavaKind.Short, JavaConstant.forShort((short) 0)));
                        curOffset += 2;
                    }
                    if (curOffset < field.getLocation()) {
                        valueList.add(makeValueInfo(data, JavaKind.Byte, JavaConstant.forByte((byte) 0)));
                        curOffset += 1;
                    }
                    assert curOffset == field.getLocation();
                    assert curOffset == computeOffset(valueList);

                    valueList.add(makeValueInfo(data, kind, value));
                    curOffset += objectLayout.sizeInBytes(kind);
                }
            }
        }

        data.virtualObjects[id] = valueList.toArray(new ValueInfo[valueList.size()]);
        ImageSingletons.lookup(Counters.class).virtualObjectsCount.inc();
    }

    private static int computeOffset(List<ValueInfo> valueInfos) {
        int result = 0;
        for (ValueInfo valueInfo : valueInfos) {
            result += ConfigurationValues.getObjectLayout().sizeInBytes(valueInfo.kind);
        }
        return result;
    }

    protected void encodeAll() {
        final JavaConstant[] encodedJavaConstants = objectConstants.encodeAll(new JavaConstant[objectConstants.getLength()]);
        frameInfoObjectConstants = newObjectArray(encodedJavaConstants.length);
        for (int i = 0; i < encodedJavaConstants.length; i++) {
            frameInfoObjectConstants[i] = KnownIntrinsics.convertUnknownValue(SubstrateObjectConstant.asObject(encodedJavaConstants[i]), Object.class);
        }

        final boolean encodeDebugNames = FrameInfoDecoder.encodeDebugNames();
        if (encodeDebugNames || FrameInfoDecoder.encodeSourceReferences()) {
            frameInfoSourceClassNames = sourceClassNames.encodeAll(newStringArray(sourceClassNames.getLength()));
            frameInfoSourceMethodNames = sourceMethodNames.encodeAll(newStringArray(sourceMethodNames.getLength()));
            frameInfoSourceFileNames = sourceFileNames.encodeAll(newStringArray(sourceFileNames.getLength()));
        }
        frameInfoNames = encodeDebugNames ? names.encodeAll(newStringArray(names.getLength())) : null;

        encodeFrameDatas();

        assert verifyEncoding();
    }

    private void encodeFrameDatas() {
        UnsafeArrayTypeWriter encodingBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        for (FrameData data : allDebugInfos) {
            data.indexInEncodings = encodingBuffer.getBytesWritten();
            encodeFrameData(data, encodingBuffer);
        }
        frameInfoEncodings = encodingBuffer.toArray(newByteArray(TypeConversion.asS4(encodingBuffer.getBytesWritten())));
    }

    private Object[] newObjectArray(int length) {
        return allocator == null ? new Object[length] : (Object[]) allocator.newArray(Object.class, length);
    }

    private String[] newStringArray(int length) {
        return allocator == null ? new String[length] : (String[]) allocator.newArray(String.class, length);
    }

    private byte[] newByteArray(int length) {
        return allocator == null ? new byte[length] : (byte[]) allocator.newArray(byte.class, length);
    }

    private void encodeFrameData(FrameData data, UnsafeArrayTypeWriter encodingBuffer) {
        for (FrameInfoQueryResult cur = data.frame; cur != null; cur = cur.caller) {
            assert cur.encodedBci != FrameInfoDecoder.NO_CALLER_BCI : "used as the end marker during decoding";
            final boolean needLocalValues = cur.needLocalValues;
            if (!needLocalValues) {
                cur.encodedBci = FrameInfoDecoder.NO_LOCAL_INFO_BCI;
            }
            encodingBuffer.putSV(cur.encodedBci);
            assert cur == data.frame || !cur.isDeoptEntry : "Deoptimization entry information for caller frames is not persisted";

            if (needLocalValues) {
                encodingBuffer.putUV(cur.numLocks);
                encodingBuffer.putUV(cur.numLocals);
                encodingBuffer.putUV(cur.numStack);

                int deoptMethodIndex;
                if (cur.deoptMethod != null) {
                    deoptMethodIndex = -1 - objectConstants.getIndex(SubstrateObjectConstant.forObject(cur.deoptMethod));
                    assert deoptMethodIndex < 0;
                    assert cur.deoptMethodOffset == cur.deoptMethod.getDeoptOffsetInImage();
                } else {
                    deoptMethodIndex = cur.deoptMethodOffset;
                    assert deoptMethodIndex >= 0;
                }
                encodingBuffer.putSV(deoptMethodIndex);

                encodeValues(cur.valueInfos, encodingBuffer);

                if (cur == data.frame) {
                    // Write virtual objects only for first frame.
                    encodingBuffer.putUV(cur.virtualObjects.length);
                    for (ValueInfo[] virtualObject : cur.virtualObjects) {
                        encodeValues(virtualObject, encodingBuffer);
                    }
                }
            }

            final boolean encodeDebugNames = needLocalValues && FrameInfoDecoder.encodeDebugNames();
            if (encodeDebugNames || FrameInfoDecoder.encodeSourceReferences()) {
                final int classIndex = sourceClassNames.getIndex(cur.sourceClassName);
                final int methodIndex = sourceMethodNames.getIndex(cur.sourceMethodName);
                final int fileIndex = sourceFileNames.getIndex(cur.sourceFileName);

                cur.sourceClassNameIndex = classIndex;
                cur.sourceMethodNameIndex = methodIndex;
                cur.sourceFileNameIndex = fileIndex;

                encodingBuffer.putSV(classIndex);
                encodingBuffer.putSV(methodIndex);
                encodingBuffer.putSV(fileIndex);
                encodingBuffer.putSV(cur.sourceLineNumber);
            }

            if (encodeDebugNames) {
                for (ValueInfo valueInfo : cur.valueInfos) {
                    valueInfo.nameIndex = names.getIndex(valueInfo.name);
                    encodingBuffer.putUV(valueInfo.nameIndex);
                }
            }
        }
        encodingBuffer.putSV(FrameInfoDecoder.NO_CALLER_BCI);
    }

    private void encodeValues(ValueInfo[] valueInfos, UnsafeArrayTypeWriter encodingBuffer) {
        encodingBuffer.putUV(valueInfos.length);
        for (ValueInfo valueInfo : valueInfos) {
            if (valueInfo.type == ValueType.Constant) {
                if (valueInfo.kind == JavaKind.Object) {
                    valueInfo.data = objectConstants.getIndex(valueInfo.value);
                } else {
                    valueInfo.data = encodePrimitiveConstant(valueInfo.value);
                }
            }

            encodingBuffer.putU1(encodeFlags(valueInfo.type, valueInfo.kind, valueInfo.isCompressedReference));
            if (valueInfo.type.hasData) {
                encodingBuffer.putSV(valueInfo.data);
            }
        }
    }

    protected static long encodePrimitiveConstant(JavaConstant constant) {
        switch (constant.getJavaKind()) {
            case Float:
                return Float.floatToRawIntBits(constant.asFloat());
            case Double:
                return Double.doubleToRawLongBits(constant.asDouble());
            default:
                return constant.asLong();
        }
    }

    private static int encodeFlags(ValueType type, JavaKind kind, boolean isCompressedReference) {
        return (type.ordinal() << FrameInfoDecoder.TYPE_SHIFT) | (kind.ordinal() << FrameInfoDecoder.KIND_SHIFT) | ((isCompressedReference ? 1 : 0) << FrameInfoDecoder.IS_COMPRESSED_REFERENCE_SHIFT);
    }

    /**
     * Encodes the BCI and the duringCall- and rethrowException flags into a single value.
     */
    public static long encodeBci(int bci, boolean duringCall, boolean rethrowException) {
        return (((long) bci) << FrameInfoDecoder.BCI_SHIFT) | (duringCall ? FrameInfoDecoder.DURING_CALL_MASK : 0) | (rethrowException ? FrameInfoDecoder.RETHROW_EXCEPTION_MASK : 0);
    }

    private boolean verifyEncoding() {
        for (FrameData expectedData : allDebugInfos) {
            FrameInfoQueryResult actualFrame = FrameInfoDecoder.decodeFrameInfo(expectedData.frame.isDeoptEntry, new ReusableTypeReader(frameInfoEncodings, expectedData.indexInEncodings),
                            frameInfoObjectConstants, frameInfoSourceClassNames, frameInfoSourceMethodNames, frameInfoSourceFileNames, frameInfoNames,
                            FrameInfoDecoder.HeapBasedFrameInfoQueryResultAllocator, FrameInfoDecoder.HeapBasedValueInfoAllocator, true);
            FrameInfoVerifier.verifyFrames(expectedData, expectedData.frame, actualFrame);
        }
        return true;
    }
}

class FrameInfoVerifier {
    protected static void verifyFrames(FrameInfoEncoder.FrameData expectedData, FrameInfoQueryResult expectedTopFrame, FrameInfoQueryResult actualTopFrame) {
        FrameInfoQueryResult expectedFrame = expectedTopFrame;
        FrameInfoQueryResult actualFrame = actualTopFrame;
        while (expectedFrame != null) {
            assert actualFrame != null;
            assert expectedFrame.isDeoptEntry == actualFrame.isDeoptEntry;
            assert expectedFrame.needLocalValues == actualFrame.needLocalValues;
            if (expectedFrame.needLocalValues) {
                assert expectedFrame.encodedBci == actualFrame.encodedBci;
                assert expectedFrame.deoptMethod == null && actualFrame.deoptMethod == null ||
                                ((expectedFrame.deoptMethod != null) && expectedFrame.deoptMethod.equals(actualFrame.deoptMethod));
                assert expectedFrame.deoptMethodOffset == actualFrame.deoptMethodOffset;
                assert expectedFrame.encodedBci == actualFrame.encodedBci;
                assert expectedFrame.isDeoptEntry == actualFrame.isDeoptEntry;
                assert expectedFrame.numLocals == actualFrame.numLocals;
                assert expectedFrame.numStack == actualFrame.numStack;
                assert expectedFrame.numLocks == actualFrame.numLocks;

                verifyValues(expectedFrame.valueInfos, actualFrame.valueInfos);
                assert expectedFrame.virtualObjects == expectedTopFrame.virtualObjects;
                assert actualFrame.virtualObjects == actualTopFrame.virtualObjects;
            }

            assert Objects.equals(expectedFrame.sourceClassName, actualFrame.sourceClassName);
            assert Objects.equals(expectedFrame.sourceMethodName, actualFrame.sourceMethodName);
            assert Objects.equals(expectedFrame.sourceFileName, actualFrame.sourceFileName);
            assert expectedFrame.sourceLineNumber == actualFrame.sourceLineNumber;

            assert expectedFrame.sourceClassNameIndex == actualFrame.sourceClassNameIndex;
            assert expectedFrame.sourceMethodNameIndex == actualFrame.sourceMethodNameIndex;
            assert expectedFrame.sourceFileNameIndex == actualFrame.sourceFileNameIndex;

            expectedFrame = expectedFrame.caller;
            actualFrame = actualFrame.caller;
        }
        assert actualFrame == null;

        if (actualTopFrame.needLocalValues) {
            assert expectedData.virtualObjects.length == actualTopFrame.virtualObjects.length;
            for (int i = 0; i < expectedData.virtualObjects.length; i++) {
                verifyValues(expectedData.virtualObjects[i], actualTopFrame.virtualObjects[i]);
            }
        }
    }

    private static void verifyValues(ValueInfo[] expectedValues, ValueInfo[] actualValues) {
        assert expectedValues.length == actualValues.length;
        for (int i = 0; i < expectedValues.length; i++) {
            ValueInfo expectedValue = expectedValues[i];
            ValueInfo actualValue = actualValues[i];

            assert expectedValue.type == actualValue.type;
            assert expectedValue.kind.equals(actualValue.kind);
            assert expectedValue.isCompressedReference == actualValue.isCompressedReference;
            assert expectedValue.data == actualValue.data;
            verifyConstant(expectedValue.value, actualValue.value);
            assert Objects.equals(expectedValue.name, actualValue.name);
            assert expectedValue.nameIndex == actualValue.nameIndex;
        }
    }

    protected static void verifyConstant(JavaConstant expectedConstant, JavaConstant actualConstant) {
        if (expectedConstant != null && expectedConstant.getJavaKind().isPrimitive()) {
            /* During compilation, the kind of a smaller-than-int constant is often Int. */
            assert FrameInfoEncoder.encodePrimitiveConstant(expectedConstant) == FrameInfoEncoder.encodePrimitiveConstant(actualConstant);
        } else {
            assert Objects.equals(expectedConstant, actualConstant);
        }
    }
}
