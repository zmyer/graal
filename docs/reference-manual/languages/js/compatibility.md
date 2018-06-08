## GraalVM JavaScript Compatibility

GraalVM is [ECMAScript 2017](http://www.ecma-international.org/ecma-262/8.0/index.html) compliant and fully compatible with a diverse range of active Node.js (npm) modules.
More than 50,000 npm packages are regularly tested and compatible with GraalVM, including modules like express, react, async, request, browserify, grunt, mocha, and underscore.
This release of GraalVM is based on Node.js version 8.11.1.

### Is GraalVM compatible with the JavaScript language?

_What version of ECMAScript do we support?_

GraalVM is compatible to the ECMAScript 2017 specification.
Most features of ECMAScript 2018 and some proposed features and extensions are available as well, but might not be fully implemented or compliant, yet.

_How do we know it?_

GraalVM is tested against the official test suite of ECMAScript, [test262](https://github.com/tc39/test262).
Even though this test set includes some draft features, GraalVM compliance is around 95% and rising.

In our internal CI system, we test against test262, tests published by Nashorn and V8, Node unit tests, as well as GraalVM's own unit tests.

From the graaljs code repository, you can execute the whole test262 test suite:
```
mx test262 gate
```

This will execute test262 in a mode to guarantee that local changes don't regress compatibility, i.e., results in an error if any tests expected to pass actually fail.
Individual tests can be executed with
```
mx test262 single=built-ins/Array/length.js
```

For a reference of the JavaScript APIs that GraalVM supports, see [GRAAL.JS-API](https://github.com/graalvm/graaljs/blob/master/docs/GRAAL.JS-API.md).

### Is GraalVM compatible with the original node implementation?

Node.js based on GraalVM is largely compatible with the original Node.js (based on the V8 engine).
This leads to a high number of npm-based modules being compatible with GraalVM (out of the 50k modules we test, 95% of them pass all tests).
Several sources of differences have to be considered.

- **Setup**
GraalVM mostly mimicks the original setup of Node, including the `node` executable, `npm`, and similar. However, not all command-line options are supported (or behave exactly identically), you need to (re-)compile native modules against our v8.h file, etc.

- **Internals**
GraalVM is implemented on top of a JVM, and thus has a different internal architecture. This implies that some internal mechanisms behave differently and cannot exactly replicate V8 behavior. This will hardly ever affect user code, but might affect modules implemented natively, depending on V8 internals.

- **Performance**
Due to GraalVM being implemented on top of a JVM, performance characteristics vary from the original native implementation. While GraalVM's peak performance can match V8 on many benchmarks, it will typically take longer to reach the peak (known as _warmup_). Be sure to give the Graal compiler some extra time when measuring (peak) performance.

_How do we determine GraalVM's JavaScript compatibility?_

GraalVM is compatible to ECMAScript 2017, guaranteeing compatibility on the language level.
In addition, GraalVM uses the following approaches to check and retain compatibility to Node.js code:

* node-compat-table: GraalVM is compared against other engines using the _node-compat-table_ module, highlighting incompatibilities that might break Node.js code.
* automated mass-testing of modules using _mocha_: In order to test a large set of modules, GraalVM is tested against 50k modules that use the mocha test framework. Using mocha allows automating the process of executing the test and comprehending the test result.
* manual testing of popular modules: A select list of npm modules is tested in a manual test setup. These highly-relevant modules are tested in a more sophisticated manner.

If you want your module to be tested by GraalVM in the future, ensure the module provides some mocha tests (and send us an email so we can ensure it's on the list of tested modules).

_How can one verify GraalVM works on their application?_

If your module ships with tests, execute them with GraalVM.
Of course, this will only test your app, but not its dependencies.
You can use the [compatibility checker]({{"/docs/reference-manual/compatibility/" | relative_url}}) to find whether the module you're interested in is tested on GraalVM, whether the tests pass successfully and so on.
Additionally, you can upload your `package-lock.json` or `package.json` file into that utility and it'll analyze all your dependencies at once.
