/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.serviceprovider;

import static java.lang.Thread.currentThread;
import static org.graalvm.compiler.serviceprovider.GraalServices.JMXService.jmx;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicLong;

import jdk.vm.ci.services.JVMCIPermission;
import jdk.vm.ci.services.Services;

/*
 * Note: Avoid import statements for java.management or com.sun.management to prevent
 * mx creating a jdk.internal.vm.compiler module that `requires` the java.management
 * or jdk.management modules. The GraalServices class is replaced by a multi-release jar
 * version in JDK 9 and later which does not depend these modules.
 *
 * See also org.graalvm.compiler.serviceprovider.GraalServices.LazyJMX in the
 * org.graalvm.compiler.serviceprovider.jdk9 project.
 */

/**
 * Interface to functionality that abstracts over which JDK version Graal is running on.
 */
public final class GraalServices {

    private static int getJavaSpecificationVersion() {
        String value = System.getProperty("java.specification.version");
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        return Integer.parseInt(value);
    }

    /**
     * The integer value corresponding to the value of the {@code java.specification.version} system
     * property after any leading {@code "1."} has been stripped.
     */
    public static final int JAVA_SPECIFICATION_VERSION = getJavaSpecificationVersion();

    /**
     * Determines if the Java runtime is version 8 or earlier.
     */
    public static final boolean Java8OrEarlier = JAVA_SPECIFICATION_VERSION <= 8;

    /**
     * Determines if the Java runtime is version 11 or later.
     */
    public static final boolean Java11OrLater = JAVA_SPECIFICATION_VERSION >= 11;

    private GraalServices() {
    }

    /**
     * Gets an {@link Iterable} of the providers available for a given service.
     *
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    public static <S> Iterable<S> load(Class<S> service) {
        assert !service.getName().startsWith("jdk.vm.ci") : "JVMCI services must be loaded via " + Services.class.getName();
        return Services.load(service);
    }

    /**
     * Gets the provider for a given service for which at most one provider must be available.
     *
     * @param service the service whose provider is being requested
     * @param required specifies if an {@link InternalError} should be thrown if no provider of
     *            {@code service} is available
     * @return the requested provider if available else {@code null}
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    public static <S> S loadSingle(Class<S> service, boolean required) {
        assert !service.getName().startsWith("jdk.vm.ci") : "JVMCI services must be loaded via " + Services.class.getName();
        Iterable<S> providers = load(service);
        S singleProvider = null;
        try {
            for (Iterator<S> it = providers.iterator(); it.hasNext();) {
                singleProvider = it.next();
                if (it.hasNext()) {
                    S other = it.next();
                    throw new InternalError(String.format("Multiple %s providers found: %s, %s", service.getName(), singleProvider.getClass().getName(), other.getClass().getName()));
                }
            }
        } catch (ServiceConfigurationError e) {
            // If the service is required we will bail out below.
        }
        if (singleProvider == null) {
            if (required) {
                throw new InternalError(String.format("No provider for %s found", service.getName()));
            }
        }
        return singleProvider;
    }

    /**
     * Gets the class file bytes for {@code c}.
     */
    @SuppressWarnings("unused")
    public static InputStream getClassfileAsStream(Class<?> c) throws IOException {
        String classfilePath = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        if (cl == null) {
            return ClassLoader.getSystemResourceAsStream(classfilePath);
        }
        return cl.getResourceAsStream(classfilePath);
    }

    private static final ClassLoader JVMCI_LOADER = GraalServices.class.getClassLoader();
    private static final ClassLoader JVMCI_PARENT_LOADER = JVMCI_LOADER == null ? null : JVMCI_LOADER.getParent();
    static {
        assert JVMCI_PARENT_LOADER == null || JVMCI_PARENT_LOADER.getParent() == null;
    }

    /**
     * Determines if invoking {@link Object#toString()} on an instance of {@code c} will only run
     * trusted code.
     */
    public static boolean isToStringTrusted(Class<?> c) {
        ClassLoader cl = c.getClassLoader();
        return cl == null || cl == JVMCI_LOADER || cl == JVMCI_PARENT_LOADER;
    }

    /**
     * Gets a unique identifier for this execution such as a process ID or a
     * {@linkplain #getGlobalTimeStamp() fixed timestamp}.
     */
    public static String getExecutionID() {
        try {
            String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            try {
                int index = runtimeName.indexOf('@');
                if (index != -1) {
                    long pid = Long.parseLong(runtimeName.substring(0, index));
                    return Long.toString(pid);
                }
            } catch (NumberFormatException e) {
            }
            return runtimeName;
        } catch (LinkageError err) {
            return String.valueOf(getGlobalTimeStamp());
        }
    }

    private static final AtomicLong globalTimeStamp = new AtomicLong();

    /**
     * Gets a time stamp for the current process. This method will always return the same value for
     * the current VM execution.
     */
    public static long getGlobalTimeStamp() {
        if (globalTimeStamp.get() == 0) {
            globalTimeStamp.compareAndSet(0, System.currentTimeMillis());
        }
        return globalTimeStamp.get();
    }

    /**
     * Access to thread specific information made available via Java Management Extensions (JMX).
     */
    public static class JMXService {
        // In this JDK 8 specific implementation, we can reference
        // JMX classes directly since there's no module dependencies to
        // worry about - everything is in rt.jar.
        private final com.sun.management.ThreadMXBean threadMXBean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

        protected long getThreadAllocatedBytes(long id) {
            return threadMXBean.getThreadAllocatedBytes(id);
        }

        protected long getCurrentThreadCpuTime() {
            return threadMXBean.getCurrentThreadCpuTime();
        }

        protected boolean isThreadAllocatedMemorySupported() {
            return threadMXBean.isThreadAllocatedMemorySupported();
        }

        protected boolean isCurrentThreadCpuTimeSupported() {
            return threadMXBean.isCurrentThreadCpuTimeSupported();
        }

        protected List<String> getInputArguments() {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        }

        // Placing this static field in JMXService (instead of GraalServices)
        // allows for lazy initialization.
        static final JMXService jmx = new JMXService();

    }

    /**
     * Returns an approximation of the total amount of memory, in bytes, allocated in heap memory
     * for the thread of the specified ID. The returned value is an approximation because some Java
     * virtual machine implementations may use object allocation mechanisms that result in a delay
     * between the time an object is allocated and the time its size is recorded.
     * <p>
     * If the thread of the specified ID is not alive or does not exist, this method returns
     * {@code -1}. If thread memory allocation measurement is disabled, this method returns
     * {@code -1}. A thread is alive if it has been started and has not yet died.
     * <p>
     * If thread memory allocation measurement is enabled after the thread has started, the Java
     * virtual machine implementation may choose any time up to and including the time that the
     * capability is enabled as the point where thread memory allocation measurement starts.
     *
     * @param id the thread ID of a thread
     * @return an approximation of the total memory allocated, in bytes, in heap memory for a thread
     *         of the specified ID if the thread of the specified ID exists, the thread is alive,
     *         and thread memory allocation measurement is enabled; {@code -1} otherwise.
     *
     * @throws IllegalArgumentException if {@code id} {@code <=} {@code 0}.
     * @throws UnsupportedOperationException if the Java virtual machine implementation does not
     *             {@linkplain #isThreadAllocatedMemorySupported() support} thread memory allocation
     *             measurement.
     */
    public static long getThreadAllocatedBytes(long id) {
        return jmx.getThreadAllocatedBytes(id);
    }

    /**
     * Convenience method for calling {@link #getThreadAllocatedBytes(long)} with the id of the
     * current thread.
     */
    public static long getCurrentThreadAllocatedBytes() {
        return getThreadAllocatedBytes(currentThread().getId());
    }

    /**
     * Returns the total CPU time for the current thread in nanoseconds. The returned value is of
     * nanoseconds precision but not necessarily nanoseconds accuracy. If the implementation
     * distinguishes between user mode time and system mode time, the returned CPU time is the
     * amount of time that the current thread has executed in user mode or system mode.
     *
     * @return the total CPU time for the current thread if CPU time measurement is enabled;
     *         {@code -1} otherwise.
     *
     * @throws UnsupportedOperationException if the Java virtual machine does not
     *             {@linkplain #isCurrentThreadCpuTimeSupported() support} CPU time measurement for
     *             the current thread
     */
    public static long getCurrentThreadCpuTime() {
        return jmx.getCurrentThreadCpuTime();
    }

    /**
     * Determines if the Java virtual machine implementation supports thread memory allocation
     * measurement.
     */
    public static boolean isThreadAllocatedMemorySupported() {
        return jmx.isThreadAllocatedMemorySupported();
    }

    /**
     * Determines if the Java virtual machine supports CPU time measurement for the current thread.
     */
    public static boolean isCurrentThreadCpuTimeSupported() {
        return jmx.isCurrentThreadCpuTimeSupported();
    }

    /**
     * Gets the input arguments passed to the Java virtual machine which does not include the
     * arguments to the {@code main} method. This method returns an empty list if there is no input
     * argument to the Java virtual machine.
     * <p>
     * Some Java virtual machine implementations may take input arguments from multiple different
     * sources: for examples, arguments passed from the application that launches the Java virtual
     * machine such as the 'java' command, environment variables, configuration files, etc.
     * <p>
     * Typically, not all command-line options to the 'java' command are passed to the Java virtual
     * machine. Thus, the returned input arguments may not include all command-line options.
     *
     * @return the input arguments to the JVM or {@code null} if they are unavailable
     */
    public static List<String> getInputArguments() {
        return jmx.getInputArguments();
    }
}
