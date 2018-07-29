/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.regex;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.util.CompilationResult;
import com.oracle.truffle.regex.util.LRUCache;

import java.util.Collections;
import java.util.Map;

public class CachingRegexCompiler extends RegexCompiler {

    private final RegexCompiler compiler;
    private final Map<RegexSource, CompilationResult<TruffleObject>> cache = Collections.synchronizedMap(new LRUCache<>(TRegexOptions.RegexMaxCacheSize));

    public CachingRegexCompiler(TruffleObject compiler) {
        this.compiler = ForeignRegexCompiler.importRegexCompiler(compiler);
    }

    @Override
    public TruffleObject compile(RegexSource source) throws RegexSyntaxException, UnsupportedRegexException {
        CompilationResult<TruffleObject> result = cacheGet(source);
        if (result == null) {
            result = doCompile(source);
            cachePut(source, result);
        }
        return result.unpack();
    }

    private CompilationResult<TruffleObject> doCompile(RegexSource regexSource) {
        return CompilationResult.pack(() -> compiler.compile(regexSource));
    }

    @TruffleBoundary
    private CompilationResult<TruffleObject> cacheGet(RegexSource source) {
        return cache.get(source);
    }

    @TruffleBoundary
    private CompilationResult<TruffleObject> cachePut(RegexSource source, CompilationResult<TruffleObject> result) {
        return cache.put(source, result);
    }
}
