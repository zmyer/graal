## Python Command Options

Python is run using `graalpython [option] ... (-c cmd | file) [arg] ...`
and supports some of the same options as the standard Python interpreter:
   * `-c cmd`: program passed in as string (terminates option list)
   * `-h`: print this help message and exit (also `--help`)
   * `-i`: inspect interactively after running script; forces a prompt even if
     stdin does not appear to be a terminal; also PYTHONINSPECT=x
   * `-V`: Print the Python version number and exit (also `--version`)
   * `file`: Program read from script file
   * `arg ...`: Arguments passed to program in sys.argv[1:]

Here are some GraalVM-specific options:
   * `--python.PythonInspectFlag`: This is equivalent to Python's `-i` option
     (see above).
   * `-CC`: Run `clang` and then `opt` with the arguments required to build a
     GraalVM and Sulong compatible LLVM bitcode file.
   * `-LD`: Run `llvm-link` with the appropriate options to bind multiple LLVM
     bitcode files into one that can be used on GraalVM with Sulong.

The following options are mostly useful for developers of the language or to
provide bug reports:
   * `--python.CoreHome=<String>`: The path to the core library of Python
     that is written in Python. This usually resides in a folder
     `lib-graalpython` in the GraalVM distribution.
   * `--python.StdLibHome=<String>`: The path to the standard library that
     Python will use. Usually this is in a under `lib-python/3` in the
     GraalVM distribution, but any Python 3.7 standard library location may work.
   * `--python.WithJavaStacktrace`: Prints a Java-level stack trace besides the
     normal Python stack when errors occur.
   * `--python.LazyInit`: Load the core library after the Python context has
     been initialized. This only affects certain tools such as the Chrome
     debugger and lets these tools also see the code in the Python core library.
   * `--python.SharedCore`: This option is not fully implemented, yet. It will
     allow sharing parts of the core library across multiple Python contexts,
     which will improve startup time of subsequent contexts.

There are a few other debugging options used by the developers of GraalVM,
but these change frequently and may not do anything at any given point in time,
so any observed effects of them should not be relied upon.
