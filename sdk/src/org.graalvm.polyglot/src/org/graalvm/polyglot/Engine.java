/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.polyglot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractContextImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractEngineImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractExceptionImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractInstrumentImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractLanguageImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractStackFrameImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractValueImpl;
import org.graalvm.polyglot.management.ExecutionEvent;

/**
 * An execution engine for Graal {@linkplain Language guest languages} that allows to inspect the
 * the installed {@link #getLanguages() guest languages}, {@link #getInstruments() instruments} and
 * their available options.
 * <p>
 * By default every context creates its own {@link Engine engine} instance implicitly when
 * {@link Context.Builder#build() instantiated}. Multiple contexts can use an
 * {@link Context.Builder#engine(Engine) explicit engine} when using a context builder. If contexts
 * share the same engine instance then they share instruments and their configuration.
 * <p>
 * It can be useful to {@link Engine#create() create} an engine instance without a context to only
 * access meta-data for installed languages, instruments and their available options.
 *
 * @since 1.0
 */
public final class Engine implements AutoCloseable {

    final AbstractEngineImpl impl;

    Engine(AbstractEngineImpl impl) {
        this.impl = impl;
    }

    private static final class ImplHolder {
        private static final AbstractPolyglotImpl IMPL = initEngineImpl();

        /**
         * Performs context pre-initialization.
         *
         * NOTE: this method is called reflectively by downstream projects
         * (com.oracle.svm.truffle.TruffleFeature).
         */
        @SuppressWarnings("unused")
        private static void preInitializeEngine() {
            IMPL.preInitializeEngine();
        }

        /**
         * Clears the pre-initialized engine.
         *
         * NOTE: this method is called reflectively by downstream projects
         * (com.oracle.svm.truffle.TruffleFeature).
         */
        @SuppressWarnings("unused")
        private static void resetPreInitializedEngine() {
            IMPL.resetPreInitializedEngine();
        }
    }

    /**
     * Gets a map of all installed languages with the language id as key and the language object as
     * value. The returned map is unmodifiable and might be used from multiple threads.
     *
     * @since 1.0
     */
    public Map<String, Language> getLanguages() {
        return impl.getLanguages();
    }

    /**
     * Gets all installed instruments of this engine. An instrument alters and/or monitors the
     * execution of guest language source code. Common examples for instruments are debuggers,
     * profilers, or monitoring tools. Instruments are enabled via {@link Instrument#getOptions()
     * options} passed to the {@link Builder#option(String, String) engine} when the engine or
     * context is constructed.
     *
     * @since 1.0
     */
    public Map<String, Instrument> getInstruments() {
        return impl.getInstruments();
    }

    /**
     * Returns all options available for the engine. The engine offers options with the following
     * {@link OptionDescriptor#getKey() groups}:
     * <ul>
     * <li><b>engine</b>: options to configure the behavior of this engine.
     * <li><b>compiler</b>: options to configure the optimizing compiler.
     * </ul>
     * The language and instrument specific options need to be retrieved using
     * {@link Instrument#getOptions()} or {@link Language#getOptions()}.
     *
     * @see Language#getOptions() To get a list of options for a language.
     * @see Instrument#getOptions() To get a list of options for an instrument.
     * @see Builder#option(String, String) To set an option for an engine, language, or instrument.
     * @see Context.Builder#option(String, String) To set an option for a context.
     *
     * @since 1.0
     */
    public OptionDescriptors getOptions() {
        return impl.getOptions();
    }

    /**
     * Gets the version string of the engine in an unspecified format.
     *
     * @since 1.0
     */
    public String getVersion() {
        return impl.getVersion();
    }

    /**
     * Closes this engine and frees up allocated native resources. If there are still open context
     * instances that were created using this engine and they are currently not being executed then
     * they will be closed automatically. If an attempt to close an engine was successful then
     * consecutive calls to close have no effect. If a context is cancelled then the currently
     * executing thread will throw a {@link PolyglotException}. The exception indicates that it was
     * {@link PolyglotException#isCancelled() cancelled}.
     *
     * @param cancelIfExecuting if <code>true</code> then currently executing contexts will be
     *            cancelled, else an {@link IllegalStateException} is thrown.
     * @since 1.0
     */
    public void close(boolean cancelIfExecuting) {
        impl.close(this, cancelIfExecuting);
    }

    /**
     * Closes this engine and frees up allocated native resources. If there are still open context
     * instances that were created using this engine and they are currently not being executed then
     * they will be closed automatically. If an attempt to close the engine was successful then
     * consecutive calls to close have no effect.
     *
     * @throws IllegalStateException if there currently executing open context instances.
     * @see #close(boolean)
     * @see Engine#close()
     * @since 1.0
     */
    @Override
    public void close() {
        close(false);
    }

    /**
     * Gets a human-readable name of the polyglot implementation (for example, "Default Truffle
     * Engine" or "Graal Truffle Engine"). The returned value may change without notice. The value
     * is never <code>null</code>.
     *
     * @since 1.0
     */
    public String getImplementationName() {
        return impl.getImplementationName();
    }

    /**
     * Creates a new engine instance with default configuration. The engine is constructed with the
     * same configuration as it will be as when constructed implicitly using the context builder.
     *
     * @see Context#create(String...) to create a new execution context.
     * @since 1.0
     */
    public static Engine create() {
        return newBuilder().build();
    }

    /**
     * Creates a new context builder that allows to configure an engine instance.
     *
     * @see Context#newBuilder(String...) to construct a new execution context.
     * @since 1.0
     */
    public static Builder newBuilder() {
        return EMPTY.new Builder();
    }

    static AbstractPolyglotImpl getImpl() {
        return ImplHolder.IMPL;
    }

    /*
     * Used internally to load language specific classes.
     */
    static Class<?> loadLanguageClass(String className) {
        return getImpl().loadLanguageClass(className);
    }

    private static final Engine EMPTY = new Engine(null);

    /**
     *
     * @since 1.0
     */
    @SuppressWarnings("hiding")
    public final class Builder {

        private OutputStream out = System.out;
        private OutputStream err = System.err;
        private InputStream in = System.in;
        private Map<String, String> options = new HashMap<>();
        private boolean useSystemProperties = true;
        private boolean boundEngine;
        private Handler customLogHandler;

        Builder() {
        }

        Builder setBoundEngine(boolean boundEngine) {
            this.boundEngine = boundEngine;
            return this;
        }

        /**
         * Sets the standard output stream to be used for this engine. Every context that uses this
         * engine will inherit the configured output stream if it is not specified in the context.
         * If not set then the system output stream will be used.
         *
         * @since 1.0
         */
        public Builder out(OutputStream out) {
            Objects.requireNonNull(out);
            this.out = out;
            return this;
        }

        /**
         * Sets the standard error stream to be used for this engine. Every context that uses this
         * engine will inherit the configured error stream if it is not specified in the context. If
         * not set then the system error stream will be used.
         *
         * @since 1.0
         */
        public Builder err(OutputStream err) {
            Objects.requireNonNull(err);
            this.err = err;
            return this;
        }

        /**
         * Sets the standard input stream to be used for this engine. Every context that uses this
         * engine will inherit the configured input stream if it is not specified in the context. If
         * not set then the system input stream will be used.
         *
         * @since 1.0
         */
        public Builder in(InputStream in) {
            Objects.requireNonNull(in);
            this.in = in;
            return this;
        }

        /**
         * Specifies whether the engine should use {@link System#getProperty(String) system
         * properties} if no explicit option is {@link #option(String, String) set}. The default
         * value is <code>true</code> indicating that the system properties should be used. System
         * properties are looked up with the prefix <i>"polyglot"</i> in order to disambiguate
         * existing system properties. For example, for the option with the key
         * <code>"js.ECMACompatiblity"</code>, the system property
         * <code>"polyglot.js.ECMACompatiblity"</code> is read. Invalid options specified using
         * system properties will cause the {@link #build() build} method to fail using an
         * {@link IllegalArgumentException}. System properties are read once when the engine is
         * built and are never updated after that.
         *
         * @param enabled if <code>true</code> system properties will be used as options.
         * @see #option(String, String) To specify option values directly.
         * @see #build() To build the engine instance.
         * @since 1.0
         */
        public Builder useSystemProperties(boolean enabled) {
            useSystemProperties = enabled;
            return this;
        }

        /**
         * Sets an option for an {@link Engine#getOptions() engine}, {@link Language#getOptions()
         * language} or {@link Instrument#getOptions() instrument}.
         * <p>
         * If one of the set option keys or values is invalid then an
         * {@link IllegalArgumentException} is thrown when the engine is {@link #build() built}. The
         * given key and value must not be <code>null</code>.
         *
         * @see Engine#getOptions() To list all available options for engines.
         * @see Language#getOptions() To list all available options for a {@link Language language}.
         * @see Instrument#getOptions() To list all available options for an {@link Instrument
         *      instrument}.
         * @since 1.0
         */
        public Builder option(String key, String value) {
            Objects.requireNonNull(key, "Key must not be null.");
            Objects.requireNonNull(value, "Value must not be null.");
            options.put(key, value);
            return this;
        }

        /**
         * Shortcut for setting multiple {@link #option(String, String) options} using a map. All
         * values of the provided map must be non-null.
         *
         * @param options a map options.
         * @see #option(String, String) To set a single option.
         * @since 1.0
         */
        public Builder options(Map<String, String> options) {
            for (String key : options.keySet()) {
                Objects.requireNonNull(options.get(key), "All option values must be non-null.");
            }
            this.options.putAll(options);
            return this;
        }

        /**
         * Installs a new logging {@link Handler}. The logger's {@link Level} configuration is done
         * using the {@link #options(java.util.Map) Engine's options}. The level option key has the
         * following format: {@code log.languageId.loggerName.level} or
         * {@code log.instrumentId.loggerName.level}. The value is either the name of pre-defined
         * {@link Level} constant or a numeric {@link Level} value. If not explicitly set in options
         * the level is inherited from the parent logger.
         * <p>
         * <b>Examples</b> of setting log level options:<br>
         * {@code builder.option("log.level","FINE");} sets the {@link Level#FINE FINE level} to all
         * {@code TruffleLogger}s.<br>
         * {@code builder.option("log.js.level","FINE");} sets the {@link Level#FINE FINE level} to
         * JavaScript {@code TruffleLogger}s.<br>
         * {@code builder.option("log.js.com.oracle.truffle.js.parser.JavaScriptLanguage.level","FINE");}
         * sets the {@link Level#FINE FINE level} to {@code TruffleLogger} for the
         * {@code JavaScriptLanguage} class.<br>
         *
         * @param logHandler the {@link Handler} to use for logging in engine's {@link Context}s.
         * @return the {@link Builder}
         * @since 1.0
         */
        public Builder logHandler(final Handler logHandler) {
            Objects.requireNonNull(logHandler, "Hanlder must be non null.");
            this.customLogHandler = logHandler;
            return this;
        }

        /**
         *
         *
         * @since 1.0
         */
        public Engine build() {
            AbstractPolyglotImpl loadedImpl = getImpl();
            if (loadedImpl == null) {
                throw new IllegalStateException("The Polyglot API implementation failed to load.");
            }
            return loadedImpl.buildEngine(out, err, in, options, 0, null,
                            false, 0, useSystemProperties, boundEngine, customLogHandler);
        }

    }

    static class APIAccessImpl extends AbstractPolyglotImpl.APIAccess {

        @Override
        public Engine newEngine(AbstractEngineImpl impl) {
            return new Engine(impl);
        }

        @Override
        public AbstractExceptionImpl getImpl(PolyglotException value) {
            return value.impl;
        }

        @Override
        public Context newContext(AbstractContextImpl impl) {
            return new Context(impl);
        }

        @Override
        public PolyglotException newLanguageException(String message, AbstractExceptionImpl impl) {
            return new PolyglotException(message, impl);
        }

        @Override
        public Language newLanguage(AbstractLanguageImpl impl) {
            return new Language(impl);
        }

        @Override
        public Instrument newInstrument(AbstractInstrumentImpl impl) {
            return new Instrument(impl);
        }

        @Override
        public Value newValue(Object value, AbstractValueImpl impl) {
            return new Value(impl, value);
        }

        @Override
        public Source newSource(String language, Object impl) {
            return new Source(language, impl);
        }

        @Override
        public SourceSection newSourceSection(Source source, Object impl) {
            return new SourceSection(source, impl);
        }

        @Override
        public AbstractEngineImpl getImpl(Engine value) {
            return value.impl;
        }

        @Override
        public AbstractValueImpl getImpl(Value value) {
            return value.impl;
        }

        @Override
        public AbstractInstrumentImpl getImpl(Instrument value) {
            return value.impl;
        }

        @Override
        public AbstractLanguageImpl getImpl(Language value) {
            return value.impl;
        }

        @Override
        public AbstractStackFrameImpl getImpl(StackFrame value) {
            return value.impl;
        }

        @Override
        public Object getReceiver(Value value) {
            return value.receiver;
        }

        @Override
        public StackFrame newPolyglotStackTraceElement(PolyglotException e, AbstractStackFrameImpl impl) {
            return e.new StackFrame(impl);
        }
    }

    private static final boolean JDK8_OR_EARLIER = System.getProperty("java.specification.version").compareTo("1.9") < 0;

    private static AbstractPolyglotImpl initEngineImpl() {
        return AccessController.doPrivileged(new PrivilegedAction<AbstractPolyglotImpl>() {
            public AbstractPolyglotImpl run() {
                AbstractPolyglotImpl engine = null;
                Class<?> servicesClass = null;

                if (JDK8_OR_EARLIER) {
                    try {
                        servicesClass = Class.forName("jdk.vm.ci.services.Services");
                    } catch (ClassNotFoundException e) {
                    }
                    if (servicesClass != null) {
                        try {
                            Method m = servicesClass.getDeclaredMethod("loadSingle", Class.class, boolean.class);
                            engine = (AbstractPolyglotImpl) m.invoke(null, AbstractPolyglotImpl.class, false);
                        } catch (Throwable e) {
                            // Fail fast for other errors
                            throw new InternalError(e);
                        }
                    }
                } else {
                    // As of JDK9, the JVMCI Services class should only be used for service
                    // types
                    // defined by JVMCI. Other services types should use ServiceLoader directly.
                    Iterator<AbstractPolyglotImpl> providers = ServiceLoader.load(AbstractPolyglotImpl.class).iterator();
                    if (providers.hasNext()) {
                        engine = providers.next();
                        if (providers.hasNext()) {

                            throw new InternalError(String.format("Multiple %s providers found", AbstractPolyglotImpl.class.getName()));
                        }
                    }
                }

                if (engine == null) {
                    try {
                        Class<? extends AbstractPolyglotImpl> polyglotClass = Class.forName("com.oracle.truffle.api.vm.PolyglotImpl").asSubclass(AbstractPolyglotImpl.class);
                        Constructor<? extends AbstractPolyglotImpl> constructor = polyglotClass.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        engine = constructor.newInstance();
                    } catch (ClassNotFoundException e) {
                    } catch (Exception e1) {
                        throw new InternalError(e1);
                    }
                }

                if (engine == null) {
                    engine = createInvalidPolyglotImpl();
                }

                if (engine != null) {
                    engine.setConstructors(new APIAccessImpl());
                }
                return engine;
            }
        });
    }

    /*
     * Use static factory method with AbstractPolyglotImpl to avoid class loading of the
     * PolyglotInvalid class by the Java verifier.
     */
    static AbstractPolyglotImpl createInvalidPolyglotImpl() {
        return new PolyglotInvalid();
    }

    private static class PolyglotInvalid extends AbstractPolyglotImpl {

        private final EmptySource source = new EmptySource(this);

        /**
         * Forces ahead-of-time initialization.
         *
         * @since 0.8 or earlier
         */
        static boolean AOT;

        static {
            Boolean aot = AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                public Boolean run() {
                    return Boolean.getBoolean("com.oracle.graalvm.isaot");
                }
            });
            PolyglotInvalid.AOT = aot.booleanValue();
        }

        @Override
        public Engine buildEngine(OutputStream out, OutputStream err, InputStream in, Map<String, String> arguments, long timeout, TimeUnit timeoutUnit, boolean sandbox,
                        long maximumAllowedAllocationBytes, boolean useSystemProperties, boolean boundEngine, Handler logHandler) {
            throw noPolyglotImplementationFound();
        }

        @Override
        public AbstractExecutionListenerImpl getExecutionListenerImpl() {
            return new AbstractExecutionListenerImpl(this) {

                @Override
                public boolean isStatement(Object impl) {
                    return false;
                }

                @Override
                public boolean isRoot(Object impl) {
                    return false;
                }

                @Override
                public boolean isExpression(Object impl) {
                    return false;
                }

                @Override
                public String getRootName(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public PolyglotException getException(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public Value getReturnValue(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public SourceSection getLocation(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public List<Value> getInputValues(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public void closeExecutionListener(Object impl) {
                    throw noPolyglotImplementationFound();
                }

                @Override
                public Object attachExecutionListener(Engine engine, Consumer<ExecutionEvent> onEnter, Consumer<ExecutionEvent> onReturn, boolean expressions, boolean statements,
                                boolean roots,
                                Predicate<Source> sourceFilter, Predicate<String> rootFilter, boolean collectInputValues, boolean collectReturnValues, boolean collectErrors) {
                    throw noPolyglotImplementationFound();
                }
            };
        }

        private static RuntimeException noPolyglotImplementationFound() {
            String suggestion;
            if (AOT) {
                suggestion = "Make sure a language is added to the classpath (e.g., native-image --js).";
            } else {
                suggestion = "Make sure the truffle-api.jar is on the classpath.";
            }
            return new IllegalStateException("No language and polyglot implementation was found on the classpath. " + suggestion);
        }

        @Override
        public AbstractSourceImpl getSourceImpl() {
            return source;
        }

        @Override
        public AbstractSourceSectionImpl getSourceSectionImpl() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<?> loadLanguageClass(String className) {
            return null;
        }

        @Override
        public void preInitializeEngine() {
        }

        @Override
        public void resetPreInitializedEngine() {
        }

        static class EmptySource extends AbstractSourceImpl {

            protected EmptySource(AbstractPolyglotImpl engineImpl) {
                super(engineImpl);
            }

            @Override
            public Source build(String language, Object origin, URI uri, String name, CharSequence content, boolean interactive, boolean internal, boolean cached) throws IOException {
                throw noPolyglotImplementationFound();
            }

            @Override
            public String findLanguage(File file) throws IOException {
                return null;
            }

            @Override
            public String findLanguage(String mimeType) {
                return null;
            }

            @Override
            public String getName(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getPath(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isInteractive(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public URL getURL(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public URI getURI(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Reader getReader(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream getInputStream(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLength(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CharSequence getCode(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CharSequence getCode(Object impl, int lineNumber) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLineCount(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLineNumber(Object impl, int offset) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getColumnNumber(Object impl, int offset) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLineStartOffset(Object impl, int lineNumber) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLineLength(Object impl, int lineNumber) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String toString(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int hashCode(Object impl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean equals(Object impl, Object otherImpl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isInternal(Object impl) {
                throw new UnsupportedOperationException();
            }

        }

    }

}
