/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.debug;

import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.debug.DebugValue.HeapValue;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import java.util.Objects;

/**
 * Represents a frame in the guest language stack. A guest language stack frame consists of a
 * {@link #getName() name}, the current {@link #getSourceSection() source location} and
 * {@link #getScope() scopes} containing local variables and arguments. Furthermore it allows to
 * {@link #eval(String) evaluate} guest language expressions in the lexical context of a particular
 * frame.
 * <p>
 * Debug stack frames are only valid as long as {@link SuspendedEvent suspended events} are valid.
 * Suspended events are valid as long while the originating {@link SuspendedCallback} is still
 * executing. All methods of the frame throw {@link IllegalStateException} if they become invalid.
 * <p>
 * Depending on the method, clients may access the stack frame only on the execution thread where
 * the suspended event of the stack frame was created and the notification was received. For some
 * methods, access from other threads may throw {@link IllegalStateException}. Please see the
 * javadoc of the individual method for details.
 *
 * @see SuspendedEvent#getStackFrames()
 * @see SuspendedEvent#getTopStackFrame()
 * @since 0.17
 */
public final class DebugStackFrame implements Iterable<DebugValue> {

    final SuspendedEvent event;
    private final FrameInstance currentFrame;
    private final int depth;    // The frame depth on stack. 0 is the top frame

    DebugStackFrame(SuspendedEvent session, FrameInstance instance, int depth) {
        this.event = session;
        this.currentFrame = instance;
        this.depth = depth;
    }

    /**
     * Returns whether this stack frame is a language implementation artifact that should be hidden
     * during normal guest language debugging, for example in stack traces.
     * <p>
     * Language implementations sometimes create method calls internally that do not correspond to
     * anything explicitly written by a programmer, for example when the body of a looping construct
     * is implemented as callable block. Language implementors mark these methods as
     * <em>internal</em>.
     * </p>
     * <p>
     * Clients of the debugging API should assume that displaying <em>internal</em> frames is
     * unlikely to help programmers debug guest language programs and might possibly create
     * confusion. However, clients may choose to display all frames, for example in a special mode
     * to support development of programming language implementations.
     * </p>
     * <p>
     * The decision to mark a method as <em>internal</em> is language-specific, reflects judgments
     * about tool usability, and is subject to change.
     * <p>
     * This method is thread-safe.
     *
     * @since 0.17
     */
    public boolean isInternal() {
        verifyValidState(true);
        RootNode root = findCurrentRoot();
        if (root == null) {
            return true;
        }
        return root.isInternal();
    }

    /**
     * A description of the AST (expected to be a method or procedure name in most languages) that
     * identifies the AST for the benefit of guest language programmers using tools; it might
     * appear, for example in the context of a stack dump or trace and is not expected to be called
     * often. If the language does not provide such a description then <code>null</code> is
     * returned.
     *
     * <p>
     * This method is thread-safe.
     *
     * @since 0.17
     */
    public String getName() {
        verifyValidState(true);
        RootNode root = findCurrentRoot();
        if (root == null) {
            return null;
        }
        try {
            return root.getName();
        } catch (Throwable e) {
            /* Throw error if assertions are enabled. */
            try {
                assert false;
            } catch (AssertionError e1) {
                throw e;
            }
            return null;
        }
    }

    /**
     * Returns the source section of the location where the debugging session was suspended. The
     * source section is <code>null</code> if the source location is not available.
     *
     * <p>
     * This method is thread-safe.
     *
     * @since 0.17
     */
    public SourceSection getSourceSection() {
        verifyValidState(true);
        if (currentFrame == null) {
            SuspendedContext context = getContext();
            return context.getInstrumentedSourceSection();
        } else {
            Node callNode = currentFrame.getCallNode();
            if (callNode != null) {
                return callNode.getEncapsulatingSourceSection();
            }
            return null;
        }
    }

    /**
     * Returns public information about the language of this frame.
     *
     * @return the language info, or <code>null</code> when no language is associated with this
     *         frame.
     * @since 1.0
     */
    public LanguageInfo getLanguage() {
        verifyValidState(true);
        RootNode root = findCurrentRoot();
        if (root == null) {
            return null;
        }
        return root.getLanguageInfo();
    }

    /**
     * Get the current inner-most scope. The scope remain valid as long as the current stack frame
     * remains valid.
     * <p>
     * Use {@link DebuggerSession#getTopScope(java.lang.String)} to get scopes with global validity.
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @return the scope, or <code>null</code> when no language is associated with this frame
     *         location, or when no local scope exists.
     * @since 0.26
     */
    public DebugScope getScope() {
        verifyValidState(false);
        SuspendedContext context = getContext();
        RootNode root = findCurrentRoot();
        if (root == null) {
            return null;
        }
        Node node;
        if (currentFrame == null) {
            node = context.getInstrumentedNode();
        } else {
            node = currentFrame.getCallNode();
        }
        if (node.getRootNode().getLanguageInfo() == null) {
            // no language, no scopes
            return null;
        }
        Debugger debugger = event.getSession().getDebugger();
        MaterializedFrame frame = findTruffleFrame();
        Iterable<Scope> scopes = debugger.getEnv().findLocalScopes(node, frame);
        Iterator<Scope> it = scopes.iterator();
        if (!it.hasNext()) {
            return null;
        }
        return new DebugScope(it.next(), it, debugger, event, frame, root);
    }

    /**
     * Lookup a stack value with a given name. If no value is available in the current stack frame
     * with that name <code>null</code> is returned. Stack values are only accessible as as long as
     * the {@link DebugStackFrame debug stack frame} is valid. Debug stack frames are only valid as
     * long as the source {@link SuspendedEvent suspended event} is valid.
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @param name the name of the local variable to query.
     * @return the value from the stack
     * @since 0.17
     * @deprecated Use {@link #getScope()} and {@link DebugScope#getDeclaredValue(java.lang.String)}
     *             .
     */
    @Deprecated
    public DebugValue getValue(String name) {
        DebugScope scope = getScope();
        while (scope != null) {
            DebugValue value = scope.getDeclaredValue(name);
            if (value != null) {
                return value;
            }
            // Search for the value up to the function root, to be compatible.
            if (scope.isFunctionScope()) {
                break;
            }
            scope = scope.getParent();
        }
        return null;
    }

    DebugValue wrapHeapValue(Object result) {
        LanguageInfo language;
        RootNode root = findCurrentRoot();
        if (root != null) {
            language = root.getLanguageInfo();
        } else {
            language = null;
        }
        return new HeapValue(event.getSession().getDebugger(), language, null, result);
    }

    /**
     * Evaluates the given code in the state of the current execution and in the same guest language
     * as the current language is defined in. Returns a heap value that remains valid even if this
     * stack frame becomes invalid.
     *
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @param code the code to evaluate
     * @return the return value of the expression
     * @throws DebugException when guest language code throws an exception
     * @throws IllegalStateException if called on another thread than this frame was created with,
     *             or if {@link #getLanguage() language} of this frame is not
     *             {@link LanguageInfo#isInteractive() interactive}.
     * @since 0.17
     */
    public DebugValue eval(String code) throws DebugException {
        verifyValidState(false);
        Object result = DebuggerSession.evalInContext(event, code, currentFrame);
        return wrapHeapValue(result);
    }

    /**
     * Returns an {@link Iterator iterator} for all stack values available in this frame. The
     * returned stack values remain valid as long as the current stack frame remains valid.
     *
     * <p>
     * This method is not thread-safe and will throw an {@link IllegalStateException} if called on
     * another thread than it was created with.
     *
     * @since 0.17
     * @deprecated Use {@link #getScope()} and {@link DebugScope#getDeclaredValues()}.
     */
    @Deprecated
    public Iterator<DebugValue> iterator() {
        DebugScope cscope = getScope();
        // Merge non-masked variables from all scopes:
        return new Iterator<DebugValue>() {
            private DebugScope scope = cscope;
            private Iterator<DebugValue> variables;
            private DebugValue nextVar;
            private Set<String> names = new HashSet<>();

            @Override
            public boolean hasNext() {
                if (nextVar != null) {
                    return true;
                }
                for (;;) {
                    if (variables == null && scope != null) {
                        variables = scope.getDeclaredValues().iterator();
                        if (!variables.hasNext()) {
                            variables = null;
                        }
                        if (scope.isFunctionScope()) {
                            // Stop at the function, do not go to closures, to be compatible.
                            scope = null;
                        } else {
                            scope = scope.getParent();
                        }
                        if (variables == null) {
                            continue;
                        }
                    }
                    if (variables != null && variables.hasNext()) {
                        nextVar = variables.next();
                        String name = nextVar.getName();
                        if (!names.contains(name)) {
                            names.add(name);
                            return true;
                        }
                    } else {
                        variables = null;
                        if (scope == null) {
                            return false;
                        }
                    }
                }
            }

            @Override
            public DebugValue next() {
                if (nextVar == null) {
                    hasNext();
                }
                DebugValue var = nextVar;
                if (var == null) {
                    throw new NoSuchElementException();
                }
                nextVar = null;
                return var;
            }
        };
    }

    /**
     * @since 1.0
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DebugStackFrame) {
            DebugStackFrame other = (DebugStackFrame) obj;
            return event == other.event &&
                            (currentFrame == other.currentFrame ||
                                            currentFrame != null && other.currentFrame != null && currentFrame.getFrame(FrameAccess.READ_ONLY) == other.currentFrame.getFrame(FrameAccess.READ_ONLY));
        }
        return false;
    }

    /**
     * @since 1.0
     */
    @Override
    public int hashCode() {
        return Objects.hash(event, currentFrame);
    }

    MaterializedFrame findTruffleFrame() {
        if (currentFrame == null) {
            return event.getMaterializedFrame();
        } else {
            return currentFrame.getFrame(FrameAccess.MATERIALIZE).materialize();
        }
    }

    int getDepth() {
        return depth;
    }

    private SuspendedContext getContext() {
        SuspendedContext context = event.getContext();
        if (context == null) {
            // there is a race condition here if the event
            // got disposed between the parent verifyValidState and getContext.
            // if the context is null we assume the event got disposed so we re-check
            // the disposed flag. return null should therefore not be reachable.
            verifyValidState(true);
            assert false : "should not be reachable";
        }
        return context;
    }

    RootNode findCurrentRoot() {
        SuspendedContext context = getContext();
        if (currentFrame == null) {
            return context.getInstrumentedNode().getRootNode();
        } else {
            Node callNode = currentFrame.getCallNode();
            if (callNode != null) {
                return callNode.getRootNode();
            }
            CallTarget target = currentFrame.getCallTarget();
            if (target instanceof RootCallTarget) {
                return ((RootCallTarget) target).getRootNode();
            }
            return null;
        }
    }

    void verifyValidState(boolean allowDifferentThread) {
        event.verifyValidState(allowDifferentThread);
    }

}
