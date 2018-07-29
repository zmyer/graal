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
package com.oracle.svm.core.jdk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.util.VMError;

/**
 * Support for resources on Substrate VM. All resources that need to be available at run time need
 * to be added explicitly during native image generation using {@link #registerResource}.
 *
 * Registered resources are then available from {@link DynamicHub#getResource classes} and
 * {@link Target_java_lang_ClassLoader class loaders}.
 */
public final class Resources {

    static class ResourcesSupport {
        final Map<String, List<byte[]>> resources = new HashMap<>();
    }

    private Resources() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void registerResource(String name, InputStream is) {
        ResourcesSupport support;
        if (ImageSingletons.contains(ResourcesSupport.class)) {
            support = ImageSingletons.lookup(ResourcesSupport.class);
        } else {
            support = new ResourcesSupport();
            ImageSingletons.add(ResourcesSupport.class, support);
        }

        byte[] arr = new byte[4096];
        int pos = 0;
        try {
            for (;;) {
                if (pos == arr.length) {
                    byte[] tmp = new byte[arr.length * 2];
                    System.arraycopy(arr, 0, tmp, 0, arr.length);
                    arr = tmp;
                }
                int len = is.read(arr, pos, arr.length - pos);
                if (len == -1) {
                    break;
                }
                pos += len;
            }
        } catch (IOException ex) {
            throw VMError.shouldNotReachHere(ex);
        }

        byte[] res = new byte[pos];
        System.arraycopy(arr, 0, res, 0, pos);

        List<byte[]> list = support.resources.get(name);
        if (list == null) {
            list = new ArrayList<>();
            support.resources.put(name, list);
        }
        list.add(res);
    }

    public static List<byte[]> get(String name) {
        if (!ImageSingletons.contains(ResourcesSupport.class)) {
            /*
             * No resources have been registered (registerResource was not called at all during
             * image generation), so there cannot be a match. This check is constant folded, all
             * methods in VMConfiguration are annotated with @Fold.
             */
            return null;
        }
        return ImageSingletons.lookup(ResourcesSupport.class).resources.get(name);
    }

    public static URL createURL(String name, byte[] resourceBytes) {
        class Conn extends URLConnection {
            Conn(URL url) {
                super(url);
            }

            @Override
            public void connect() throws IOException {
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(resourceBytes);
            }
        }

        try {
            return new URL("resource", null, -1, name, new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) throws IOException {
                    return new Conn(u);
                }
            });
        } catch (MalformedURLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
