/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.posix.darwin;

import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.jdk.SystemPropertiesSupport;
import com.oracle.svm.core.posix.PosixSystemPropertiesSupport;
import com.oracle.svm.core.posix.headers.Limits;
import com.oracle.svm.core.posix.headers.Unistd;
import com.oracle.svm.core.posix.headers.darwin.CoreFoundation;

@Platforms({Platform.DARWIN.class})
public class DarwinSystemPropertiesSupport extends PosixSystemPropertiesSupport {

    @Override
    protected String tmpdirValue() {
        /* Darwin has a per-user temp dir */
        int buflen = Limits.PATH_MAX();
        CCharPointer tmpPath = StackValue.get(buflen);
        UnsignedWord pathSize = Unistd.confstr(Unistd._CS_DARWIN_USER_TEMP_DIR(), tmpPath, WordFactory.unsigned(buflen));
        if (pathSize.aboveThan(0) && pathSize.belowOrEqual(buflen)) {
            return CTypeConversion.toJavaString(tmpPath);
        } else {
            /*
             * Default as defined in JDK source/jdk/src/solaris/native/java/lang/java_props_md.c
             * line 135.
             */
            return "/var/tmp";
        }
    }

    private static volatile String osVersionValue = null;

    @Override
    protected String osVersionValue() {
        if (osVersionValue != null) {
            return osVersionValue;
        }

        /* On OSX Java returns the ProductVersion instead of kernel release info. */
        CoreFoundation.CFDictionaryRef dict = CoreFoundation._CFCopyServerVersionDictionary();
        if (dict.isNull()) {
            dict = CoreFoundation._CFCopySystemVersionDictionary();
        }
        if (dict.isNull()) {
            return osVersionValue = "Unknown";
        }
        CoreFoundation.CFStringRef dictKeyRef = DarwinCoreFoundationUtils.toCFStringRef("MacOSXProductVersion");
        CoreFoundation.CFStringRef dictValue = CoreFoundation.CFDictionaryGetValue(dict, dictKeyRef);
        CoreFoundation.CFRelease(dictKeyRef);
        if (dictValue.isNull()) {
            dictKeyRef = DarwinCoreFoundationUtils.toCFStringRef("ProductVersion");
            dictValue = CoreFoundation.CFDictionaryGetValue(dict, dictKeyRef);
            CoreFoundation.CFRelease(dictKeyRef);
        }
        if (dictValue.isNull()) {
            return osVersionValue = "Unknown";
        }
        osVersionValue = DarwinCoreFoundationUtils.fromCFStringRef(dictValue);
        CoreFoundation.CFRelease(dictValue);
        return osVersionValue;
    }
}

@Platforms({Platform.DARWIN.class})
@AutomaticFeature
class DarwinSystemPropertiesFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(SystemPropertiesSupport.class, new DarwinSystemPropertiesSupport());
    }
}
