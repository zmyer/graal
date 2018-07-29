/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.hosted.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Function;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.nativeimage.Platform;

import com.oracle.objectfile.ObjectFile;
import com.oracle.svm.core.LinkerInvocation;
import com.oracle.svm.core.OS;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl.BeforeImageWriteAccessImpl;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.hosted.c.NativeLibraries;
import com.oracle.svm.hosted.c.util.FileUtils;
import com.oracle.svm.hosted.meta.HostedMetaAccess;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

public abstract class NativeBootImageViaCC extends NativeBootImage {

    public NativeBootImageViaCC(NativeImageKind k, HostedUniverse universe, HostedMetaAccess metaAccess, NativeLibraries nativeLibs, NativeImageHeap heap, NativeImageCodeCache codeCache,
                    List<HostedMethod> entryPoints, HostedMethod mainEntryPoint, ClassLoader imageClassLoader) {
        super(k, universe, metaAccess, nativeLibs, heap, codeCache, entryPoints, mainEntryPoint, imageClassLoader);
    }

    public NativeImageKind getOutputKind() {
        return kind;
    }

    class BinutilsCCLinkerInvocation extends CCLinkerInvocation {

        @Override
        protected void addOneSymbolAliasOption(List<String> cmd, Entry<String, String> ent) {
            cmd.add("-Wl,--defsym");
            cmd.add("-Wl," + ent.getKey() + "=" + ent.getValue());
        }

        @Override
        protected void setOutputKind(List<String> cmd) {
            switch (kind) {
                case EXECUTABLE:
                    break;
                case STATIC_EXECUTABLE:
                    cmd.add("-static");
                    break;
                case SHARED_LIBRARY:
                    cmd.add("-shared");
                    break;
                default:
                    VMError.shouldNotReachHere();
            }
        }

    }

    class DarwinCCLinkerInvocation extends CCLinkerInvocation {

        @Override
        protected void addOneSymbolAliasOption(List<String> cmd, Entry<String, String> ent) {
            cmd.add("-Wl,-alias," + ent.getValue() + "," + ent.getKey());
        }

        @Override
        protected void setOutputKind(List<String> cmd) {
            switch (kind) {
                case STATIC_EXECUTABLE:
                    throw UserError.abort(OS.getCurrent().name() + " does not support building static executable images.");
                case SHARED_LIBRARY:
                    cmd.add("-shared");
                    if (Platform.includedIn(Platform.DARWIN.class)) {
                        cmd.add("-undefined");
                        cmd.add("dynamic_lookup");
                    }
                    break;
            }
        }
    }

    class WindowsCCLinkerInvocation extends CCLinkerInvocation {

        WindowsCCLinkerInvocation() {
            setCompilerCommand("CL");
        }

        @Override
        protected void addOneSymbolAliasOption(List<String> cmd, Entry<String, String> ent) {
            // cmd.add("-Wl,-alias," + ent.getValue() + "," + ent.getKey());
        }

        @Override
        protected void setOutputKind(List<String> cmd) {
            switch (kind) {
                case EXECUTABLE:
                case STATIC_EXECUTABLE:
                    cmd.add("/MT");
                    break;
                case SHARED_LIBRARY:
                    cmd.add("/MD");
                    break;
                default:
                    VMError.shouldNotReachHere();
            }
        }

        @Override
        public List<String> getCommand() {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(compilerCommand);

            // Add debugging info
            cmd.add("/Zi");

            cmd.add("/Fe" + outputFile.toString());

            cmd.addAll(inputFilenames);

            // We could add a .drectve section instead of doing this
            cmd.add("/link /DEFAULTLIB:LIBCMT /DEFAULTLIB:OLDNAMES");
            return cmd;
        }
    }

    LinkerInvocation getLinkerInvocation(Path outputDirectory, Path tempDirectory, String imageName) {
        String relocatableFileName = tempDirectory.resolve(imageName + ObjectFile.getFilenameSuffix()).toString();

        CCLinkerInvocation inv;

        switch (ObjectFile.getNativeFormat()) {
            case MACH_O:
                inv = new DarwinCCLinkerInvocation();
                break;
            case PECOFF:
                inv = new WindowsCCLinkerInvocation();
                break;
            case ELF:
            default:
                inv = new BinutilsCCLinkerInvocation();
                break;
        }

        Path outputFile = outputDirectory.resolve(imageName + getBootImageKind().getFilenameSuffix());
        UserError.guarantee(!Files.isDirectory(outputFile), "Cannot write image to %s. Path exists as directory. (Use -H:Name=<image name>)", outputFile);
        inv.setOutputFile(outputFile);
        inv.setOutputKind(getOutputKind());

        inv.addLibPath(tempDirectory.toString());
        for (String libraryPath : nativeLibs.getLibraryPaths()) {
            inv.addLibPath(libraryPath);
        }
        for (String rPath : SubstrateOptions.LinkerRPath.getValue().split(",")) {
            inv.addRPath(rPath);
        }

        for (String library : nativeLibs.getLibraries()) {
            inv.addLinkedLibrary(library);
        }

        inv.addInputFile(relocatableFileName);

        return inv;
    }

    @Override
    @SuppressWarnings("try")
    public Path write(DebugContext debug, Path outputDirectory, Path tempDirectory, String imageName, BeforeImageWriteAccessImpl config) {
        String cmdstr = "";
        String outputstr = "";
        try (Indent indent = debug.logAndIndent("Writing native image")) {
            // 1. write the relocatable file

            // Since we're using FileChannel.map, and we can't unmap the file,
            // we have to copy the file or the linker will fail to open it.
            if (OS.getCurrent() == OS.WINDOWS) {
                Path tempFile = tempDirectory.resolve(imageName + ".tmp");
                write(tempFile);
                try {
                    Files.copy(tempFile, tempDirectory.resolve(imageName + ObjectFile.getFilenameSuffix()));
                    // Files.delete(tempFile);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create Object file " + e);
                }
            } else {
                write(tempDirectory.resolve(imageName + ObjectFile.getFilenameSuffix()));
            }
            // 2. run a command to make an executable of it
            int status;
            try {
                /*
                 * To support automated stub generation, we first search for a libsvm.a in the
                 * images directory. FIXME: make this a per-image directory, to avoid clobbering on
                 * multiple runs. It actually doesn't matter, because it's a .a file which will get
                 * absorbed into the executable, but avoiding clobbering will help debugging.
                 */

                LinkerInvocation inv = getLinkerInvocation(outputDirectory, tempDirectory, imageName);
                for (Function<LinkerInvocation, LinkerInvocation> fn : config.getLinkerInvocationTransformers()) {
                    inv = fn.apply(inv);
                }
                List<String> cmd = inv.getCommand();
                StringBuilder sb = new StringBuilder("Running command:");
                for (String s : cmd) {
                    sb.append(' ');
                    sb.append(s);
                }
                cmdstr = sb.toString();
                try (DebugContext.Scope s = debug.scope("InvokeCC")) {
                    debug.log("%s", sb);

                    if (NativeImageOptions.MachODebugInfoTesting.getValue()) {
                        System.out.printf("Testing Mach-O debuginfo generation - SKIP %s%n", cmdstr);
                    } else {
                        ProcessBuilder pb = new ProcessBuilder().command(cmd);
                        pb.directory(tempDirectory.toFile());
                        pb.redirectErrorStream(true);
                        Process p = pb.start();

                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        FileUtils.drainInputStream(p.getInputStream(), output);
                        status = p.waitFor();

                        outputstr = output.toString();
                        debug.log("%s", output);

                        if (status != 0) {
                            throw new RuntimeException("returned " + status);
                        }
                    }
                }
                return inv.getOutputFile();
            } catch (Exception ex) {
                throw new RuntimeException("host C compiler or linker does not seem to work: " + ex.toString() + "\n\n" + cmdstr + "\n\n" + outputstr);
            }
        }
    }
}
