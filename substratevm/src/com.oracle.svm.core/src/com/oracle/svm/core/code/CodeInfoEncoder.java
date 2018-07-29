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

import java.util.BitSet;
import java.util.TreeMap;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.core.common.util.TypeConversion;
import org.graalvm.compiler.core.common.util.UnsafeArrayTypeWriter;
import org.graalvm.compiler.options.Option;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.code.FrameInfoQueryResult.ValueInfo;
import com.oracle.svm.core.code.FrameInfoQueryResult.ValueType;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.deopt.DeoptEntryInfopoint;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.PinnedAllocator;
import com.oracle.svm.core.heap.ReferenceMapDecoder;
import com.oracle.svm.core.heap.ReferenceMapEncoder;
import com.oracle.svm.core.heap.SubstrateReferenceMap;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.meta.SharedField;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SharedType;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.ByteArrayReader;
import com.oracle.svm.core.util.Counter;

import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.ExceptionHandler;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaValue;

public class CodeInfoEncoder {

    public static class Options {
        @Option(help = "Statistics about code and deoptimization information") //
        public static final HostedOptionKey<Boolean> CodeInfoEncoderCounters = new HostedOptionKey<>(false);
    }

    public static class Counters {
        public final Counter.Group group = new Counter.Group(Options.CodeInfoEncoderCounters, "CodeInfoEncoder");
        final Counter methodCount = new Counter(group, "Number of methods", "Number of methods encoded");
        final Counter codeSize = new Counter(group, "Code size", "Total size of machine code");
        final Counter frameInfoSize = new Counter(group, "Frame info size", "Total size of encoded frame information");
        final Counter frameCount = new Counter(group, "Number of frames", "Number of frames encoded");
        final Counter stackValueCount = new Counter(group, "Number of stack values", "Number of stack values encoded");
        final Counter constantValueCount = new Counter(group, "Number of constant values", "Number of constant values encoded");
        final Counter virtualObjectsCount = new Counter(group, "Number of virtual objects", "Number of virtual objects encoded");
    }

    static class IPData {
        protected long ip;
        protected int frameSizeEncoding;
        protected int exceptionOffset;
        protected ReferenceMapEncoder.Input referenceMap;
        protected long referenceMapIndex;
        protected FrameInfoEncoder.FrameData frameData;
        protected IPData next;
    }

    private final PinnedAllocator allocator;
    private final TreeMap<Long, IPData> entries;
    private final FrameInfoEncoder frameInfoEncoder;

    private byte[] codeInfoIndex;
    private byte[] codeInfoEncodings;
    private byte[] referenceMapEncoding;

    public CodeInfoEncoder(FrameInfoEncoder.Customization frameInfoCustomization, PinnedAllocator allocator) {
        this.allocator = allocator;
        this.entries = new TreeMap<>();
        this.frameInfoEncoder = new FrameInfoEncoder(frameInfoCustomization, allocator);
    }

    public static int getEntryOffset(Infopoint infopoint) {
        if (infopoint instanceof Call || infopoint instanceof DeoptEntryInfopoint) {
            int offset = infopoint.pcOffset;
            if (infopoint instanceof Call) {
                // add size of the Call instruction to get the PCEntry
                offset += ((Call) infopoint).size;
            }
            return offset;
        }
        return -1;
    }

    public void addMethod(SharedMethod method, CompilationResult compilation, int compilationOffset) {
        int totalFrameSize = compilation.getTotalFrameSize();

        /* Mark the method start and register the frame size. */
        IPData startEntry = makeEntry(compilationOffset);
        startEntry.frameSizeEncoding = encodeFrameSize(totalFrameSize, true);

        /* Register the frame size for all entries that are starting points for the index. */
        long entryIP = CodeInfoDecoder.lookupEntryIP(CodeInfoDecoder.indexGranularity() + compilationOffset);
        while (entryIP <= CodeInfoDecoder.lookupEntryIP(compilation.getTargetCodeSize() + compilationOffset)) {
            IPData entry = makeEntry(entryIP);
            entry.frameSizeEncoding = encodeFrameSize(totalFrameSize, false);
            entryIP += CodeInfoDecoder.indexGranularity();
        }

        /* Make entries for all calls and deoptimization entry points of the method. */
        for (Infopoint infopoint : compilation.getInfopoints()) {
            final DebugInfo debugInfo = infopoint.debugInfo;
            if (debugInfo != null) {
                final int offset = getEntryOffset(infopoint);
                if (offset >= 0) {
                    IPData entry = makeEntry(offset + compilationOffset);
                    assert entry.referenceMap == null && entry.frameData == null;
                    entry.referenceMap = (ReferenceMapEncoder.Input) debugInfo.getReferenceMap();
                    entry.frameData = frameInfoEncoder.addDebugInfo(method, infopoint, totalFrameSize);
                }
            }
        }

        /* Make entries for all exception handlers. */
        for (ExceptionHandler handler : compilation.getExceptionHandlers()) {
            final IPData entry = makeEntry(handler.pcOffset + compilationOffset);
            assert entry.exceptionOffset == 0;
            entry.exceptionOffset = handler.handlerPos - handler.pcOffset;
        }

        ImageSingletons.lookup(Counters.class).methodCount.inc();
        ImageSingletons.lookup(Counters.class).codeSize.add(compilation.getTargetCodeSize());
    }

    private IPData makeEntry(long ip) {
        IPData result = entries.get(ip);
        if (result == null) {
            result = new IPData();
            result.ip = ip;
            entries.put(ip, result);
        }
        return result;
    }

    public void encodeAll() {
        encodeReferenceMaps();
        frameInfoEncoder.encodeAll();
        encodeIPData();
    }

    public void install(CodeInfoDecoder installTarget) {
        installTarget.setData(codeInfoIndex, codeInfoEncodings, referenceMapEncoding, frameInfoEncoder.frameInfoEncodings, frameInfoEncoder.frameInfoObjectConstants,
                        frameInfoEncoder.frameInfoSourceClassNames, frameInfoEncoder.frameInfoSourceMethodNames, frameInfoEncoder.frameInfoSourceFileNames,
                        frameInfoEncoder.frameInfoNames);

        ImageSingletons.lookup(Counters.class).frameInfoSize.add(
                        ConfigurationValues.getObjectLayout().getArrayElementOffset(JavaKind.Byte, frameInfoEncoder.frameInfoEncodings.length) +
                                        ConfigurationValues.getObjectLayout().getArrayElementOffset(JavaKind.Object, frameInfoEncoder.frameInfoObjectConstants.length));
    }

    private void encodeReferenceMaps() {
        ReferenceMapEncoder referenceMapEncoder = new ReferenceMapEncoder();
        for (IPData data : entries.values()) {
            referenceMapEncoder.add(data.referenceMap);
        }
        referenceMapEncoding = referenceMapEncoder.encodeAll(allocator);
        for (IPData data : entries.values()) {
            data.referenceMapIndex = referenceMapEncoder.lookupEncoding(data.referenceMap);
        }
    }

    /**
     * Inverse of {@link CodeInfoDecoder#decodeTotalFrameSize} and
     * {@link CodeInfoDecoder#decodeMethodStart}.
     */
    protected int encodeFrameSize(int totalFrameSize, boolean methodStart) {
        if (methodStart) {
            assert totalFrameSize > 0;
            return -totalFrameSize;
        } else {
            assert totalFrameSize >= 0;
            return totalFrameSize;
        }
    }

    private void encodeIPData() {
        IPData first = null;
        IPData prev = null;
        for (IPData cur : entries.values()) {
            if (first == null) {
                first = cur;
            } else {
                while (!TypeConversion.isU1(cur.ip - prev.ip)) {
                    final IPData filler = new IPData();
                    filler.ip = prev.ip + 0xFF;
                    prev.next = filler;
                    prev = filler;
                }
                prev.next = cur;
            }
            prev = cur;
        }

        long nextIndexIP = 0;
        UnsafeArrayTypeWriter indexBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        UnsafeArrayTypeWriter encodingBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        for (IPData data = first; data != null; data = data.next) {
            assert data.ip <= nextIndexIP;
            if (data.ip == nextIndexIP) {
                indexBuffer.putU4(encodingBuffer.getBytesWritten());
                nextIndexIP += CodeInfoDecoder.indexGranularity();
            }

            int entryFlags = 0;
            entryFlags = entryFlags | flagsForSizeEncoding(data) << CodeInfoDecoder.FS_SHIFT;
            entryFlags = entryFlags | flagsForExceptionOffset(data) << CodeInfoDecoder.EX_SHIFT;
            entryFlags = entryFlags | flagsForReferenceMapIndex(data) << CodeInfoDecoder.RM_SHIFT;
            entryFlags = entryFlags | flagsForDeoptFrameInfo(data) << CodeInfoDecoder.FI_SHIFT;

            encodingBuffer.putU1(entryFlags);
            encodingBuffer.putU1(data.next == null ? CodeInfoDecoder.DELTA_END_OF_TABLE : (data.next.ip - data.ip));

            writeSizeEncoding(encodingBuffer, data, entryFlags);
            writeExceptionOffset(encodingBuffer, data, entryFlags);
            writeReferenceMapIndex(encodingBuffer, data, entryFlags);
            writeDeoptFrameInfo(encodingBuffer, data, entryFlags);
        }

        codeInfoIndex = indexBuffer.toArray(newByteArray(TypeConversion.asU4(indexBuffer.getBytesWritten())));
        codeInfoEncodings = encodingBuffer.toArray(newByteArray(TypeConversion.asU4(encodingBuffer.getBytesWritten())));
    }

    private byte[] newByteArray(int length) {
        return allocator == null ? new byte[length] : (byte[]) allocator.newArray(byte.class, length);
    }

    /**
     * Inverse of {@link CodeInfoDecoder#updateSizeEncoding}.
     */
    private static int flagsForSizeEncoding(IPData data) {
        if (data.frameSizeEncoding == 0) {
            return CodeInfoDecoder.FS_NO_CHANGE;
        } else if (TypeConversion.isS1(data.frameSizeEncoding)) {
            return CodeInfoDecoder.FS_SIZE_S1;
        } else if (TypeConversion.isS2(data.frameSizeEncoding)) {
            return CodeInfoDecoder.FS_SIZE_S2;
        } else if (TypeConversion.isS4(data.frameSizeEncoding)) {
            return CodeInfoDecoder.FS_SIZE_S4;
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static void writeSizeEncoding(UnsafeArrayTypeWriter writeBuffer, IPData data, int entryFlags) {
        switch (CodeInfoDecoder.extractFS(entryFlags)) {
            case CodeInfoDecoder.FS_SIZE_S1:
                writeBuffer.putS1(data.frameSizeEncoding);
                break;
            case CodeInfoDecoder.FS_SIZE_S2:
                writeBuffer.putS2(data.frameSizeEncoding);
                break;
            case CodeInfoDecoder.FS_SIZE_S4:
                writeBuffer.putS4(data.frameSizeEncoding);
                break;
        }
    }

    /**
     * Inverse of {@link CodeInfoDecoder#loadExceptionOffset}.
     */
    private static int flagsForExceptionOffset(IPData data) {
        if (data.exceptionOffset == 0) {
            return CodeInfoDecoder.EX_NO_HANDLER;
        } else if (TypeConversion.isS1(data.exceptionOffset)) {
            return CodeInfoDecoder.EX_OFFSET_S1;
        } else if (TypeConversion.isS2(data.exceptionOffset)) {
            return CodeInfoDecoder.EX_OFFSET_S2;
        } else if (TypeConversion.isS4(data.exceptionOffset)) {
            return CodeInfoDecoder.EX_OFFSET_S4;
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static void writeExceptionOffset(UnsafeArrayTypeWriter writeBuffer, IPData data, int entryFlags) {
        switch (CodeInfoDecoder.extractEX(entryFlags)) {
            case CodeInfoDecoder.EX_OFFSET_S1:
                writeBuffer.putS1(data.exceptionOffset);
                break;
            case CodeInfoDecoder.EX_OFFSET_S2:
                writeBuffer.putS2(data.exceptionOffset);
                break;
            case CodeInfoDecoder.EX_OFFSET_S4:
                writeBuffer.putS4(data.exceptionOffset);
                break;
        }
    }

    /**
     * Inverse of {@link CodeInfoDecoder#loadReferenceMapIndex}.
     */
    private static int flagsForReferenceMapIndex(IPData data) {
        if (data.referenceMap == null) {
            return CodeInfoDecoder.RM_NO_MAP;
        } else if (data.referenceMap.isEmpty()) {
            return CodeInfoDecoder.RM_EMPTY_MAP;
        } else if (TypeConversion.isU2(data.referenceMapIndex)) {
            return CodeInfoDecoder.RM_INDEX_U2;
        } else if (TypeConversion.isU4(data.referenceMapIndex)) {
            return CodeInfoDecoder.RM_INDEX_U4;
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static void writeReferenceMapIndex(UnsafeArrayTypeWriter writeBuffer, IPData data, int entryFlags) {
        switch (CodeInfoDecoder.extractRM(entryFlags)) {
            case CodeInfoDecoder.RM_INDEX_U2:
                writeBuffer.putU2(data.referenceMapIndex);
                break;
            case CodeInfoDecoder.RM_INDEX_U4:
                writeBuffer.putU4(data.referenceMapIndex);
                break;
        }
    }

    /**
     * Inverse of {@link CodeInfoDecoder#loadFrameInfo}.
     */
    private static int flagsForDeoptFrameInfo(IPData data) {
        if (data.frameData == null) {
            return CodeInfoDecoder.FI_NO_DEOPT;
        } else if (TypeConversion.isS4(data.frameData.indexInEncodings)) {
            if (data.frameData.frame.isDeoptEntry) {
                return CodeInfoDecoder.FI_DEOPT_ENTRY_INDEX_S4;
            } else {
                return CodeInfoDecoder.FI_INFO_ONLY_INDEX_S4;
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static void writeDeoptFrameInfo(UnsafeArrayTypeWriter writeBuffer, IPData data, int entryFlags) {
        switch (CodeInfoDecoder.extractFI(entryFlags)) {
            case CodeInfoDecoder.FI_DEOPT_ENTRY_INDEX_S4:
            case CodeInfoDecoder.FI_INFO_ONLY_INDEX_S4:
                writeBuffer.putS4(data.frameData.indexInEncodings);
                break;
        }
    }

    public boolean verifyMethod(CompilationResult compilation, int compilationOffset) {
        CodeInfoVerifier verifier = new CodeInfoVerifier();
        install(verifier);
        verifier.verifyMethod(compilation, compilationOffset);
        return true;
    }
}

class CodeInfoVerifier extends CodeInfoDecoder {
    protected void verifyMethod(CompilationResult compilation, int compilationOffset) {
        for (int relativeIP = 0; relativeIP < compilation.getTargetCodeSize(); relativeIP++) {
            int totalIP = relativeIP + compilationOffset;
            CodeInfoQueryResult codeInfo = new CodeInfoQueryResult();
            lookupCodeInfo(totalIP, codeInfo);
            assert codeInfo.getTotalFrameSize() == compilation.getTotalFrameSize();

            assert lookupTotalFrameSize(totalIP) == codeInfo.getTotalFrameSize();
            assert lookupExceptionOffset(totalIP) == codeInfo.getExceptionOffset();
            assert lookupReferenceMapIndex(totalIP) == codeInfo.getReferenceMapIndex();
            assert getReferenceMapEncoding() == codeInfo.getReferenceMapEncoding();
        }

        for (Infopoint infopoint : compilation.getInfopoints()) {
            if (infopoint.debugInfo != null) {
                int offset = CodeInfoEncoder.getEntryOffset(infopoint);
                if (offset >= 0) {
                    assert offset < compilation.getTargetCodeSize();
                    CodeInfoQueryResult codeInfo = new CodeInfoQueryResult();
                    lookupCodeInfo(offset + compilationOffset, codeInfo);

                    CollectingObjectReferenceVisitor visitor = new CollectingObjectReferenceVisitor();
                    ReferenceMapDecoder.walkOffsetsFromPointer(WordFactory.zero(), codeInfo.getReferenceMapEncoding(), codeInfo.getReferenceMapIndex(), visitor);
                    ReferenceMapEncoder.Input expected = (ReferenceMapEncoder.Input) infopoint.debugInfo.getReferenceMap();
                    assert expected.equals(visitor.result);

                    if (codeInfo.frameInfo != CodeInfoQueryResult.NO_FRAME_INFO) {
                        verifyFrame(compilation, infopoint.debugInfo.frame(), codeInfo.frameInfo, new BitSet());
                    }
                }
            }
        }

        for (ExceptionHandler handler : compilation.getExceptionHandlers()) {
            int offset = handler.pcOffset;
            assert offset >= 0 && offset < compilation.getTargetCodeSize();

            long actual = lookupExceptionOffset(offset + compilationOffset);
            long expected = handler.handlerPos - handler.pcOffset;
            assert expected != 0;
            assert expected == actual;
        }
    }

    private void verifyFrame(CompilationResult compilation, BytecodeFrame expectedFrame, FrameInfoQueryResult actualFrame, BitSet visitedVirtualObjects) {
        assert (expectedFrame == null) == (actualFrame == null);
        if (expectedFrame == null || !actualFrame.needLocalValues) {
            return;
        }
        verifyFrame(compilation, expectedFrame.caller(), actualFrame.getCaller(), visitedVirtualObjects);

        for (int i = 0; i < expectedFrame.values.length; i++) {
            JavaValue expectedValue = expectedFrame.values[i];
            if (i >= actualFrame.getValueInfos().length) {
                assert ValueUtil.isIllegalJavaValue(expectedValue);
                continue;
            }

            ValueInfo actualValue = actualFrame.getValueInfos()[i];

            JavaKind expectedKind = FrameInfoEncoder.getFrameValueKind(expectedFrame, i);
            assert expectedKind == actualValue.getKind();
            verifyValue(compilation, expectedValue, actualValue, actualFrame, visitedVirtualObjects);
        }
    }

    private void verifyValue(CompilationResult compilation, JavaValue expectedValue, ValueInfo actualValue, FrameInfoQueryResult actualFrame, BitSet visitedVirtualObjects) {
        if (ValueUtil.isIllegalJavaValue(expectedValue)) {
            assert actualValue.getType() == ValueType.Illegal;

        } else if (ValueUtil.isConstantJavaValue(expectedValue)) {
            assert actualValue.getType() == ValueType.Constant || actualValue.getType() == ValueType.DefaultConstant;
            JavaConstant expectedConstant = ValueUtil.asConstantJavaValue(expectedValue);
            JavaConstant actualConstant = actualValue.getValue();
            FrameInfoVerifier.verifyConstant(expectedConstant, actualConstant);

        } else if (expectedValue instanceof StackSlot) {
            assert actualValue.getType() == ValueType.StackSlot;
            int expectedOffset = ((StackSlot) expectedValue).getOffset(compilation.getTotalFrameSize());
            long actualOffset = actualValue.getData();
            assert expectedOffset == actualOffset;

        } else if (ValueUtil.isVirtualObject(expectedValue)) {
            assert actualValue.getType() == ValueType.VirtualObject;
            int expectedId = ValueUtil.asVirtualObject(expectedValue).getId();
            long actualId = actualValue.getData();
            assert expectedId == actualId;

            verifyVirtualObject(compilation, ValueUtil.asVirtualObject(expectedValue), actualFrame.getVirtualObjects()[expectedId], actualFrame, visitedVirtualObjects);

        } else {
            throw shouldNotReachHere();
        }
    }

    private void verifyVirtualObject(CompilationResult compilation, VirtualObject expectedObject, ValueInfo[] actualObject, FrameInfoQueryResult actualFrame, BitSet visitedVirtualObjects) {
        if (visitedVirtualObjects.get(expectedObject.getId())) {
            return;
        }
        visitedVirtualObjects.set(expectedObject.getId());

        ObjectLayout objectLayout = ConfigurationValues.getObjectLayout();
        SharedType expectedType = (SharedType) expectedObject.getType();

        // TODO assertion does not hold for now because expectedHub is java.lang.Class, but
        // actualHub is DynamicHub
        // ValueInfo actualHub = actualObject[0];
        // assert actualHub.getType() == ValueType.Constant && actualHub.getKind() ==
        // Kind.Object && expectedType.getObjectHub().equals(actualHub.getValue());

        if (expectedType.isArray()) {
            JavaKind kind = ((SharedType) expectedType.getComponentType()).getStorageKind();
            int expectedLength = 0;
            for (int i = 0; i < expectedObject.getValues().length; i++) {
                JavaValue expectedValue = expectedObject.getValues()[i];
                UnsignedWord expectedOffset = WordFactory.unsigned(objectLayout.getArrayElementOffset(kind, expectedLength));
                ValueInfo actualValue = findActualArrayElement(actualObject, expectedOffset);
                verifyValue(compilation, expectedValue, actualValue, actualFrame, visitedVirtualObjects);

                JavaKind valueKind = expectedObject.getSlotKind(i);
                if (objectLayout.sizeInBytes(kind) == 4 && objectLayout.sizeInBytes(valueKind) == 8) {
                    /*
                     * Truffle uses arrays in a non-standard way: it declares an int[] array and
                     * uses it to also store long and double values. These values span two array
                     * elements - so we have to add 2 to the length.
                     */
                    expectedLength += 2;
                } else {
                    expectedLength++;
                }
            }
            int actualLength = actualObject[1].value.asInt();
            assert expectedLength == actualLength;

        } else {
            SharedField[] expectedFields = (SharedField[]) expectedType.getInstanceFields(true);
            int fieldIdx = 0;
            int valueIdx = 0;
            while (valueIdx < expectedObject.getValues().length) {
                SharedField expectedField = expectedFields[fieldIdx];
                fieldIdx += 1;
                JavaValue expectedValue = expectedObject.getValues()[valueIdx];
                JavaKind valueKind = expectedObject.getSlotKind(valueIdx);
                valueIdx += 1;

                JavaKind kind = expectedField.getStorageKind();
                if (objectLayout.sizeInBytes(kind) == 4 && objectLayout.sizeInBytes(valueKind) == 8) {
                    /*
                     * Truffle uses fields in a non-standard way: it declares a couple of
                     * (consecutive) int fields, and uses them to also store long and double values.
                     * These values span two fields - so we have to ignore a field.
                     */
                    fieldIdx++;
                }

                UnsignedWord expectedOffset = WordFactory.unsigned(expectedField.getLocation());
                ValueInfo actualValue = findActualField(actualObject, expectedOffset);
                verifyValue(compilation, expectedValue, actualValue, actualFrame, visitedVirtualObjects);
            }
        }
    }

    private static ValueInfo findActualArrayElement(ValueInfo[] actualObject, UnsignedWord expectedOffset) {
        DynamicHub hub = KnownIntrinsics.convertUnknownValue(SubstrateObjectConstant.asObject(actualObject[0].getValue()), DynamicHub.class);
        ObjectLayout objectLayout = ConfigurationValues.getObjectLayout();
        assert LayoutEncoding.isArray(hub.getLayoutEncoding());
        return findActualValue(actualObject, expectedOffset, objectLayout, LayoutEncoding.getArrayBaseOffset(hub.getLayoutEncoding()), 2);
    }

    private static ValueInfo findActualField(ValueInfo[] actualObject, UnsignedWord expectedOffset) {
        DynamicHub hub = KnownIntrinsics.convertUnknownValue(SubstrateObjectConstant.asObject(actualObject[0].getValue()), DynamicHub.class);
        ObjectLayout objectLayout = ConfigurationValues.getObjectLayout();
        assert LayoutEncoding.isInstance(hub.getLayoutEncoding());
        return findActualValue(actualObject, expectedOffset, objectLayout, WordFactory.unsigned(objectLayout.getFirstFieldOffset()), 1);
    }

    private static ValueInfo findActualValue(ValueInfo[] actualObject, UnsignedWord expectedOffset, ObjectLayout objectLayout, UnsignedWord startOffset, int startIdx) {
        UnsignedWord curOffset = startOffset;
        int curIdx = startIdx;
        while (curOffset.notEqual(expectedOffset)) {
            ValueInfo value = actualObject[curIdx];
            curOffset = curOffset.add(objectLayout.sizeInBytes(value.getKind()));
            curIdx++;
        }
        assert curOffset.equal(expectedOffset);
        return actualObject[curIdx];
    }
}

class CollectingObjectReferenceVisitor implements ObjectReferenceVisitor {
    protected final SubstrateReferenceMap result = new SubstrateReferenceMap();

    @Override
    public boolean visitObjectReference(Pointer objRef, boolean compressed) {
        int offset = NumUtil.safeToInt(objRef.rawValue());
        assert !result.isOffsetMarked(offset);
        result.markReferenceAtOffset(offset, compressed);
        return true;
    }
}
