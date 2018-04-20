## LLVM Compatibility

GraalVM works with LLVM bitcode versions 3.8 to 6.0.

### Optimization flags

In contrast to the static compilation model of LLVM languages, in GraalVM the
machine code is not directly produced from the LLVM bitcode, but there is an
additional dynamic compilation step by the Graal compiler.

In this scenario, first the LLVM frontend (e.g. `clang`) does optimizations on
the bitcode level, and then Graal does its own optimizations on top of that
during dynamic compilation. Some optimizations are better when done
ahead-of-time on the bitcode, while other optimizations are better left for the
dynamic compilation of Graal, when profiling information is available.

In principle, all optimization levels should work, but for best results we
suggest compiling the bitcode with optimization level `-O1`.

Cross-language interoperability will only work when the bitcode is compiled
with debug information enabled (`-g`), and the `-mem2reg` optimization is
performed on the bitcode (compiled with at least `-O1`, or explicitly using the
`opt` tool).
