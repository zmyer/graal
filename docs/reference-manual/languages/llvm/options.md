## LLI Command Options

`-L <path>`/`--llvm.libraryPath=<path>`: A list of paths where GraalVM will
search for library dependencies. Paths are delemited by `:`.

`--lib <libs>`/`--llvm.libraries=<libs>`: List of libraries to load. The list
can contain precompiled native libraries (`*.so`/`*.dylib`) and bitcode
libraries (`*.bc`). Files with a relative path are looked up relative to
`llvm.libraryPath`. Entries are delimited by `:`.

`--version`: print the version and exit

`--show-version`: print the version and continue

### Expert and Diagnostic Options

Use `--help` and `--help:<topic>` to get a full list of options.
