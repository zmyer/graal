## LLI Command Options

`-L <path>`/`--llvm.libraryPath=<path>`: a list of paths where GraalVM will
search for library dependencies. Paths are delimited by `:`.

`--lib <libs>`/`--llvm.libraries=<libs>`: a list of libraries to load. The list
can contain precompiled native libraries (`*.so`/`*.dylib`) and bitcode
libraries (`*.bc`). Files with a relative path are looked up relative to
`llvm.libraryPath`. Entries are delimited by `:`.

`--version` prints the version and exit.

`--version:graalvm` prints GraalVM version information and exit.

### Expert and Diagnostic Options

Use `--help` and `--help:<topic>` to get a full list of options.
