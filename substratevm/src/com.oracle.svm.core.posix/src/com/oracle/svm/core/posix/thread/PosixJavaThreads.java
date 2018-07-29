/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.posix.thread;

import static com.oracle.svm.core.posix.headers.Pthread.PTHREAD_STACK_MIN;
import static com.oracle.svm.core.posix.headers.Pthread.pthread_attr_destroy;
import static com.oracle.svm.core.posix.headers.Pthread.pthread_mutex_init;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platform.HOSTED_ONLY;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointContext;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.c.function.CEntryPointOptions.Publish;
import com.oracle.svm.core.c.function.CEntryPointSetup.LeaveDetachThreadEpilogue;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.os.IsDefined;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.LibC;
import com.oracle.svm.core.posix.headers.Pthread;
import com.oracle.svm.core.posix.headers.Pthread.pthread_attr_t;
import com.oracle.svm.core.posix.headers.darwin.DarwinPthread;
import com.oracle.svm.core.posix.headers.Sched;
import com.oracle.svm.core.posix.headers.Time;
import com.oracle.svm.core.posix.headers.linux.LinuxPthread;
import com.oracle.svm.core.posix.pthread.PthreadConditionUtils;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.ParkEvent;
import com.oracle.svm.core.thread.ParkEvent.ParkEventFactory;
import com.oracle.svm.core.util.VMError;

public final class PosixJavaThreads extends JavaThreads {

    @Fold
    public static PosixJavaThreads singleton() {
        return (PosixJavaThreads) JavaThreads.singleton();
    }

    @SuppressFBWarnings(value = "BC", justification = "Cast for @TargetClass")
    private static Target_java_lang_Thread toTarget(Thread thread) {
        return Target_java_lang_Thread.class.cast(thread);
    }

    @Platforms(HOSTED_ONLY.class)
    PosixJavaThreads() {
    }

    @Override
    protected void start0(Thread thread, long stackSize) {
        pthread_attr_t attributes = StackValue.get(pthread_attr_t.class);
        PosixUtils.checkStatusIs0(
                        Pthread.pthread_attr_init(attributes),
                        "PosixJavaThreads.start0: pthread_attr_init");
        PosixUtils.checkStatusIs0(
                        Pthread.pthread_attr_setdetachstate(attributes, Pthread.PTHREAD_CREATE_DETACHED()),
                        "PosixJavaThreads.start0: pthread_attr_init");
        long threadStackSize = stackSize;
        /* If there is a chosen stack size, use it as the stack size. */
        if (threadStackSize != 0) {
            /* Make sure the chosen stack size is large enough. */
            if ((threadStackSize < 0) || (threadStackSize < PTHREAD_STACK_MIN())) {
                threadStackSize = PTHREAD_STACK_MIN();
            }
            PosixUtils.checkStatusIs0(
                            Pthread.pthread_attr_setstacksize(attributes, WordFactory.unsigned(threadStackSize)),
                            "PosixJavaThreads.start0: pthread_attr_setstacksize");
        }

        PosixUtils.checkStatusIs0(
                        Pthread.pthread_attr_setguardsize(attributes, VirtualMemoryProvider.get().getGranularity()),
                        "PosixJavaThreads.start0: pthread_attr_setguardsize");

        ThreadStartData startData = UnmanagedMemory.malloc(SizeOf.get(ThreadStartData.class));
        startData.setIsolate(CEntryPointContext.getCurrentIsolate());
        startData.setThreadHandle(ObjectHandles.getGlobal().create(thread));

        if (!thread.isDaemon()) {
            JavaThreads.singleton().signalNonDaemonThreadStart();
        }

        Pthread.pthread_tPointer newThread = StackValue.get(Pthread.pthread_tPointer.class);
        PosixUtils.checkStatusIs0(
                        Pthread.pthread_create(newThread, attributes, PosixJavaThreads.pthreadStartRoutine.getFunctionPointer(), startData),
                        "PosixJavaThreads.start0: pthread_create");
        setPthreadIdentifier(thread, newThread.read());
        pthread_attr_destroy(attributes);
    }

    private static void setPthreadIdentifier(Thread thread, Pthread.pthread_t pthread) {
        toTarget(thread).hasPthreadIdentifier = true;
        toTarget(thread).pthreadIdentifier = pthread;
    }

    private static Pthread.pthread_t getPthreadIdentifier(Thread thread) {
        return toTarget(thread).pthreadIdentifier;
    }

    private static boolean hasThreadIdentifier(Thread thread) {
        return toTarget(thread).hasPthreadIdentifier;
    }

    /**
     * Try to set the native name of the current thread.
     *
     * Failures are ignored.
     */
    @Override
    protected void setNativeName(String name) {
        if (hasThreadIdentifier(Thread.currentThread())) {
            /* Use at most 15 characters from the right end of the name. */
            final int startIndex = Math.max(0, name.length() - 15);
            final String pthreadName = name.substring(startIndex);
            assert pthreadName.length() < 16 : "thread name for pthread has a maximum length of 16 characters including the terminating 0";
            try (CCharPointerHolder threadNameHolder = CTypeConversion.toCString(pthreadName)) {
                if (IsDefined.isLinux()) {
                    LinuxPthread.pthread_setname_np(getPthreadIdentifier(Thread.currentThread()), threadNameHolder.get());
                } else if (IsDefined.isDarwin()) {
                    DarwinPthread.pthread_setname_np(threadNameHolder.get());
                } else {
                    VMError.unsupportedFeature("PosixJavaThreads.setNativeName on unknown OS");
                }
            }
        }
    }

    @Override
    protected void yield() {
        Sched.sched_yield();
    }

    @RawStructure
    interface ThreadStartData extends PointerBase {

        @RawField
        ObjectHandle getThreadHandle();

        @RawField
        void setThreadHandle(ObjectHandle handle);

        @RawField
        Isolate getIsolate();

        @RawField
        void setIsolate(Isolate vm);
    }

    private static final CEntryPointLiteral<CFunctionPointer> pthreadStartRoutine = CEntryPointLiteral.create(PosixJavaThreads.class, "pthreadStartRoutine", ThreadStartData.class);

    private static class PthreadStartRoutinePrologue {
        private static final CGlobalData<CCharPointer> errorMessage = CGlobalDataFactory.createCString("Failed to attach a newly launched thread.");

        @SuppressWarnings("unused")
        static void enter(ThreadStartData data) {
            int code = CEntryPointActions.enterAttachThread(data.getIsolate());
            if (code != 0) {
                CEntryPointActions.failFatally(code, errorMessage.get());
            }
        }
    }

    @CEntryPoint
    @CEntryPointOptions(prologue = PthreadStartRoutinePrologue.class, epilogue = LeaveDetachThreadEpilogue.class, publishAs = Publish.NotPublished, include = CEntryPointOptions.NotIncludedAutomatically.class)
    static WordBase pthreadStartRoutine(ThreadStartData data) {
        ObjectHandle threadHandle = data.getThreadHandle();
        UnmanagedMemory.free(data);

        Thread thread = ObjectHandles.getGlobal().get(threadHandle);

        boolean status = singleton().assignJavaThread(thread, false);
        VMError.guarantee(status, "currentThread already initialized");

        /*
         * Destroy the handle only after setting currentThread, since the lock used by destroy
         * requires the current thread.
         */
        ObjectHandles.getGlobal().destroy(threadHandle);

        /* Complete the initialization of the thread, now that it is (nearly) running. */
        setPthreadIdentifier(thread, Pthread.pthread_self());
        singleton().setNativeName(thread.getName());

        singleton().noteThreadStart(thread);

        try {
            thread.run();
        } catch (Throwable ex) {
            dispatchUncaughtException(thread, ex);
        } finally {
            exit(thread);
            singleton().noteThreadFinish(thread);
        }

        return WordFactory.nullPointer();
    }

    private void noteThreadStart(Thread thread) {
        totalThreads.incrementAndGet();
        int lThreads = liveThreads.incrementAndGet();
        peakThreads.set(Integer.max(peakThreads.get(), lThreads));
        if (thread.isDaemon()) {
            daemonThreads.incrementAndGet();
        } else {
            nonDaemonThreads.incrementAndGet();
        }
    }

    private void noteThreadFinish(Thread thread) {
        liveThreads.decrementAndGet();
        if (thread.isDaemon()) {
            daemonThreads.decrementAndGet();
        } else {
            nonDaemonThreads.decrementAndGet();
        }
    }
}

@TargetClass(Thread.class)
@Platforms({Platform.LINUX.class, Platform.DARWIN.class})
final class Target_java_lang_Thread {
    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    boolean hasPthreadIdentifier;

    /** Every thread started by {@link PosixJavaThreads#start0} has an opaque pthread_t. */
    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    Pthread.pthread_t pthreadIdentifier;
}

class PosixParkEvent extends ParkEvent {

    /** A mutex: from the operating system. */
    private final Pthread.pthread_mutex_t mutex;
    /** A condition variable: from the operating system. */
    private final Pthread.pthread_cond_t cond;

    PosixParkEvent() {
        /* Create a mutex. */
        mutex = LibC.malloc(SizeOf.unsigned(Pthread.pthread_mutex_t.class));
        VMError.guarantee(mutex.isNonNull(), "mutex allocation");
        /* The attributes for the mutex. Can be null. */
        final Pthread.pthread_mutexattr_t mutexAttr = WordFactory.nullPointer();
        PosixUtils.checkStatusIs0(pthread_mutex_init(mutex, mutexAttr), "mutex initialization");

        /* Create a condition variable. */
        cond = LibC.malloc(SizeOf.unsigned(Pthread.pthread_cond_t.class));
        VMError.guarantee(cond.isNonNull(), "condition variable allocation");
        PosixUtils.checkStatusIs0(PthreadConditionUtils.initCondition(cond), "condition variable initialization");
    }

    @Override
    protected WaitResult condWait() {
        WaitResult result = WaitResult.UNPARKED;
        /* Lock the mutex in preparation for waiting. */
        PosixUtils.checkStatusIs0(Pthread.pthread_mutex_lock(mutex), "park(): mutex lock");
        try {
            if (resetEventBeforeWait) {
                event = false;
            }
            /*
             * Wait while the ticket is not available. Note that the ticket might already be
             * available before we enter the loop the first time, in which case we do not want to
             * wait at all.
             */
            while (!event) {
                /* Before blocking, check if this thread has been interrupted. */
                if (Thread.interrupted()) {
                    result = WaitResult.INTERRUPTED;
                    return result;
                }
                /* Wait on the condition variable and give up the mutex. */
                final int status = Pthread.pthread_cond_wait(cond, mutex);
                /*
                 * For some reason, under 2.7 lwp_cond_wait() may return ETIME ... Treat this the
                 * same as if the wait was interrupted
                 */
                if (status == Errno.EINTR() || status == Errno.ETIMEDOUT()) {
                    result = WaitResult.INTERRUPTED;
                    break;
                }
                PosixUtils.checkStatusIs0(status, "park(): condition variable wait");
            }

            if (event) {
                /* If the ticket is available, then someone unparked me. */
                event = false;
                result = WaitResult.UNPARKED;
            }
        } finally {
            /* Unlock the mutex. */
            PosixUtils.checkStatusIs0(Pthread.pthread_mutex_unlock(mutex), "park(): mutex unlock");
        }
        return result;
    }

    @Override
    protected WaitResult condTimedWait(long delayNanos) {
        /* Encode the delay as a deadline in a Time.timespec. */
        Time.timespec deadlineTimespec = StackValue.get(Time.timespec.class);
        PthreadConditionUtils.delayNanosToDeadlineTimespec(delayNanos, deadlineTimespec);

        WaitResult result = WaitResult.UNPARKED;
        /* Lock the mutex in preparation for waiting. */
        PosixUtils.checkStatusIs0(Pthread.pthread_mutex_lock(mutex), "park(long): mutex lock");
        try {
            if (resetEventBeforeWait) {
                event = false;
            }
            while (!event) {
                /* Before blocking, check if this thread has been interrupted. */
                if (Thread.interrupted()) {
                    result = WaitResult.INTERRUPTED;
                    return result;
                }
                final int status = Pthread.pthread_cond_timedwait(cond, mutex, deadlineTimespec);
                if (status == Errno.ETIMEDOUT()) {
                    /* If I was awakened because I ran out of time, do not wait for the ticket. */
                    result = WaitResult.TIMED_OUT;
                    break;
                }
                if (status == Errno.EINTR()) {
                    /* If I was awakened because I was interrupted, do not wait for the ticket. */
                    result = WaitResult.INTERRUPTED;
                    break;
                }
                if (status != 0) {
                    /* Detailed error message. */
                    Log.log().newline()
                                    .string("[PosixParkEvent.condTimedWait(delayNanos: ").signed(delayNanos).string("): Should not reach here.")
                                    .string("  mutex: ").hex(mutex)
                                    .string("  cond: ").hex(cond)
                                    .string("  deadlineTimeSpec.tv_sec: ").signed(deadlineTimespec.tv_sec())
                                    .string("  deadlineTimespec.tv_nsec: ").signed(deadlineTimespec.tv_nsec())
                                    .string("  status: ").signed(status).string(" ").string(Errno.strerror(status))
                                    .string("]").newline();
                    PosixUtils.checkStatusIs0(status, "park(long): condition variable timed wait");
                }
            }

            if (event) {
                /* If the ticket is available, then someone unparked me. */
                event = false;
                result = WaitResult.UNPARKED;
            }
        } finally {
            /* Unlock the mutex. */
            PosixUtils.checkStatusIs0(Pthread.pthread_mutex_unlock(mutex), "park(long): mutex unlock");
        }

        return result;
    }

    @Override
    protected void unpark() {
        /* Lock the mutex so threads trying to park do not miss my signal. */
        PosixUtils.checkStatusIs0(Pthread.pthread_mutex_lock(mutex), "PosixParkEvent.unpark(): mutex lock");
        try {
            /* Re-establish the ticket. */
            event = true;

            /* Broadcast to any waiters. */
            PosixUtils.checkStatusIs0(Pthread.pthread_cond_broadcast(cond), "PosixParkEvent.unpark(): condition variable broadcast");
        } finally {
            PosixUtils.checkStatusIs0(Pthread.pthread_mutex_unlock(mutex), "PosixParkEvent.unpark(): mutex unlock");
        }
    }
}

class PosixParkEventFactory implements ParkEventFactory {
    @Override
    public ParkEvent create() {
        return new PosixParkEvent();
    }
}

@AutomaticFeature
@Platforms({Platform.LINUX.class, Platform.DARWIN.class})
class PosixThreadsFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(JavaThreads.class, new PosixJavaThreads());
        ImageSingletons.add(ParkEventFactory.class, new PosixParkEventFactory());
    }
}
