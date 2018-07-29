/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex.tregex.parser;

import com.oracle.truffle.regex.chardata.CodePointRange;
import com.oracle.truffle.regex.chardata.CodePointSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;

public class CaseFoldTable {

    public static CodePointSet applyCaseFold(CodePointSet codePointSet, boolean unicode) {
        CaseFoldTableEntry[] tableEntries = unicode ? UNICODE_TABLE_ENTRIES : NON_UNICODE_TABLE_ENTRIES;
        CodePointSet result = codePointSet.copy();
        for (CodePointRange r : codePointSet.getRanges()) {
            int search = Arrays.binarySearch(tableEntries, r);
            if (CodePointRange.binarySearchExactMatch(tableEntries, r, search)) {
                tableEntries[search].apply(result, r);
                continue;
            }
            int firstIntersection = CodePointRange.binarySearchGetFirstIntersecting(tableEntries, r, search);
            if (CodePointRange.binarySearchNoIntersectingFound(tableEntries, firstIntersection)) {
                continue;
            }
            for (int i = firstIntersection; i < tableEntries.length; i++) {
                CaseFoldTableEntry entry = tableEntries[i];
                if (entry.getRange().rightOf(r)) {
                    break;
                }
                CodePointRange intersection = r.createIntersection(entry.getRange());
                if (intersection != null) {
                    entry.apply(result, intersection);
                }
            }
        }
        return result;
    }

    private abstract static class CaseFoldTableEntry implements Comparable<ContainsRange>, ContainsRange {

        private final CodePointRange range;

        private CaseFoldTableEntry(CodePointRange range) {
            this.range = range;
        }

        @Override
        public CodePointRange getRange() {
            return range;
        }

        @Override
        public int compareTo(ContainsRange o) {
            return range.compareTo(o.getRange());
        }

        public abstract void apply(CodePointSet codePointSet, CodePointRange subRange);
    }

    private static final class CaseFoldAlternating extends CaseFoldTableEntry {

        private final boolean aligned;

        private CaseFoldAlternating(CodePointRange range, boolean aligned) {
            super(range);
            this.aligned = aligned;
        }

        private int alt(int i) {
            return aligned ? i ^ 1 : ((i - 1) ^ 1) + 1;
        }

        @Override
        public void apply(CodePointSet codePointSet, CodePointRange subRange) {
            final CodePointRange altRange = CodePointRange.fromUnordered(alt(subRange.lo), alt(subRange.hi));
            if (!subRange.contains(altRange)) {
                codePointSet.addRange(altRange);
            }
        }
    }

    private static final class CaseFoldDelta extends CaseFoldTableEntry {

        private final int delta;

        private CaseFoldDelta(CodePointRange range, int delta) {
            super(range);
            this.delta = delta;
        }

        @Override
        public void apply(CodePointSet codePointSet, CodePointRange subRange) {
            codePointSet.addRange(subRange.move(delta));
        }
    }

    private static final class CaseFoldMap extends CaseFoldTableEntry {

        private final TreeSet<CodePointRange> target;

        private CaseFoldMap(CodePointRange range, TreeSet<CodePointRange> target) {
            super(range);
            this.target = target;
        }

        @Override
        public void apply(CodePointSet codePointSet, CodePointRange unused) {
            for (CodePointRange r : target) {
                codePointSet.addRange(r);
            }
        }
    }

    private static TreeSet<CodePointRange> rangeSet(int... values) {
        TreeSet<CodePointRange> ret = new TreeSet<>();
        for (int v : values) {
            ret.add(new CodePointRange(v));
        }
        return ret;
    }

    private static TreeSet<CodePointRange> rangeSet(CodePointRange... ranges) {
        return new TreeSet<>(Arrays.asList(ranges));
    }

    private static final ArrayList<TreeSet<CodePointRange>> CHARACTER_SET_TABLE = new ArrayList<>(Arrays.asList(
                    rangeSet(0x00b5, 0x039c, 0x03bc),
                    rangeSet(new CodePointRange(0x01c4, 0x01c6)),
                    rangeSet(new CodePointRange(0x01c7, 0x01c9)),
                    rangeSet(new CodePointRange(0x01ca, 0x01cc)),
                    rangeSet(new CodePointRange(0x01f1, 0x01f3)),
                    rangeSet(0x0345, 0x0399, 0x03b9, 0x1fbe),
                    rangeSet(0x0392, 0x03b2, 0x03d0),
                    rangeSet(0x0395, 0x03b5, 0x03f5),
                    rangeSet(0x0398, 0x03b8, 0x03d1),
                    rangeSet(0x039a, 0x03ba, 0x03f0),
                    rangeSet(0x03a0, 0x03c0, 0x03d6),
                    rangeSet(0x03a1, 0x03c1, 0x03f1),
                    rangeSet(new CodePointRange(0x03a3), new CodePointRange(0x03c2, 0x03c3)),
                    rangeSet(0x03a6, 0x03c6, 0x03d5),
                    rangeSet(0x0412, 0x0432, 0x1c80),
                    rangeSet(0x0414, 0x0434, 0x1c81),
                    rangeSet(0x041e, 0x043e, 0x1c82),
                    rangeSet(0x0421, 0x0441, 0x1c83),
                    rangeSet(new CodePointRange(0x0422), new CodePointRange(0x0442), new CodePointRange(0x1c84, 0x1c85)),
                    rangeSet(0x042a, 0x044a, 0x1c86),
                    rangeSet(new CodePointRange(0x0462, 0x0463), new CodePointRange(0x1c87)),
                    rangeSet(new CodePointRange(0x1c88), new CodePointRange(0xa64a, 0xa64b)),
                    rangeSet(new CodePointRange(0x1e60, 0x1e61), new CodePointRange(0x1e9b)),
                    rangeSet(0x004b, 0x006b, 0x212a),
                    rangeSet(0x0053, 0x0073, 0x017f),
                    rangeSet(0x00c5, 0x00e5, 0x212b),
                    rangeSet(0x0398, 0x03b8, 0x03d1, 0x03f4),
                    rangeSet(0x03a9, 0x03c9, 0x2126)));

    private static CaseFoldDelta deltaPositive(int lo, int hi, int delta) {
        return new CaseFoldDelta(new CodePointRange(lo, hi), delta);
    }

    private static CaseFoldDelta deltaNegative(int lo, int hi, int delta) {
        return new CaseFoldDelta(new CodePointRange(lo, hi), delta * -1);
    }

    private static CaseFoldMap directMapping(int lo, int hi, int setIndex) {
        return new CaseFoldMap(new CodePointRange(lo, hi), CHARACTER_SET_TABLE.get(setIndex));
    }

    private static CaseFoldAlternating alternatingUL(int lo, int hi) {
        return new CaseFoldAlternating(new CodePointRange(lo, hi), false);
    }

    private static CaseFoldAlternating alternatingAL(int lo, int hi) {
        return new CaseFoldAlternating(new CodePointRange(lo, hi), true);
    }

    private static final CaseFoldTableEntry[] NON_UNICODE_TABLE_ENTRIES = new CaseFoldTableEntry[]{
                    deltaPositive(0x0041, 0x005a, 0x0020),
                    deltaNegative(0x0061, 0x007a, 0x0020),
                    directMapping(0x00b5, 0x00b5, 0x0000),
                    deltaPositive(0x00c0, 0x00d6, 0x0020),
                    deltaPositive(0x00d8, 0x00de, 0x0020),
                    deltaNegative(0x00e0, 0x00f6, 0x0020),
                    deltaNegative(0x00f8, 0x00fe, 0x0020),
                    deltaPositive(0x00ff, 0x00ff, 0x0079),
                    alternatingAL(0x0100, 0x012f),
                    alternatingAL(0x0132, 0x0137),
                    alternatingUL(0x0139, 0x0148),
                    alternatingAL(0x014a, 0x0177),
                    deltaNegative(0x0178, 0x0178, 0x0079),
                    alternatingUL(0x0179, 0x017e),
                    deltaPositive(0x0180, 0x0180, 0x00c3),
                    deltaPositive(0x0181, 0x0181, 0x00d2),
                    alternatingAL(0x0182, 0x0185),
                    deltaPositive(0x0186, 0x0186, 0x00ce),
                    alternatingUL(0x0187, 0x0188),
                    deltaPositive(0x0189, 0x018a, 0x00cd),
                    alternatingUL(0x018b, 0x018c),
                    deltaPositive(0x018e, 0x018e, 0x004f),
                    deltaPositive(0x018f, 0x018f, 0x00ca),
                    deltaPositive(0x0190, 0x0190, 0x00cb),
                    alternatingUL(0x0191, 0x0192),
                    deltaPositive(0x0193, 0x0193, 0x00cd),
                    deltaPositive(0x0194, 0x0194, 0x00cf),
                    deltaPositive(0x0195, 0x0195, 0x0061),
                    deltaPositive(0x0196, 0x0196, 0x00d3),
                    deltaPositive(0x0197, 0x0197, 0x00d1),
                    alternatingAL(0x0198, 0x0199),
                    deltaPositive(0x019a, 0x019a, 0x00a3),
                    deltaPositive(0x019c, 0x019c, 0x00d3),
                    deltaPositive(0x019d, 0x019d, 0x00d5),
                    deltaPositive(0x019e, 0x019e, 0x0082),
                    deltaPositive(0x019f, 0x019f, 0x00d6),
                    alternatingAL(0x01a0, 0x01a5),
                    deltaPositive(0x01a6, 0x01a6, 0x00da),
                    alternatingUL(0x01a7, 0x01a8),
                    deltaPositive(0x01a9, 0x01a9, 0x00da),
                    alternatingAL(0x01ac, 0x01ad),
                    deltaPositive(0x01ae, 0x01ae, 0x00da),
                    alternatingUL(0x01af, 0x01b0),
                    deltaPositive(0x01b1, 0x01b2, 0x00d9),
                    alternatingUL(0x01b3, 0x01b6),
                    deltaPositive(0x01b7, 0x01b7, 0x00db),
                    alternatingAL(0x01b8, 0x01b9),
                    alternatingAL(0x01bc, 0x01bd),
                    deltaPositive(0x01bf, 0x01bf, 0x0038),
                    directMapping(0x01c4, 0x01c6, 0x0001),
                    directMapping(0x01c7, 0x01c9, 0x0002),
                    directMapping(0x01ca, 0x01cc, 0x0003),
                    alternatingUL(0x01cd, 0x01dc),
                    deltaNegative(0x01dd, 0x01dd, 0x004f),
                    alternatingAL(0x01de, 0x01ef),
                    directMapping(0x01f1, 0x01f3, 0x0004),
                    alternatingAL(0x01f4, 0x01f5),
                    deltaNegative(0x01f6, 0x01f6, 0x0061),
                    deltaNegative(0x01f7, 0x01f7, 0x0038),
                    alternatingAL(0x01f8, 0x021f),
                    deltaNegative(0x0220, 0x0220, 0x0082),
                    alternatingAL(0x0222, 0x0233),
                    deltaPositive(0x023a, 0x023a, 0x2a2b),
                    alternatingUL(0x023b, 0x023c),
                    deltaNegative(0x023d, 0x023d, 0x00a3),
                    deltaPositive(0x023e, 0x023e, 0x2a28),
                    deltaPositive(0x023f, 0x0240, 0x2a3f),
                    alternatingUL(0x0241, 0x0242),
                    deltaNegative(0x0243, 0x0243, 0x00c3),
                    deltaPositive(0x0244, 0x0244, 0x0045),
                    deltaPositive(0x0245, 0x0245, 0x0047),
                    alternatingAL(0x0246, 0x024f),
                    deltaPositive(0x0250, 0x0250, 0x2a1f),
                    deltaPositive(0x0251, 0x0251, 0x2a1c),
                    deltaPositive(0x0252, 0x0252, 0x2a1e),
                    deltaNegative(0x0253, 0x0253, 0x00d2),
                    deltaNegative(0x0254, 0x0254, 0x00ce),
                    deltaNegative(0x0256, 0x0257, 0x00cd),
                    deltaNegative(0x0259, 0x0259, 0x00ca),
                    deltaNegative(0x025b, 0x025b, 0x00cb),
                    deltaPositive(0x025c, 0x025c, 0xa54f),
                    deltaNegative(0x0260, 0x0260, 0x00cd),
                    deltaPositive(0x0261, 0x0261, 0xa54b),
                    deltaNegative(0x0263, 0x0263, 0x00cf),
                    deltaPositive(0x0265, 0x0265, 0xa528),
                    deltaPositive(0x0266, 0x0266, 0xa544),
                    deltaNegative(0x0268, 0x0268, 0x00d1),
                    deltaNegative(0x0269, 0x0269, 0x00d3),
                    deltaPositive(0x026a, 0x026a, 0xa544),
                    deltaPositive(0x026b, 0x026b, 0x29f7),
                    deltaPositive(0x026c, 0x026c, 0xa541),
                    deltaNegative(0x026f, 0x026f, 0x00d3),
                    deltaPositive(0x0271, 0x0271, 0x29fd),
                    deltaNegative(0x0272, 0x0272, 0x00d5),
                    deltaNegative(0x0275, 0x0275, 0x00d6),
                    deltaPositive(0x027d, 0x027d, 0x29e7),
                    deltaNegative(0x0280, 0x0280, 0x00da),
                    deltaNegative(0x0283, 0x0283, 0x00da),
                    deltaPositive(0x0287, 0x0287, 0xa52a),
                    deltaNegative(0x0288, 0x0288, 0x00da),
                    deltaNegative(0x0289, 0x0289, 0x0045),
                    deltaNegative(0x028a, 0x028b, 0x00d9),
                    deltaNegative(0x028c, 0x028c, 0x0047),
                    deltaNegative(0x0292, 0x0292, 0x00db),
                    deltaPositive(0x029d, 0x029d, 0xa515),
                    deltaPositive(0x029e, 0x029e, 0xa512),
                    directMapping(0x0345, 0x0345, 0x0005),
                    alternatingAL(0x0370, 0x0373),
                    alternatingAL(0x0376, 0x0377),
                    deltaPositive(0x037b, 0x037d, 0x0082),
                    deltaPositive(0x037f, 0x037f, 0x0074),
                    deltaPositive(0x0386, 0x0386, 0x0026),
                    deltaPositive(0x0388, 0x038a, 0x0025),
                    deltaPositive(0x038c, 0x038c, 0x0040),
                    deltaPositive(0x038e, 0x038f, 0x003f),
                    deltaPositive(0x0391, 0x0391, 0x0020),
                    directMapping(0x0392, 0x0392, 0x0006),
                    deltaPositive(0x0393, 0x0394, 0x0020),
                    directMapping(0x0395, 0x0395, 0x0007),
                    deltaPositive(0x0396, 0x0397, 0x0020),
                    directMapping(0x0398, 0x0398, 0x0008),
                    directMapping(0x0399, 0x0399, 0x0005),
                    directMapping(0x039a, 0x039a, 0x0009),
                    deltaPositive(0x039b, 0x039b, 0x0020),
                    directMapping(0x039c, 0x039c, 0x0000),
                    deltaPositive(0x039d, 0x039f, 0x0020),
                    directMapping(0x03a0, 0x03a0, 0x000a),
                    directMapping(0x03a1, 0x03a1, 0x000b),
                    directMapping(0x03a3, 0x03a3, 0x000c),
                    deltaPositive(0x03a4, 0x03a5, 0x0020),
                    directMapping(0x03a6, 0x03a6, 0x000d),
                    deltaPositive(0x03a7, 0x03ab, 0x0020),
                    deltaNegative(0x03ac, 0x03ac, 0x0026),
                    deltaNegative(0x03ad, 0x03af, 0x0025),
                    deltaNegative(0x03b1, 0x03b1, 0x0020),
                    directMapping(0x03b2, 0x03b2, 0x0006),
                    deltaNegative(0x03b3, 0x03b4, 0x0020),
                    directMapping(0x03b5, 0x03b5, 0x0007),
                    deltaNegative(0x03b6, 0x03b7, 0x0020),
                    directMapping(0x03b8, 0x03b8, 0x0008),
                    directMapping(0x03b9, 0x03b9, 0x0005),
                    directMapping(0x03ba, 0x03ba, 0x0009),
                    deltaNegative(0x03bb, 0x03bb, 0x0020),
                    directMapping(0x03bc, 0x03bc, 0x0000),
                    deltaNegative(0x03bd, 0x03bf, 0x0020),
                    directMapping(0x03c0, 0x03c0, 0x000a),
                    directMapping(0x03c1, 0x03c1, 0x000b),
                    directMapping(0x03c2, 0x03c3, 0x000c),
                    deltaNegative(0x03c4, 0x03c5, 0x0020),
                    directMapping(0x03c6, 0x03c6, 0x000d),
                    deltaNegative(0x03c7, 0x03cb, 0x0020),
                    deltaNegative(0x03cc, 0x03cc, 0x0040),
                    deltaNegative(0x03cd, 0x03ce, 0x003f),
                    deltaPositive(0x03cf, 0x03cf, 0x0008),
                    directMapping(0x03d0, 0x03d0, 0x0006),
                    directMapping(0x03d1, 0x03d1, 0x0008),
                    directMapping(0x03d5, 0x03d5, 0x000d),
                    directMapping(0x03d6, 0x03d6, 0x000a),
                    deltaNegative(0x03d7, 0x03d7, 0x0008),
                    alternatingAL(0x03d8, 0x03ef),
                    directMapping(0x03f0, 0x03f0, 0x0009),
                    directMapping(0x03f1, 0x03f1, 0x000b),
                    deltaPositive(0x03f2, 0x03f2, 0x0007),
                    deltaNegative(0x03f3, 0x03f3, 0x0074),
                    directMapping(0x03f5, 0x03f5, 0x0007),
                    alternatingUL(0x03f7, 0x03f8),
                    deltaNegative(0x03f9, 0x03f9, 0x0007),
                    alternatingAL(0x03fa, 0x03fb),
                    deltaNegative(0x03fd, 0x03ff, 0x0082),
                    deltaPositive(0x0400, 0x040f, 0x0050),
                    deltaPositive(0x0410, 0x0411, 0x0020),
                    directMapping(0x0412, 0x0412, 0x000e),
                    deltaPositive(0x0413, 0x0413, 0x0020),
                    directMapping(0x0414, 0x0414, 0x000f),
                    deltaPositive(0x0415, 0x041d, 0x0020),
                    directMapping(0x041e, 0x041e, 0x0010),
                    deltaPositive(0x041f, 0x0420, 0x0020),
                    directMapping(0x0421, 0x0421, 0x0011),
                    directMapping(0x0422, 0x0422, 0x0012),
                    deltaPositive(0x0423, 0x0429, 0x0020),
                    directMapping(0x042a, 0x042a, 0x0013),
                    deltaPositive(0x042b, 0x042f, 0x0020),
                    deltaNegative(0x0430, 0x0431, 0x0020),
                    directMapping(0x0432, 0x0432, 0x000e),
                    deltaNegative(0x0433, 0x0433, 0x0020),
                    directMapping(0x0434, 0x0434, 0x000f),
                    deltaNegative(0x0435, 0x043d, 0x0020),
                    directMapping(0x043e, 0x043e, 0x0010),
                    deltaNegative(0x043f, 0x0440, 0x0020),
                    directMapping(0x0441, 0x0441, 0x0011),
                    directMapping(0x0442, 0x0442, 0x0012),
                    deltaNegative(0x0443, 0x0449, 0x0020),
                    directMapping(0x044a, 0x044a, 0x0013),
                    deltaNegative(0x044b, 0x044f, 0x0020),
                    deltaNegative(0x0450, 0x045f, 0x0050),
                    alternatingAL(0x0460, 0x0461),
                    directMapping(0x0462, 0x0463, 0x0014),
                    alternatingAL(0x0464, 0x0481),
                    alternatingAL(0x048a, 0x04bf),
                    deltaPositive(0x04c0, 0x04c0, 0x000f),
                    alternatingUL(0x04c1, 0x04ce),
                    deltaNegative(0x04cf, 0x04cf, 0x000f),
                    alternatingAL(0x04d0, 0x052f),
                    deltaPositive(0x0531, 0x0556, 0x0030),
                    deltaNegative(0x0561, 0x0586, 0x0030),
                    deltaPositive(0x10a0, 0x10c5, 0x1c60),
                    deltaPositive(0x10c7, 0x10c7, 0x1c60),
                    deltaPositive(0x10cd, 0x10cd, 0x1c60),
                    deltaPositive(0x13a0, 0x13ef, 0x97d0),
                    deltaPositive(0x13f0, 0x13f5, 0x0008),
                    deltaNegative(0x13f8, 0x13fd, 0x0008),
                    directMapping(0x1c80, 0x1c80, 0x000e),
                    directMapping(0x1c81, 0x1c81, 0x000f),
                    directMapping(0x1c82, 0x1c82, 0x0010),
                    directMapping(0x1c83, 0x1c83, 0x0011),
                    directMapping(0x1c84, 0x1c85, 0x0012),
                    directMapping(0x1c86, 0x1c86, 0x0013),
                    directMapping(0x1c87, 0x1c87, 0x0014),
                    directMapping(0x1c88, 0x1c88, 0x0015),
                    deltaPositive(0x1d79, 0x1d79, 0x8a04),
                    deltaPositive(0x1d7d, 0x1d7d, 0x0ee6),
                    alternatingAL(0x1e00, 0x1e5f),
                    directMapping(0x1e60, 0x1e61, 0x0016),
                    alternatingAL(0x1e62, 0x1e95),
                    directMapping(0x1e9b, 0x1e9b, 0x0016),
                    alternatingAL(0x1ea0, 0x1eff),
                    deltaPositive(0x1f00, 0x1f07, 0x0008),
                    deltaNegative(0x1f08, 0x1f0f, 0x0008),
                    deltaPositive(0x1f10, 0x1f15, 0x0008),
                    deltaNegative(0x1f18, 0x1f1d, 0x0008),
                    deltaPositive(0x1f20, 0x1f27, 0x0008),
                    deltaNegative(0x1f28, 0x1f2f, 0x0008),
                    deltaPositive(0x1f30, 0x1f37, 0x0008),
                    deltaNegative(0x1f38, 0x1f3f, 0x0008),
                    deltaPositive(0x1f40, 0x1f45, 0x0008),
                    deltaNegative(0x1f48, 0x1f4d, 0x0008),
                    deltaPositive(0x1f51, 0x1f51, 0x0008),
                    deltaPositive(0x1f53, 0x1f53, 0x0008),
                    deltaPositive(0x1f55, 0x1f55, 0x0008),
                    deltaPositive(0x1f57, 0x1f57, 0x0008),
                    deltaNegative(0x1f59, 0x1f59, 0x0008),
                    deltaNegative(0x1f5b, 0x1f5b, 0x0008),
                    deltaNegative(0x1f5d, 0x1f5d, 0x0008),
                    deltaNegative(0x1f5f, 0x1f5f, 0x0008),
                    deltaPositive(0x1f60, 0x1f67, 0x0008),
                    deltaNegative(0x1f68, 0x1f6f, 0x0008),
                    deltaPositive(0x1f70, 0x1f71, 0x004a),
                    deltaPositive(0x1f72, 0x1f75, 0x0056),
                    deltaPositive(0x1f76, 0x1f77, 0x0064),
                    deltaPositive(0x1f78, 0x1f79, 0x0080),
                    deltaPositive(0x1f7a, 0x1f7b, 0x0070),
                    deltaPositive(0x1f7c, 0x1f7d, 0x007e),
                    deltaPositive(0x1f80, 0x1f87, 0x0008),
                    deltaNegative(0x1f88, 0x1f8f, 0x0008),
                    deltaPositive(0x1f90, 0x1f97, 0x0008),
                    deltaNegative(0x1f98, 0x1f9f, 0x0008),
                    deltaPositive(0x1fa0, 0x1fa7, 0x0008),
                    deltaNegative(0x1fa8, 0x1faf, 0x0008),
                    deltaPositive(0x1fb0, 0x1fb1, 0x0008),
                    deltaPositive(0x1fb3, 0x1fb3, 0x0009),
                    deltaNegative(0x1fb8, 0x1fb9, 0x0008),
                    deltaNegative(0x1fba, 0x1fbb, 0x004a),
                    deltaNegative(0x1fbc, 0x1fbc, 0x0009),
                    directMapping(0x1fbe, 0x1fbe, 0x0005),
                    deltaPositive(0x1fc3, 0x1fc3, 0x0009),
                    deltaNegative(0x1fc8, 0x1fcb, 0x0056),
                    deltaNegative(0x1fcc, 0x1fcc, 0x0009),
                    deltaPositive(0x1fd0, 0x1fd1, 0x0008),
                    deltaNegative(0x1fd8, 0x1fd9, 0x0008),
                    deltaNegative(0x1fda, 0x1fdb, 0x0064),
                    deltaPositive(0x1fe0, 0x1fe1, 0x0008),
                    deltaPositive(0x1fe5, 0x1fe5, 0x0007),
                    deltaNegative(0x1fe8, 0x1fe9, 0x0008),
                    deltaNegative(0x1fea, 0x1feb, 0x0070),
                    deltaNegative(0x1fec, 0x1fec, 0x0007),
                    deltaPositive(0x1ff3, 0x1ff3, 0x0009),
                    deltaNegative(0x1ff8, 0x1ff9, 0x0080),
                    deltaNegative(0x1ffa, 0x1ffb, 0x007e),
                    deltaNegative(0x1ffc, 0x1ffc, 0x0009),
                    deltaPositive(0x2132, 0x2132, 0x001c),
                    deltaNegative(0x214e, 0x214e, 0x001c),
                    deltaPositive(0x2160, 0x216f, 0x0010),
                    deltaNegative(0x2170, 0x217f, 0x0010),
                    alternatingUL(0x2183, 0x2184),
                    deltaPositive(0x24b6, 0x24cf, 0x001a),
                    deltaNegative(0x24d0, 0x24e9, 0x001a),
                    deltaPositive(0x2c00, 0x2c2e, 0x0030),
                    deltaNegative(0x2c30, 0x2c5e, 0x0030),
                    alternatingAL(0x2c60, 0x2c61),
                    deltaNegative(0x2c62, 0x2c62, 0x29f7),
                    deltaNegative(0x2c63, 0x2c63, 0x0ee6),
                    deltaNegative(0x2c64, 0x2c64, 0x29e7),
                    deltaNegative(0x2c65, 0x2c65, 0x2a2b),
                    deltaNegative(0x2c66, 0x2c66, 0x2a28),
                    alternatingUL(0x2c67, 0x2c6c),
                    deltaNegative(0x2c6d, 0x2c6d, 0x2a1c),
                    deltaNegative(0x2c6e, 0x2c6e, 0x29fd),
                    deltaNegative(0x2c6f, 0x2c6f, 0x2a1f),
                    deltaNegative(0x2c70, 0x2c70, 0x2a1e),
                    alternatingAL(0x2c72, 0x2c73),
                    alternatingUL(0x2c75, 0x2c76),
                    deltaNegative(0x2c7e, 0x2c7f, 0x2a3f),
                    alternatingAL(0x2c80, 0x2ce3),
                    alternatingUL(0x2ceb, 0x2cee),
                    alternatingAL(0x2cf2, 0x2cf3),
                    deltaNegative(0x2d00, 0x2d25, 0x1c60),
                    deltaNegative(0x2d27, 0x2d27, 0x1c60),
                    deltaNegative(0x2d2d, 0x2d2d, 0x1c60),
                    alternatingAL(0xa640, 0xa649),
                    directMapping(0xa64a, 0xa64b, 0x0015),
                    alternatingAL(0xa64c, 0xa66d),
                    alternatingAL(0xa680, 0xa69b),
                    alternatingAL(0xa722, 0xa72f),
                    alternatingAL(0xa732, 0xa76f),
                    alternatingUL(0xa779, 0xa77c),
                    deltaNegative(0xa77d, 0xa77d, 0x8a04),
                    alternatingAL(0xa77e, 0xa787),
                    alternatingUL(0xa78b, 0xa78c),
                    deltaNegative(0xa78d, 0xa78d, 0xa528),
                    alternatingAL(0xa790, 0xa793),
                    alternatingAL(0xa796, 0xa7a9),
                    deltaNegative(0xa7aa, 0xa7aa, 0xa544),
                    deltaNegative(0xa7ab, 0xa7ab, 0xa54f),
                    deltaNegative(0xa7ac, 0xa7ac, 0xa54b),
                    deltaNegative(0xa7ad, 0xa7ad, 0xa541),
                    deltaNegative(0xa7ae, 0xa7ae, 0xa544),
                    deltaNegative(0xa7b0, 0xa7b0, 0xa512),
                    deltaNegative(0xa7b1, 0xa7b1, 0xa52a),
                    deltaNegative(0xa7b2, 0xa7b2, 0xa515),
                    deltaPositive(0xa7b3, 0xa7b3, 0x03a0),
                    alternatingAL(0xa7b4, 0xa7b7),
                    deltaNegative(0xab53, 0xab53, 0x03a0),
                    deltaNegative(0xab70, 0xabbf, 0x97d0),
                    deltaPositive(0xff21, 0xff3a, 0x0020),
                    deltaNegative(0xff41, 0xff5a, 0x0020),
                    deltaPositive(0x10400, 0x10427, 0x0028),
                    deltaNegative(0x10428, 0x1044f, 0x0028),
                    deltaPositive(0x104b0, 0x104d3, 0x0028),
                    deltaNegative(0x104d8, 0x104fb, 0x0028),
                    deltaPositive(0x10c80, 0x10cb2, 0x0040),
                    deltaNegative(0x10cc0, 0x10cf2, 0x0040),
                    deltaPositive(0x118a0, 0x118bf, 0x0020),
                    deltaNegative(0x118c0, 0x118df, 0x0020),
                    deltaPositive(0x1e900, 0x1e921, 0x0022),
                    deltaNegative(0x1e922, 0x1e943, 0x0022)
    };

    private static final CaseFoldTableEntry[] UNICODE_TABLE_ENTRIES = new CaseFoldTableEntry[]{
                    deltaPositive(0x0041, 0x004a, 0x0020),
                    directMapping(0x004b, 0x004b, 0x0017),
                    deltaPositive(0x004c, 0x0052, 0x0020),
                    directMapping(0x0053, 0x0053, 0x0018),
                    deltaPositive(0x0054, 0x005a, 0x0020),
                    deltaNegative(0x0061, 0x006a, 0x0020),
                    directMapping(0x006b, 0x006b, 0x0017),
                    deltaNegative(0x006c, 0x0072, 0x0020),
                    directMapping(0x0073, 0x0073, 0x0018),
                    deltaNegative(0x0074, 0x007a, 0x0020),
                    directMapping(0x00b5, 0x00b5, 0x0000),
                    deltaPositive(0x00c0, 0x00c4, 0x0020),
                    directMapping(0x00c5, 0x00c5, 0x0019),
                    deltaPositive(0x00c6, 0x00d6, 0x0020),
                    deltaPositive(0x00d8, 0x00de, 0x0020),
                    deltaPositive(0x00df, 0x00df, 0x1dbf),
                    deltaNegative(0x00e0, 0x00e4, 0x0020),
                    directMapping(0x00e5, 0x00e5, 0x0019),
                    deltaNegative(0x00e6, 0x00f6, 0x0020),
                    deltaNegative(0x00f8, 0x00fe, 0x0020),
                    deltaPositive(0x00ff, 0x00ff, 0x0079),
                    alternatingAL(0x0100, 0x012f),
                    alternatingAL(0x0132, 0x0137),
                    alternatingUL(0x0139, 0x0148),
                    alternatingAL(0x014a, 0x0177),
                    deltaNegative(0x0178, 0x0178, 0x0079),
                    alternatingUL(0x0179, 0x017e),
                    directMapping(0x017f, 0x017f, 0x0018),
                    deltaPositive(0x0180, 0x0180, 0x00c3),
                    deltaPositive(0x0181, 0x0181, 0x00d2),
                    alternatingAL(0x0182, 0x0185),
                    deltaPositive(0x0186, 0x0186, 0x00ce),
                    alternatingUL(0x0187, 0x0188),
                    deltaPositive(0x0189, 0x018a, 0x00cd),
                    alternatingUL(0x018b, 0x018c),
                    deltaPositive(0x018e, 0x018e, 0x004f),
                    deltaPositive(0x018f, 0x018f, 0x00ca),
                    deltaPositive(0x0190, 0x0190, 0x00cb),
                    alternatingUL(0x0191, 0x0192),
                    deltaPositive(0x0193, 0x0193, 0x00cd),
                    deltaPositive(0x0194, 0x0194, 0x00cf),
                    deltaPositive(0x0195, 0x0195, 0x0061),
                    deltaPositive(0x0196, 0x0196, 0x00d3),
                    deltaPositive(0x0197, 0x0197, 0x00d1),
                    alternatingAL(0x0198, 0x0199),
                    deltaPositive(0x019a, 0x019a, 0x00a3),
                    deltaPositive(0x019c, 0x019c, 0x00d3),
                    deltaPositive(0x019d, 0x019d, 0x00d5),
                    deltaPositive(0x019e, 0x019e, 0x0082),
                    deltaPositive(0x019f, 0x019f, 0x00d6),
                    alternatingAL(0x01a0, 0x01a5),
                    deltaPositive(0x01a6, 0x01a6, 0x00da),
                    alternatingUL(0x01a7, 0x01a8),
                    deltaPositive(0x01a9, 0x01a9, 0x00da),
                    alternatingAL(0x01ac, 0x01ad),
                    deltaPositive(0x01ae, 0x01ae, 0x00da),
                    alternatingUL(0x01af, 0x01b0),
                    deltaPositive(0x01b1, 0x01b2, 0x00d9),
                    alternatingUL(0x01b3, 0x01b6),
                    deltaPositive(0x01b7, 0x01b7, 0x00db),
                    alternatingAL(0x01b8, 0x01b9),
                    alternatingAL(0x01bc, 0x01bd),
                    deltaPositive(0x01bf, 0x01bf, 0x0038),
                    directMapping(0x01c4, 0x01c6, 0x0001),
                    directMapping(0x01c7, 0x01c9, 0x0002),
                    directMapping(0x01ca, 0x01cc, 0x0003),
                    alternatingUL(0x01cd, 0x01dc),
                    deltaNegative(0x01dd, 0x01dd, 0x004f),
                    alternatingAL(0x01de, 0x01ef),
                    directMapping(0x01f1, 0x01f3, 0x0004),
                    alternatingAL(0x01f4, 0x01f5),
                    deltaNegative(0x01f6, 0x01f6, 0x0061),
                    deltaNegative(0x01f7, 0x01f7, 0x0038),
                    alternatingAL(0x01f8, 0x021f),
                    deltaNegative(0x0220, 0x0220, 0x0082),
                    alternatingAL(0x0222, 0x0233),
                    deltaPositive(0x023a, 0x023a, 0x2a2b),
                    alternatingUL(0x023b, 0x023c),
                    deltaNegative(0x023d, 0x023d, 0x00a3),
                    deltaPositive(0x023e, 0x023e, 0x2a28),
                    deltaPositive(0x023f, 0x0240, 0x2a3f),
                    alternatingUL(0x0241, 0x0242),
                    deltaNegative(0x0243, 0x0243, 0x00c3),
                    deltaPositive(0x0244, 0x0244, 0x0045),
                    deltaPositive(0x0245, 0x0245, 0x0047),
                    alternatingAL(0x0246, 0x024f),
                    deltaPositive(0x0250, 0x0250, 0x2a1f),
                    deltaPositive(0x0251, 0x0251, 0x2a1c),
                    deltaPositive(0x0252, 0x0252, 0x2a1e),
                    deltaNegative(0x0253, 0x0253, 0x00d2),
                    deltaNegative(0x0254, 0x0254, 0x00ce),
                    deltaNegative(0x0256, 0x0257, 0x00cd),
                    deltaNegative(0x0259, 0x0259, 0x00ca),
                    deltaNegative(0x025b, 0x025b, 0x00cb),
                    deltaPositive(0x025c, 0x025c, 0xa54f),
                    deltaNegative(0x0260, 0x0260, 0x00cd),
                    deltaPositive(0x0261, 0x0261, 0xa54b),
                    deltaNegative(0x0263, 0x0263, 0x00cf),
                    deltaPositive(0x0265, 0x0265, 0xa528),
                    deltaPositive(0x0266, 0x0266, 0xa544),
                    deltaNegative(0x0268, 0x0268, 0x00d1),
                    deltaNegative(0x0269, 0x0269, 0x00d3),
                    deltaPositive(0x026a, 0x026a, 0xa544),
                    deltaPositive(0x026b, 0x026b, 0x29f7),
                    deltaPositive(0x026c, 0x026c, 0xa541),
                    deltaNegative(0x026f, 0x026f, 0x00d3),
                    deltaPositive(0x0271, 0x0271, 0x29fd),
                    deltaNegative(0x0272, 0x0272, 0x00d5),
                    deltaNegative(0x0275, 0x0275, 0x00d6),
                    deltaPositive(0x027d, 0x027d, 0x29e7),
                    deltaNegative(0x0280, 0x0280, 0x00da),
                    deltaNegative(0x0283, 0x0283, 0x00da),
                    deltaPositive(0x0287, 0x0287, 0xa52a),
                    deltaNegative(0x0288, 0x0288, 0x00da),
                    deltaNegative(0x0289, 0x0289, 0x0045),
                    deltaNegative(0x028a, 0x028b, 0x00d9),
                    deltaNegative(0x028c, 0x028c, 0x0047),
                    deltaNegative(0x0292, 0x0292, 0x00db),
                    deltaPositive(0x029d, 0x029d, 0xa515),
                    deltaPositive(0x029e, 0x029e, 0xa512),
                    directMapping(0x0345, 0x0345, 0x0005),
                    alternatingAL(0x0370, 0x0373),
                    alternatingAL(0x0376, 0x0377),
                    deltaPositive(0x037b, 0x037d, 0x0082),
                    deltaPositive(0x037f, 0x037f, 0x0074),
                    deltaPositive(0x0386, 0x0386, 0x0026),
                    deltaPositive(0x0388, 0x038a, 0x0025),
                    deltaPositive(0x038c, 0x038c, 0x0040),
                    deltaPositive(0x038e, 0x038f, 0x003f),
                    deltaPositive(0x0391, 0x0391, 0x0020),
                    directMapping(0x0392, 0x0392, 0x0006),
                    deltaPositive(0x0393, 0x0394, 0x0020),
                    directMapping(0x0395, 0x0395, 0x0007),
                    deltaPositive(0x0396, 0x0397, 0x0020),
                    directMapping(0x0398, 0x0398, 0x001a),
                    directMapping(0x0399, 0x0399, 0x0005),
                    directMapping(0x039a, 0x039a, 0x0009),
                    deltaPositive(0x039b, 0x039b, 0x0020),
                    directMapping(0x039c, 0x039c, 0x0000),
                    deltaPositive(0x039d, 0x039f, 0x0020),
                    directMapping(0x03a0, 0x03a0, 0x000a),
                    directMapping(0x03a1, 0x03a1, 0x000b),
                    directMapping(0x03a3, 0x03a3, 0x000c),
                    deltaPositive(0x03a4, 0x03a5, 0x0020),
                    directMapping(0x03a6, 0x03a6, 0x000d),
                    deltaPositive(0x03a7, 0x03a8, 0x0020),
                    directMapping(0x03a9, 0x03a9, 0x001b),
                    deltaPositive(0x03aa, 0x03ab, 0x0020),
                    deltaNegative(0x03ac, 0x03ac, 0x0026),
                    deltaNegative(0x03ad, 0x03af, 0x0025),
                    deltaNegative(0x03b1, 0x03b1, 0x0020),
                    directMapping(0x03b2, 0x03b2, 0x0006),
                    deltaNegative(0x03b3, 0x03b4, 0x0020),
                    directMapping(0x03b5, 0x03b5, 0x0007),
                    deltaNegative(0x03b6, 0x03b7, 0x0020),
                    directMapping(0x03b8, 0x03b8, 0x001a),
                    directMapping(0x03b9, 0x03b9, 0x0005),
                    directMapping(0x03ba, 0x03ba, 0x0009),
                    deltaNegative(0x03bb, 0x03bb, 0x0020),
                    directMapping(0x03bc, 0x03bc, 0x0000),
                    deltaNegative(0x03bd, 0x03bf, 0x0020),
                    directMapping(0x03c0, 0x03c0, 0x000a),
                    directMapping(0x03c1, 0x03c1, 0x000b),
                    directMapping(0x03c2, 0x03c3, 0x000c),
                    deltaNegative(0x03c4, 0x03c5, 0x0020),
                    directMapping(0x03c6, 0x03c6, 0x000d),
                    deltaNegative(0x03c7, 0x03c8, 0x0020),
                    directMapping(0x03c9, 0x03c9, 0x001b),
                    deltaNegative(0x03ca, 0x03cb, 0x0020),
                    deltaNegative(0x03cc, 0x03cc, 0x0040),
                    deltaNegative(0x03cd, 0x03ce, 0x003f),
                    deltaPositive(0x03cf, 0x03cf, 0x0008),
                    directMapping(0x03d0, 0x03d0, 0x0006),
                    directMapping(0x03d1, 0x03d1, 0x001a),
                    directMapping(0x03d5, 0x03d5, 0x000d),
                    directMapping(0x03d6, 0x03d6, 0x000a),
                    deltaNegative(0x03d7, 0x03d7, 0x0008),
                    alternatingAL(0x03d8, 0x03ef),
                    directMapping(0x03f0, 0x03f0, 0x0009),
                    directMapping(0x03f1, 0x03f1, 0x000b),
                    deltaPositive(0x03f2, 0x03f2, 0x0007),
                    deltaNegative(0x03f3, 0x03f3, 0x0074),
                    directMapping(0x03f4, 0x03f4, 0x001a),
                    directMapping(0x03f5, 0x03f5, 0x0007),
                    alternatingUL(0x03f7, 0x03f8),
                    deltaNegative(0x03f9, 0x03f9, 0x0007),
                    alternatingAL(0x03fa, 0x03fb),
                    deltaNegative(0x03fd, 0x03ff, 0x0082),
                    deltaPositive(0x0400, 0x040f, 0x0050),
                    deltaPositive(0x0410, 0x0411, 0x0020),
                    directMapping(0x0412, 0x0412, 0x000e),
                    deltaPositive(0x0413, 0x0413, 0x0020),
                    directMapping(0x0414, 0x0414, 0x000f),
                    deltaPositive(0x0415, 0x041d, 0x0020),
                    directMapping(0x041e, 0x041e, 0x0010),
                    deltaPositive(0x041f, 0x0420, 0x0020),
                    directMapping(0x0421, 0x0421, 0x0011),
                    directMapping(0x0422, 0x0422, 0x0012),
                    deltaPositive(0x0423, 0x0429, 0x0020),
                    directMapping(0x042a, 0x042a, 0x0013),
                    deltaPositive(0x042b, 0x042f, 0x0020),
                    deltaNegative(0x0430, 0x0431, 0x0020),
                    directMapping(0x0432, 0x0432, 0x000e),
                    deltaNegative(0x0433, 0x0433, 0x0020),
                    directMapping(0x0434, 0x0434, 0x000f),
                    deltaNegative(0x0435, 0x043d, 0x0020),
                    directMapping(0x043e, 0x043e, 0x0010),
                    deltaNegative(0x043f, 0x0440, 0x0020),
                    directMapping(0x0441, 0x0441, 0x0011),
                    directMapping(0x0442, 0x0442, 0x0012),
                    deltaNegative(0x0443, 0x0449, 0x0020),
                    directMapping(0x044a, 0x044a, 0x0013),
                    deltaNegative(0x044b, 0x044f, 0x0020),
                    deltaNegative(0x0450, 0x045f, 0x0050),
                    alternatingAL(0x0460, 0x0461),
                    directMapping(0x0462, 0x0463, 0x0014),
                    alternatingAL(0x0464, 0x0481),
                    alternatingAL(0x048a, 0x04bf),
                    deltaPositive(0x04c0, 0x04c0, 0x000f),
                    alternatingUL(0x04c1, 0x04ce),
                    deltaNegative(0x04cf, 0x04cf, 0x000f),
                    alternatingAL(0x04d0, 0x052f),
                    deltaPositive(0x0531, 0x0556, 0x0030),
                    deltaNegative(0x0561, 0x0586, 0x0030),
                    deltaPositive(0x10a0, 0x10c5, 0x1c60),
                    deltaPositive(0x10c7, 0x10c7, 0x1c60),
                    deltaPositive(0x10cd, 0x10cd, 0x1c60),
                    deltaPositive(0x13a0, 0x13ef, 0x97d0),
                    deltaPositive(0x13f0, 0x13f5, 0x0008),
                    deltaNegative(0x13f8, 0x13fd, 0x0008),
                    directMapping(0x1c80, 0x1c80, 0x000e),
                    directMapping(0x1c81, 0x1c81, 0x000f),
                    directMapping(0x1c82, 0x1c82, 0x0010),
                    directMapping(0x1c83, 0x1c83, 0x0011),
                    directMapping(0x1c84, 0x1c85, 0x0012),
                    directMapping(0x1c86, 0x1c86, 0x0013),
                    directMapping(0x1c87, 0x1c87, 0x0014),
                    directMapping(0x1c88, 0x1c88, 0x0015),
                    deltaPositive(0x1d79, 0x1d79, 0x8a04),
                    deltaPositive(0x1d7d, 0x1d7d, 0x0ee6),
                    alternatingAL(0x1e00, 0x1e5f),
                    directMapping(0x1e60, 0x1e61, 0x0016),
                    alternatingAL(0x1e62, 0x1e95),
                    directMapping(0x1e9b, 0x1e9b, 0x0016),
                    deltaNegative(0x1e9e, 0x1e9e, 0x1dbf),
                    alternatingAL(0x1ea0, 0x1eff),
                    deltaPositive(0x1f00, 0x1f07, 0x0008),
                    deltaNegative(0x1f08, 0x1f0f, 0x0008),
                    deltaPositive(0x1f10, 0x1f15, 0x0008),
                    deltaNegative(0x1f18, 0x1f1d, 0x0008),
                    deltaPositive(0x1f20, 0x1f27, 0x0008),
                    deltaNegative(0x1f28, 0x1f2f, 0x0008),
                    deltaPositive(0x1f30, 0x1f37, 0x0008),
                    deltaNegative(0x1f38, 0x1f3f, 0x0008),
                    deltaPositive(0x1f40, 0x1f45, 0x0008),
                    deltaNegative(0x1f48, 0x1f4d, 0x0008),
                    deltaPositive(0x1f51, 0x1f51, 0x0008),
                    deltaPositive(0x1f53, 0x1f53, 0x0008),
                    deltaPositive(0x1f55, 0x1f55, 0x0008),
                    deltaPositive(0x1f57, 0x1f57, 0x0008),
                    deltaNegative(0x1f59, 0x1f59, 0x0008),
                    deltaNegative(0x1f5b, 0x1f5b, 0x0008),
                    deltaNegative(0x1f5d, 0x1f5d, 0x0008),
                    deltaNegative(0x1f5f, 0x1f5f, 0x0008),
                    deltaPositive(0x1f60, 0x1f67, 0x0008),
                    deltaNegative(0x1f68, 0x1f6f, 0x0008),
                    deltaPositive(0x1f70, 0x1f71, 0x004a),
                    deltaPositive(0x1f72, 0x1f75, 0x0056),
                    deltaPositive(0x1f76, 0x1f77, 0x0064),
                    deltaPositive(0x1f78, 0x1f79, 0x0080),
                    deltaPositive(0x1f7a, 0x1f7b, 0x0070),
                    deltaPositive(0x1f7c, 0x1f7d, 0x007e),
                    deltaPositive(0x1f80, 0x1f87, 0x0008),
                    deltaNegative(0x1f88, 0x1f8f, 0x0008),
                    deltaPositive(0x1f90, 0x1f97, 0x0008),
                    deltaNegative(0x1f98, 0x1f9f, 0x0008),
                    deltaPositive(0x1fa0, 0x1fa7, 0x0008),
                    deltaNegative(0x1fa8, 0x1faf, 0x0008),
                    deltaPositive(0x1fb0, 0x1fb1, 0x0008),
                    deltaPositive(0x1fb3, 0x1fb3, 0x0009),
                    deltaNegative(0x1fb8, 0x1fb9, 0x0008),
                    deltaNegative(0x1fba, 0x1fbb, 0x004a),
                    deltaNegative(0x1fbc, 0x1fbc, 0x0009),
                    directMapping(0x1fbe, 0x1fbe, 0x0005),
                    deltaPositive(0x1fc3, 0x1fc3, 0x0009),
                    deltaNegative(0x1fc8, 0x1fcb, 0x0056),
                    deltaNegative(0x1fcc, 0x1fcc, 0x0009),
                    deltaPositive(0x1fd0, 0x1fd1, 0x0008),
                    deltaNegative(0x1fd8, 0x1fd9, 0x0008),
                    deltaNegative(0x1fda, 0x1fdb, 0x0064),
                    deltaPositive(0x1fe0, 0x1fe1, 0x0008),
                    deltaPositive(0x1fe5, 0x1fe5, 0x0007),
                    deltaNegative(0x1fe8, 0x1fe9, 0x0008),
                    deltaNegative(0x1fea, 0x1feb, 0x0070),
                    deltaNegative(0x1fec, 0x1fec, 0x0007),
                    deltaPositive(0x1ff3, 0x1ff3, 0x0009),
                    deltaNegative(0x1ff8, 0x1ff9, 0x0080),
                    deltaNegative(0x1ffa, 0x1ffb, 0x007e),
                    deltaNegative(0x1ffc, 0x1ffc, 0x0009),
                    directMapping(0x2126, 0x2126, 0x001b),
                    directMapping(0x212a, 0x212a, 0x0017),
                    directMapping(0x212b, 0x212b, 0x0019),
                    deltaPositive(0x2132, 0x2132, 0x001c),
                    deltaNegative(0x214e, 0x214e, 0x001c),
                    deltaPositive(0x2160, 0x216f, 0x0010),
                    deltaNegative(0x2170, 0x217f, 0x0010),
                    alternatingUL(0x2183, 0x2184),
                    deltaPositive(0x24b6, 0x24cf, 0x001a),
                    deltaNegative(0x24d0, 0x24e9, 0x001a),
                    deltaPositive(0x2c00, 0x2c2e, 0x0030),
                    deltaNegative(0x2c30, 0x2c5e, 0x0030),
                    alternatingAL(0x2c60, 0x2c61),
                    deltaNegative(0x2c62, 0x2c62, 0x29f7),
                    deltaNegative(0x2c63, 0x2c63, 0x0ee6),
                    deltaNegative(0x2c64, 0x2c64, 0x29e7),
                    deltaNegative(0x2c65, 0x2c65, 0x2a2b),
                    deltaNegative(0x2c66, 0x2c66, 0x2a28),
                    alternatingUL(0x2c67, 0x2c6c),
                    deltaNegative(0x2c6d, 0x2c6d, 0x2a1c),
                    deltaNegative(0x2c6e, 0x2c6e, 0x29fd),
                    deltaNegative(0x2c6f, 0x2c6f, 0x2a1f),
                    deltaNegative(0x2c70, 0x2c70, 0x2a1e),
                    alternatingAL(0x2c72, 0x2c73),
                    alternatingUL(0x2c75, 0x2c76),
                    deltaNegative(0x2c7e, 0x2c7f, 0x2a3f),
                    alternatingAL(0x2c80, 0x2ce3),
                    alternatingUL(0x2ceb, 0x2cee),
                    alternatingAL(0x2cf2, 0x2cf3),
                    deltaNegative(0x2d00, 0x2d25, 0x1c60),
                    deltaNegative(0x2d27, 0x2d27, 0x1c60),
                    deltaNegative(0x2d2d, 0x2d2d, 0x1c60),
                    alternatingAL(0xa640, 0xa649),
                    directMapping(0xa64a, 0xa64b, 0x0015),
                    alternatingAL(0xa64c, 0xa66d),
                    alternatingAL(0xa680, 0xa69b),
                    alternatingAL(0xa722, 0xa72f),
                    alternatingAL(0xa732, 0xa76f),
                    alternatingUL(0xa779, 0xa77c),
                    deltaNegative(0xa77d, 0xa77d, 0x8a04),
                    alternatingAL(0xa77e, 0xa787),
                    alternatingUL(0xa78b, 0xa78c),
                    deltaNegative(0xa78d, 0xa78d, 0xa528),
                    alternatingAL(0xa790, 0xa793),
                    alternatingAL(0xa796, 0xa7a9),
                    deltaNegative(0xa7aa, 0xa7aa, 0xa544),
                    deltaNegative(0xa7ab, 0xa7ab, 0xa54f),
                    deltaNegative(0xa7ac, 0xa7ac, 0xa54b),
                    deltaNegative(0xa7ad, 0xa7ad, 0xa541),
                    deltaNegative(0xa7ae, 0xa7ae, 0xa544),
                    deltaNegative(0xa7b0, 0xa7b0, 0xa512),
                    deltaNegative(0xa7b1, 0xa7b1, 0xa52a),
                    deltaNegative(0xa7b2, 0xa7b2, 0xa515),
                    deltaPositive(0xa7b3, 0xa7b3, 0x03a0),
                    alternatingAL(0xa7b4, 0xa7b7),
                    deltaNegative(0xab53, 0xab53, 0x03a0),
                    deltaNegative(0xab70, 0xabbf, 0x97d0),
                    deltaPositive(0xff21, 0xff3a, 0x0020),
                    deltaNegative(0xff41, 0xff5a, 0x0020),
                    deltaPositive(0x10400, 0x10427, 0x0028),
                    deltaNegative(0x10428, 0x1044f, 0x0028),
                    deltaPositive(0x104b0, 0x104d3, 0x0028),
                    deltaNegative(0x104d8, 0x104fb, 0x0028),
                    deltaPositive(0x10c80, 0x10cb2, 0x0040),
                    deltaNegative(0x10cc0, 0x10cf2, 0x0040),
                    deltaPositive(0x118a0, 0x118bf, 0x0020),
                    deltaNegative(0x118c0, 0x118df, 0x0020),
                    deltaPositive(0x1e900, 0x1e921, 0x0022),
                    deltaNegative(0x1e922, 0x1e943, 0x0022)
    };

    static {
        assert CodePointRange.rangesAreSortedAndDisjoint(NON_UNICODE_TABLE_ENTRIES);
        assert CodePointRange.rangesAreSortedAndDisjoint(UNICODE_TABLE_ENTRIES);
    }
}
