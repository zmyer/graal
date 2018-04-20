## Graal Configuration on JVM

The options for configuring Graal on the JVM are in 2 categories.

### JVMCI HotSpot options

Graal interfaces with the JVM via the Java Virtual Machine Compiler Interface
(JVMCI). That is, Graal is an instantiation of a JVMCI compiler.
There are a number of [`-XX` options](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/java.html)
for configuring JVMCI specific functionality. Interesting ones include:

* `-XX:-UseJVMCICompiler`: Disables use of the Graal as the top tier JIT. This is
useful when wanting to compare performance of Graal against the native JIT compilers.
* `-XX:+JVMCIPrintProperties`: Prints help for all defined `jvmci.*` and `graal.*` system properties.
* `-XX:-UseJVMCIClassLoader`: Disables the class loader used to isolate JVMCI and Graal
from application code. This is useful if you want to
[programmatically invoke Graal](https://github.com/oracle/graal/blob/eda70d0f1cfdfb0baa9abca534e2c36184bc1546/compiler/src/org.graalvm.compiler.core.test/src/org/graalvm/compiler/core/test/tutorial/InvokeGraal.java#L56-L58).
* `-XX:+BootstrapJVMCI`: Causes Graal to be compiled before running the Java main method.
By default, Graal is compiled by the C1 compiler to mitigate warmup costs. To force Graal
to compile itself, add either `Dgraal.CompileGraalWithC1Only=false` or `-XX:-TieredCompilation`
to the command line.
* `-XX:+EagerJVMCI`: By default, Graal is only initialized upon the first top tier compilation
request. Use this flag to force eager initialization which can be useful for [testing](https://bugs.openjdk.java.net/browse/JDK-8195632).
* `-XX:JVMCIThreads=1`: By the default, JVM ergonomics decide how many threads are to
be used for Graal compilation. When debugging Graal in a Java debugger, it often helps to
restrict Graal compilation to a single thread with this option.

### Graal System Properties

In addition to the JVMCI `-XX` options, `graal.*` system properties
can be used to configure Graal. A selection of interesting ones is shown below.
To see the complete list, use the `-XX:+JVMCIPrintProperties` option.

* `ShowConfiguration`: Prints various information about the compiler configuration in use.
This option is best used as follows: `java -XX:EagerJVMCI -Dgraal.ShowConfiguration=info -version`.
Since Graal is only initialized upon the first top tier JIT compilation
request, without `-XX:+EagerJVMCI` Graal may not be initialized at all
in which case `-Dgraal.ShowConfiguration` will be ignored. Adding `-version`
avoids the noise of the `java` launcher usage message while providing useful
information about the VM configuration at the same time.
* `-Dgraal.CompilationFailureAction=Silent`: Suppresses any output on the console when
a compilation fails due to an exception indicating an internal compiler error.
The default behavior (corresponding to the value `Diagnose`) is to retry the compilation
with extra diagnostics. Upon VM shut down, the diagnostics will be collated into
a single file that can be submitted with bug reports. To get notifications of
compiler errors without the extra diagnostics, set this option value to `Print`.
To exit the VM on a compiler error, set this option value to `ExitVM`.
* `-Dgraal.CompilationBailoutAction=Print`: This option is similar to
`graal.CompilationFailureAction` except that it applies to compilations that
are aborted due to input that the compiler decides not to compile. For example,
bytecode that contains unbalanced monitors. The default value for this option
is `Silent`.
* `-Dgraal.CompileGraalWithC1Only=false`: Specifies that Graal should compile itself.
By default, Graal is compiled by C1.
* `-Dgraal.GraalCompileOnly=<pattern>`: Restricts compilation by Graal only to methods
matching `<pattern>`. The pattern format is the same as that for the `graal.MethodFilter`
option whose complete help description can be seen with `-XX:+JVMCIPrintProperties`.
* `-Dgraal.TraceInlining=true`: Shows the inlining decision tree for each method compiled.

### Graal System Properties with other GraalVM Launchers

These Graal properties above are usable with some other GraalVM launchers such as
`node`, `js` and `lli`. The prefix for specifying the properties is slightly different.
For example:

```
$ java -Dgraal.ShowConfiguration=info
```

Becomes:

```
$ js --jvm.Dgraal.ShowConfiguration=info
```

Note the `-D` prefix is replaced by `--jvm.D`.
