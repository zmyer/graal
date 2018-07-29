#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------

import os
from os.path import join, exists, getmtime, basename, isdir
from collections import namedtuple
from argparse import ArgumentParser, RawDescriptionHelpFormatter
import re
import stat
import zipfile
import tarfile
import subprocess
import tempfile
import shutil

import mx_truffle
import mx_sdk

import mx
import mx_gate
from mx_gate import Task

import mx_unittest
from mx_unittest import unittest

from mx_javamodules import as_java_module
import mx_jaotc

import mx_graal_benchmark # pylint: disable=unused-import
import mx_graal_tools #pylint: disable=unused-import

import argparse
import shlex
import glob

_suite = mx.suite('compiler')

""" Prefix for running the VM. """
_vm_prefix = None

def get_vm_prefix(asList=True):
    """
    Get the prefix for running the VM (e.g. "gdb --args").
    """
    if asList:
        return _vm_prefix.split() if _vm_prefix is not None else []
    return _vm_prefix

#: The JDK used to build and run Graal.
jdk = mx.get_jdk(tag='default')

if jdk.javaCompliance < '1.8':
    mx.abort('Graal requires JDK8 or later, got ' + str(jdk))

#: Specifies if Graal is being built/run against JDK8. If false, then
#: JDK9 or later is being used (checked above).
isJDK8 = jdk.javaCompliance < '1.9'

def _check_jvmci_version(jdk):
    """
    Runs a Java utility to check that `jdk` supports the minimum JVMCI API required by Graal.
    """
    simplename = 'JVMCIVersionCheck'
    name = 'org.graalvm.compiler.hotspot.' + simplename
    binDir = mx.ensure_dir_exists(join(_suite.get_output_root(), '.jdk' + str(jdk.version)))
    if isinstance(_suite, mx.BinarySuite):
        javaSource = join(binDir, simplename + '.java')
        if not exists(javaSource):
            dists = [d for d in _suite.dists if d.name == 'GRAAL_HOTSPOT']
            assert len(dists) == 1, 'could not find GRAAL_HOTSPOT distribution'
            d = dists[0]
            assert exists(d.sourcesPath), 'missing expected file: ' + d.sourcesPath
            with zipfile.ZipFile(d.sourcesPath, 'r') as zf:
                with open(javaSource, 'w') as fp:
                    fp.write(zf.read(name.replace('.', '/') + '.java'))
    else:
        javaSource = join(_suite.dir, 'src', 'org.graalvm.compiler.hotspot', 'src', name.replace('.', '/') + '.java')
    javaClass = join(binDir, name.replace('.', '/') + '.class')
    if not exists(javaClass) or getmtime(javaClass) < getmtime(javaSource):
        mx.run([jdk.javac, '-d', binDir, javaSource])
    mx.run([jdk.java, '-cp', binDir, name])

if os.environ.get('JVMCI_VERSION_CHECK', None) != 'ignore':
    _check_jvmci_version(jdk)

class JVMCIClasspathEntry(object):
    """
    Denotes a distribution that is put on the JVMCI class path.

    :param str name: the name of the `JARDistribution` to be deployed
    """
    def __init__(self, name):
        self._name = name

    def dist(self):
        """
        Gets the `JARDistribution` deployed on the JVMCI class path.
        """
        return mx.distribution(self._name)

    def get_path(self):
        """
        Gets the path to the distribution jar file.

        :rtype: str
        """
        return self.dist().classpath_repr()

#: The deployed Graal distributions
_jvmci_classpath = [
    JVMCIClasspathEntry('GRAAL'),
]

def add_jvmci_classpath_entry(entry):
    """
    Appends an entry to the JVMCI classpath.
    """
    _jvmci_classpath.append(entry)

if jdk.javaCompliance != '9' and jdk.javaCompliance != '10' and mx.get_os() != 'windows':
    # The jdk.internal.vm.compiler.management module is
    # not available in 9 nor upgradeable in 10
    add_jvmci_classpath_entry(JVMCIClasspathEntry('GRAAL_MANAGEMENT'))

_bootclasspath_appends = []

def add_bootclasspath_append(dep):
    """
    Adds a distribution that must be appended to the boot class path
    """
    assert dep.isJARDistribution(), dep.name + ' is not a distribution'
    _bootclasspath_appends.append(dep)

mx_gate.add_jacoco_includes(['org.graalvm.compiler.*'])
mx_gate.add_jacoco_excluded_annotations(['@Snippet', '@ClassSubstitution'])

def _get_XX_option_value(vmargs, name, default):
    """
    Gets the value of an ``-XX:`` style HotSpot VM option.

    :param list vmargs: VM arguments to inspect
    :param str name: the name of the option
    :param default: the default value of the option if it's not present in `vmargs`
    :return: the value of the option as specified in `vmargs` or `default`
    """
    for arg in reversed(vmargs):
        if arg == '-XX:-' + name:
            return False
        if arg == '-XX:+' + name:
            return True
        if arg.startswith('-XX:' + name + '='):
            return arg[len('-XX:' + name + '='):]
    return default

def _is_jvmci_enabled(vmargs):
    """
    Determines if JVMCI is enabled according to the given VM arguments and whether JDK > 8.

    :param list vmargs: VM arguments to inspect
    """
    return _get_XX_option_value(vmargs, 'EnableJVMCI', isJDK8)

def _nodeCostDump(args, extraVMarguments=None):
    """list the costs associated with each Node type"""
    import csv, StringIO
    parser = ArgumentParser(prog='mx nodecostdump')
    parser.add_argument('--regex', action='store', help="Node Name Regex", default=False, metavar='<regex>')
    parser.add_argument('--markdown', action='store_const', const=True, help="Format to Markdown table", default=False)
    args, vmargs = parser.parse_known_args(args)
    additionalPrimarySuiteClassPath = '-Dprimary.suite.cp=' + mx.primary_suite().dir
    vmargs.extend([additionalPrimarySuiteClassPath, '-XX:-UseJVMCIClassLoader', 'org.graalvm.compiler.hotspot.NodeCostDumpUtil'])
    out = mx.OutputCapture()
    regex = ""
    if args.regex:
        regex = args.regex
    run_vm(vmargs + _remove_empty_entries(extraVMarguments) + [regex], out=out)
    if args.markdown:
        stringIO = StringIO.StringIO(out.data)
        reader = csv.reader(stringIO, delimiter=';', lineterminator="\n")
        firstRow = True
        maxLen = 0
        for row in reader:
            for col in row:
                maxLen = max(maxLen, len(col))
        stringIO.seek(0)
        for row in reader:
            s = '|'
            if firstRow:
                firstRow = False
                nrOfCols = len(row)
                for col in row:
                    s = s + col + "|"
                print s
                s = '|'
                for _ in range(nrOfCols):
                    s = s + ('-' * maxLen) + '|'
            else:
                for col in row:
                    s = s + col + "|"
            print s
    else:
        print out.data

def _ctw_jvmci_export_args():
    """
    Gets the VM args needed to export JVMCI API required by CTW.
    """
    if isJDK8:
        return ['-XX:-UseJVMCIClassLoader']
    else:
        return ['--add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.services=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED']

def _ctw_system_properties_suffix():
    out = mx.OutputCapture()
    out.data = 'System properties for CTW:\n\n'
    args = ['-XX:+EnableJVMCI'] + _ctw_jvmci_export_args()
    args.extend(['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk),
            '-DCompileTheWorld.Help=true', 'org.graalvm.compiler.hotspot.test.CompileTheWorld'])
    run_java(args, out=out, addDefaultArgs=False)
    return out.data

def ctw(args, extraVMarguments=None):
    """run CompileTheWorld"""

    defaultCtwopts = 'Inline=false'

    parser = ArgumentParser(prog='mx ctw', formatter_class=RawDescriptionHelpFormatter, epilog=_ctw_system_properties_suffix())
    parser.add_argument('--ctwopts', action='store', help='space separated Graal options used for CTW compilations (default: --ctwopts="' + defaultCtwopts + '")', metavar='<options>')
    parser.add_argument('--cp', '--jar', action='store', help='jar or class path denoting classes to compile', metavar='<path>')
    if not isJDK8:
        parser.add_argument('--limitmods', action='store', help='limits the set of compiled classes to only those in the listed modules', metavar='<modulename>[,<modulename>...]')

    configArgs = [a for a in args if a.startswith('-DCompileTheWorld.Config=')]
    args, vmargs = parser.parse_known_args(args)

    if args.ctwopts:
        if configArgs:
            mx.abort('Cannot specify both --ctwopts and -DCompileTheWorld.Config')
        vmargs.append('-DCompileTheWorld.Config=' + re.sub(r'\s+', '#', args.ctwopts))
    elif not configArgs:
        vmargs.append('-DCompileTheWorld.Config=Inline=false')

    # suppress menubar and dock when running on Mac
    vmargs = ['-Djava.awt.headless=true'] + vmargs

    if args.cp:
        cp = os.path.abspath(args.cp)
        if not isJDK8 and not _is_jvmci_enabled(vmargs):
            mx.abort('Non-Graal CTW does not support specifying a specific class path or jar to compile')
    else:
        # Default to the CompileTheWorld.SUN_BOOT_CLASS_PATH token
        cp = None

    # Exclude X11 classes as they may cause VM crashes (on Solaris)
    exclusionPrefix = '-DCompileTheWorld.ExcludeMethodFilter='
    exclusions = ','.join([a[len(exclusionPrefix):] for a in vmargs if a.startswith(exclusionPrefix)] + ['sun.awt.X11.*.*'])
    vmargs.append(exclusionPrefix + exclusions)

    if _get_XX_option_value(vmargs + _remove_empty_entries(extraVMarguments), 'UseJVMCICompiler', False):
        vmargs.append('-XX:+BootstrapJVMCI')

    mainClassAndArgs = []
    if isJDK8:
        if not _is_jvmci_enabled(vmargs):
            vmargs.append('-XX:+CompileTheWorld')
            if cp is not None:
                vmargs.append('-Xbootclasspath/p:' + cp)
        else:
            if cp is not None:
                vmargs.append('-DCompileTheWorld.Classpath=' + cp)
            vmargs.extend(_ctw_jvmci_export_args() + ['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk)])
            mainClassAndArgs = ['org.graalvm.compiler.hotspot.test.CompileTheWorld']
    else:
        if _is_jvmci_enabled(vmargs):
            # To be able to load all classes in the JRT with Class.forName,
            # all JDK modules need to be made root modules.
            limitmods = frozenset(args.limitmods.split(',')) if args.limitmods else None
            nonBootJDKModules = [m.name for m in jdk.get_modules() if not m.boot and (limitmods is None or m.name in limitmods)]
            if nonBootJDKModules:
                vmargs.append('--add-modules=' + ','.join(nonBootJDKModules))
            if args.limitmods:
                vmargs.append('-DCompileTheWorld.limitmods=' + args.limitmods)
            if cp is not None:
                vmargs.append('-DCompileTheWorld.Classpath=' + cp)
            vmargs.extend(_ctw_jvmci_export_args() + ['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk)])
            mainClassAndArgs = ['org.graalvm.compiler.hotspot.test.CompileTheWorld']
        else:
            vmargs.append('-XX:+CompileTheWorld')

    run_vm(vmargs + _remove_empty_entries(extraVMarguments) + mainClassAndArgs)

def verify_jvmci_ci_versions(args):
    """
    Checks that the jvmci versions used in various ci files agree.

    If the ci.hocon files use a -dev version, it allows the travis ones to use the previous version.
    For example, if ci.hocon uses jvmci-0.24-dev, travis may use either jvmci-0.24-dev or jvmci-0.23
    """
    version_pattern = re.compile(r'^(?!\s*#).*jvmci-(?P<version>\d*\.\d*)(?P<dev>-dev)?')

    def _grep_version(files, msg):
        version = None
        dev = None
        last = None
        linenr = 0
        for filename in files:
            for line in open(filename):
                m = version_pattern.search(line)
                if m:
                    new_version = m.group('version')
                    new_dev = bool(m.group('dev'))
                    if (version and version != new_version) or (dev is not None and dev != new_dev):
                        mx.abort(
                            os.linesep.join([
                                "Multiple JVMCI versions found in {0} files:".format(msg),
                                "  {0} in {1}:{2}:    {3}".format(version + ('-dev' if dev else ''), *last),
                                "  {0} in {1}:{2}:    {3}".format(new_version + ('-dev' if new_dev else ''), filename, linenr, line),
                            ]))
                    last = (filename, linenr, line.rstrip())
                    version = new_version
                    dev = new_dev
                linenr += 1
        if not version:
            mx.abort("No JVMCI version found in {0} files!".format(msg))
        return version, dev

    primary_suite = mx.primary_suite()
    hocon_version, hocon_dev = _grep_version(
        glob.glob(join(primary_suite.vc_dir, '*.hocon')) +
        glob.glob(join(primary_suite.dir, 'ci*.hocon')) +
        glob.glob(join(primary_suite.dir, 'ci*/*.hocon')), 'hocon')
    travis_version, travis_dev = _grep_version([join(primary_suite.vc_dir, '.travis.yml')], 'TravisCI')

    if hocon_version != travis_version or hocon_dev != travis_dev:
        versions_ok = False
        if not travis_dev and hocon_dev:
            next_travis_version = [int(a) for a in travis_version.split('.')]
            next_travis_version[-1] += 1
            next_travis_version_str = '.'.join((str(a) for a in next_travis_version))
            if next_travis_version_str == hocon_version:
                versions_ok = True
        if not versions_ok:
            mx.abort("Travis and ci.hocon JVMCI versions do not match: {0} vs. {1}".format(travis_version + ('-dev' if travis_dev else ''), hocon_version + ('-dev' if hocon_dev else '')))
    mx.log('JVMCI versions are ok!')


class UnitTestRun:
    def __init__(self, name, args, tags):
        self.name = name
        self.args = args
        self.tags = tags

    def run(self, suites, tasks, extraVMarguments=None):
        for suite in suites:
            with Task(self.name + ': hosted-product ' + suite, tasks, tags=self.tags) as t:
                if mx_gate.Task.verbose:
                    extra_args = ['--verbose', '--enable-timing']
                else:
                    extra_args = []
                if t: unittest(['--suite', suite, '--fail-fast'] + extra_args + self.args + _remove_empty_entries(extraVMarguments))

class BootstrapTest:
    def __init__(self, name, args, tags, suppress=None):
        self.name = name
        self.args = args
        self.suppress = suppress
        self.tags = tags
        if tags is not None and (type(tags) is not list or all(not isinstance(x, basestring) for x in tags)):
            mx.abort("Gate tag argument must be a list of strings, tag argument:" + str(tags))

    def run(self, tasks, extraVMarguments=None):
        with Task(self.name, tasks, tags=self.tags) as t:
            if t:
                if self.suppress:
                    out = mx.DuplicateSuppressingStream(self.suppress).write
                else:
                    out = None
                run_vm(self.args + ['-XX:+UseJVMCICompiler'] + _remove_empty_entries(extraVMarguments) + ['-XX:-TieredCompilation', '-XX:+BootstrapJVMCI', '-version'], out=out)

class GraalTags:
    bootstrap = ['bootstrap', 'fulltest']
    bootstraplite = ['bootstraplite', 'bootstrap', 'fulltest']
    bootstrapfullverify = ['bootstrapfullverify', 'fulltest']
    test = ['test', 'fulltest']
    benchmarktest = ['benchmarktest', 'fulltest']
    ctw = ['ctw', 'fulltest']
    doc = ['javadoc']

def _remove_empty_entries(a):
    """Removes empty entries. Return value is always a list."""
    if not a:
        return []
    return [x for x in a if x]

def _gate_java_benchmark(args, successRe):
    """
    Runs a Java benchmark and aborts if the benchmark process exits with a non-zero
    exit code or the `successRe` pattern is not in the output of the benchmark process.

    :param list args: the arguments to pass to the VM
    :param str successRe: a regular expression
    """
    out = mx.OutputCapture()
    try:
        run_java(args, out=mx.TeeOutputCapture(out), err=subprocess.STDOUT)
    finally:
        jvmErrorFile = re.search(r'(([A-Z]:|/).*[/\\]hs_err_pid[0-9]+\.log)', out.data)
        if jvmErrorFile:
            jvmErrorFile = jvmErrorFile.group()
            mx.log('Dumping ' + jvmErrorFile)
            with open(jvmErrorFile, 'rb') as fp:
                mx.log(fp.read())
            os.unlink(jvmErrorFile)

    if not re.search(successRe, out.data, re.MULTILINE):
        mx.abort('Could not find benchmark success pattern: ' + successRe)

def _is_batik_supported(jdk):
    """
    Determines if Batik runs on the given jdk. Batik's JPEGRegistryEntry contains a reference
    to TruncatedFileException, which is specific to the Sun/Oracle JDK. On a different JDK,
    this results in a NoClassDefFoundError: com/sun/image/codec/jpeg/TruncatedFileException
    """
    try:
        subprocess.check_output([jdk.javap, 'com.sun.image.codec.jpeg.TruncatedFileException'])
        return True
    except subprocess.CalledProcessError:
        mx.warn('Batik uses Sun internal class com.sun.image.codec.jpeg.TruncatedFileException which is not present in ' + jdk.home)
        return False

def _gate_dacapo(name, iterations, extraVMarguments=None, force_serial_gc=True, set_start_heap_size=True, threads=None):
    vmargs = ['-XX:+UseSerialGC'] if force_serial_gc else []
    if set_start_heap_size:
        vmargs += ['-Xms2g']
    vmargs += ['-XX:-UseCompressedOops', '-Djava.net.preferIPv4Stack=true', '-Dgraal.CompilationFailureAction=ExitVM'] + _remove_empty_entries(extraVMarguments)
    dacapoJar = mx.library('DACAPO').get_path(True)
    if name == 'batik' and not _is_batik_supported(jdk):
        return
    args = ['-n', str(iterations)]
    if threads is not None:
        args += ['-t', str(threads)]
    _gate_java_benchmark(vmargs + ['-jar', dacapoJar, name] + args, r'^===== DaCapo 9\.12 ([a-zA-Z0-9_]+) PASSED in ([0-9]+) msec =====')

def jdk_includes_corba(jdk):
    # corba has been removed since JDK11 (http://openjdk.java.net/jeps/320)
    return jdk.javaCompliance < '11'

def _gate_scala_dacapo(name, iterations, extraVMarguments=None):
    vmargs = ['-Xms2g', '-XX:+UseSerialGC', '-XX:-UseCompressedOops', '-Dgraal.CompilationFailureAction=ExitVM'] + _remove_empty_entries(extraVMarguments)
    if name == 'actors' and jdk.javaCompliance >= '9' and jdk_includes_corba(jdk):
        vmargs += ['--add-modules', 'java.corba']
    scalaDacapoJar = mx.library('DACAPO_SCALA').get_path(True)
    _gate_java_benchmark(vmargs + ['-jar', scalaDacapoJar, name, '-n', str(iterations)], r'^===== DaCapo 0\.1\.0(-SNAPSHOT)? ([a-zA-Z0-9_]+) PASSED in ([0-9]+) msec =====')


def jvmci_ci_version_gate_runner(tasks):
    # Check that travis and ci.hocon use the same JVMCI version
    with Task('JVMCI_CI_VersionSyncCheck', tasks, tags=[mx_gate.Tags.style]) as t:
        if t: verify_jvmci_ci_versions([])

def compiler_gate_runner(suites, unit_test_runs, bootstrap_tests, tasks, extraVMarguments=None):
    if jdk.javaCompliance >= '9':
        with Task('JDK_java_base_test', tasks, tags=['javabasetest']) as t:
            if t: java_base_unittest(_remove_empty_entries(extraVMarguments) + [])

    # Run unit tests in hosted mode
    for r in unit_test_runs:
        r.run(suites, tasks, ['-XX:-UseJVMCICompiler'] + _remove_empty_entries(extraVMarguments))

    # Run selected tests (initially those from GR-6581) under -Xcomp
    xcompTests = [
        'BlackholeDirectiveTest',
        'OpaqueDirectiveTest',
        'CompiledMethodTest',
        'ControlFlowAnchorDirectiveTest',
        'ConditionalElimination',
        'MarkUnsafeAccessTest',
        'PEAAssertionsTest',
        'MergeCanonicalizerTest',
        'ExplicitExceptionTest',
        'GuardedIntrinsicTest',
        'HashCodeTest',
        'ProfilingInfoTest',
        'GraalOSRLockTest'
    ]
    UnitTestRun('XcompUnitTests', [], tags=GraalTags.test).run(['compiler'], tasks, ['-Xcomp', '-XX:-UseJVMCICompiler'] + _remove_empty_entries(extraVMarguments) + xcompTests)

    # Ensure makegraaljdk works
    with Task('MakeGraalJDK', tasks, tags=GraalTags.test) as t:
        if t and isJDK8:
            try:
                makegraaljdk(['-a', 'graaljdk.tar', '-b', 'graaljdk'])
            finally:
                if exists('graaljdk'):
                    shutil.rmtree('graaljdk')
                if exists('graaljdk.tar'):
                    os.unlink('graaljdk.tar')

    # Run ctw against rt.jar on hosted
    with Task('CTW:hosted', tasks, tags=GraalTags.ctw) as t:
        if t:
            ctw([
                    '--ctwopts', 'Inline=false CompilationFailureAction=ExitVM', '-esa', '-XX:-UseJVMCICompiler', '-XX:+EnableJVMCI',
                    '-DCompileTheWorld.MultiThreaded=true', '-Dgraal.InlineDuringParsing=false', '-Dgraal.TrackNodeSourcePosition=true',
                    '-DCompileTheWorld.Verbose=false', '-XX:ReservedCodeCacheSize=300m',
                ], _remove_empty_entries(extraVMarguments))

    # bootstrap tests
    for b in bootstrap_tests:
        b.run(tasks, extraVMarguments)

    # run selected DaCapo benchmarks
    # DaCapo benchmarks that can run with system assertions enabled
    dacapos_with_sa = {
        'avrora':     1,
        'h2':         1,
        'jython':     2,
        'luindex':    1,
        'lusearch':   4,
        'xalan':      1,
    }
    for name, iterations in sorted(dacapos_with_sa.iteritems()):
        with Task('DaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Dgraal.TrackNodeSourcePosition=true', '-esa'])

    # DaCapo benchmarks for which system assertions cannot be enabled because of benchmark failures
    dacapos_without_sa = {
        'batik':      1,
        'fop':        8,
        'pmd':        1,
        'sunflow':    2,
    }
    for name, iterations in sorted(dacapos_without_sa.iteritems()):
        with Task('DaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler'])

    # run selected Scala DaCapo benchmarks
    # Scala DaCapo benchmarks that can run with system assertions enabled
    scala_dacapos_with_sa = {
        'apparat':    1,
        'factorie':   1,
        'kiama':      4,
        'scalac':     1,
        'scaladoc':   1,
        'scalap':     1,
        'scalariform':1,
        'scalatest':  1,
        'scalaxb':    1,
        'tmt':        1
    }
    for name, iterations in sorted(scala_dacapos_with_sa.iteritems()):
        with Task('ScalaDaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_scala_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Dgraal.TrackNodeSourcePosition=true', '-esa'])

    # Scala DaCapo benchmarks for which system assertions cannot be enabled because of benchmark failures
    scala_dacapos_without_sa = {
        'actors':     1,
    }
    if not jdk_includes_corba(jdk):
        mx.warn('Removing scaladacapo:actors from benchmarks because corba has been removed since JDK11 (http://openjdk.java.net/jeps/320)')
        del scala_dacapos_without_sa['actors']

    for name, iterations in sorted(scala_dacapos_without_sa.iteritems()):
        with Task('ScalaDaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_scala_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler'])

    # ensure -Xbatch still works
    with Task('DaCapo_pmd:BatchMode', tasks, tags=GraalTags.test) as t:
        if t: _gate_dacapo('pmd', 1, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xbatch'])

    # ensure benchmark counters still work
    if mx.get_arch() != 'aarch64': # GR-8364 Exclude benchmark counters on AArch64
        with Task('DaCapo_pmd:BenchmarkCounters', tasks, tags=GraalTags.test) as t:
            if t: _gate_dacapo('pmd', 1, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Dgraal.LIRProfileMoves=true', '-Dgraal.GenericDynamicCounters=true', '-XX:JVMCICounterSize=10'])

    # ensure -Xcomp still works
    with Task('XCompMode:product', tasks, tags=GraalTags.test) as t:
        if t: run_vm(_remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xcomp', '-version'])

    if isJDK8:
        # temporarily isolate those test (GR-10990)
        cms = ['cms']
        # ensure CMS still works
        with Task('DaCapo_pmd:CMS', tasks, tags=cms) as t:
            if t: _gate_dacapo('pmd', 4, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xmx256M', '-XX:+UseConcMarkSweepGC'], threads=4, force_serial_gc=False, set_start_heap_size=False)

        # ensure CMSIncrementalMode still works
        with Task('DaCapo_pmd:CMSIncrementalMode', tasks, tags=cms) as t:
            if t: _gate_dacapo('pmd', 4, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xmx256M', '-XX:+UseConcMarkSweepGC', '-XX:+CMSIncrementalMode'], threads=4, force_serial_gc=False, set_start_heap_size=False)

    with Task('Javadoc', tasks, tags=GraalTags.doc) as t:
        # metadata package was deprecated, exclude it
        if t: mx.javadoc(['--exclude-packages', 'com.oracle.truffle.api.metadata,com.oracle.truffle.api.interop.java,com.oracle.truffle.api.vm'], quietForNoPackages=True)

graal_unit_test_runs = [
    UnitTestRun('UnitTests', [], tags=GraalTags.test),
]

_registers = {
    'sparcv9': 'o0,o1,o2,o3,f8,f9,d32,d34',
    'amd64': 'rbx,r11,r10,r14,xmm3,xmm11,xmm14',
    'aarch64': 'r0,r1,r2,r3,r4,v0,v1,v2,v3'
}
if mx.get_arch() not in _registers:
    mx.warn('No registers for register pressure tests are defined for architecture ' + mx.get_arch())

_defaultFlags = ['-Dgraal.CompilationWatchDogStartDelay=60.0D']
_assertionFlags = ['-esa', '-Dgraal.DetailedAsserts=true']
_graalErrorFlags = ['-Dgraal.CompilationFailureAction=ExitVM']
_graalEconomyFlags = ['-Dgraal.CompilerConfiguration=economy']
_verificationFlags = ['-Dgraal.VerifyGraalGraphs=true', '-Dgraal.VerifyGraalGraphEdges=true', '-Dgraal.VerifyGraalPhasesSize=true', '-Dgraal.VerifyPhases=true']
_coopFlags = ['-XX:-UseCompressedOops']
_gcVerificationFlags = ['-XX:+UnlockDiagnosticVMOptions', '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC']
_g1VerificationFlags = ['-XX:-UseSerialGC', '-XX:+UseG1GC']
_exceptionFlags = ['-Dgraal.StressInvokeWithExceptionNode=true']
_registerPressureFlags = ['-Dgraal.RegisterPressure=' + _registers[mx.get_arch()]]
_immutableCodeFlags = ['-Dgraal.ImmutableCode=true']

graal_bootstrap_tests = [
    BootstrapTest('BootstrapWithSystemAssertionsFullVerify', _defaultFlags + _assertionFlags + _verificationFlags + _graalErrorFlags, tags=GraalTags.bootstrapfullverify),
    BootstrapTest('BootstrapWithSystemAssertions', _defaultFlags + _assertionFlags + _graalErrorFlags, tags=GraalTags.bootstraplite),
    BootstrapTest('BootstrapWithSystemAssertionsNoCoop', _defaultFlags + _assertionFlags + _coopFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithGCVerification', _defaultFlags + _gcVerificationFlags + _graalErrorFlags, tags=GraalTags.bootstrap, suppress=['VerifyAfterGC:', 'VerifyBeforeGC:']),
    BootstrapTest('BootstrapWithG1GCVerification', _defaultFlags + _g1VerificationFlags + _gcVerificationFlags + _graalErrorFlags, tags=GraalTags.bootstrap, suppress=['VerifyAfterGC:', 'VerifyBeforeGC:']),
    BootstrapTest('BootstrapWithSystemAssertionsEconomy', _defaultFlags + _assertionFlags + _graalEconomyFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsExceptionEdges', _defaultFlags + _assertionFlags + _exceptionFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsRegisterPressure', _defaultFlags + _assertionFlags + _registerPressureFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsImmutableCode', _defaultFlags + _assertionFlags + _immutableCodeFlags + ['-Dgraal.VerifyPhases=true'] + _graalErrorFlags, tags=GraalTags.bootstrap)
]

def _graal_gate_runner(args, tasks):
    compiler_gate_runner(['compiler', 'truffle'], graal_unit_test_runs, graal_bootstrap_tests, tasks, args.extra_vm_argument)
    jvmci_ci_version_gate_runner(tasks)
    mx_jaotc.jaotc_gate_runner(tasks)

class ShellEscapedStringAction(argparse.Action):
    """Turns a shell-escaped string into a list of arguments.
       Note that it appends the result to the destination.
    """
    def __init__(self, option_strings, nargs=None, **kwargs):
        if nargs is not None:
            raise ValueError("nargs not allowed")
        super(ShellEscapedStringAction, self).__init__(option_strings, **kwargs)

    def __call__(self, parser, namespace, values, option_string=None):
        # do not override existing values
        old_values = getattr(namespace, self.dest)
        setattr(namespace, self.dest, (old_values if old_values else []) + shlex.split(values))

mx_gate.add_gate_runner(_suite, _graal_gate_runner)
mx_gate.add_gate_argument('--extra-vm-argument', action=ShellEscapedStringAction, help='add extra vm arguments to gate tasks if applicable')

def _unittest_vm_launcher(vmArgs, mainClass, mainClassArgs):
    run_vm(vmArgs + [mainClass] + mainClassArgs)

def _unittest_config_participant(config):
    vmArgs, mainClass, mainClassArgs = config
    cpIndex, cp = mx.find_classpath_arg(vmArgs)
    if cp:
        cp = _uniqify(cp.split(os.pathsep))
        if isJDK8:
            # Remove entries from class path that are in Graal or on the boot class path
            redundantClasspathEntries = set()
            for dist in [entry.dist() for entry in _jvmci_classpath]:
                redundantClasspathEntries.update((d.output_dir() for d in dist.archived_deps() if d.isJavaProject()))
                redundantClasspathEntries.add(dist.path)
            cp = os.pathsep.join([e for e in cp if e not in redundantClasspathEntries])
            vmArgs[cpIndex] = cp
        else:
            redundantClasspathEntries = set()
            for dist in [entry.dist() for entry in _jvmci_classpath] + _bootclasspath_appends:
                redundantClasspathEntries.update(mx.classpath(dist, preferProjects=False, jdk=jdk).split(os.pathsep))
                redundantClasspathEntries.update(mx.classpath(dist, preferProjects=True, jdk=jdk).split(os.pathsep))
                if hasattr(dist, 'overlaps'):
                    for o in dist.overlaps:
                        path = mx.distribution(o).classpath_repr()
                        if path:
                            redundantClasspathEntries.add(path)

            # Remove entries from the class path that are in the deployed modules
            cp = [classpathEntry for classpathEntry in cp if classpathEntry not in redundantClasspathEntries]
            vmArgs[cpIndex] = os.pathsep.join(cp)

            # JVMCI is dynamically exported to Graal when JVMCI is initialized. This is too late
            # for the junit harness which uses reflection to find @Test methods. In addition, the
            # tests widely use JVMCI classes so JVMCI needs to also export all its packages to
            # ALL-UNNAMED.
            jvmci = [m for m in jdk.get_modules() if m.name == 'jdk.internal.vm.ci'][0]
            vmArgs.extend(['--add-exports=' + jvmci.name + '/' + p + '=jdk.internal.vm.compiler,ALL-UNNAMED' for p in jvmci.packages])

    vmArgs.append('-Dgraal.TrackNodeSourcePosition=true')
    vmArgs.append('-esa')

    if isJDK8:
        # Run the VM in a mode where application/test classes can
        # access JVMCI loaded classes.
        vmArgs.append('-XX:-UseJVMCIClassLoader')

    return (vmArgs, mainClass, mainClassArgs)

mx_unittest.add_config_participant(_unittest_config_participant)
mx_unittest.set_vm_launcher('JDK VM launcher', _unittest_vm_launcher, jdk)

def _uniqify(alist):
    """
    Processes given list to remove all duplicate entries, preserving only the first unique instance for each entry.

    :param list alist: the list to process
    :return: `alist` with all duplicates removed
    """
    seen = set()
    return [e for e in alist if e not in seen and seen.add(e) is None]

def _parseVmArgs(args, addDefaultArgs=True):
    args = mx.expand_project_in_args(args, insitu=False)

    argsPrefix = []
    jacocoArgs = mx_gate.get_jacoco_agent_args()
    if jacocoArgs:
        argsPrefix.extend(jacocoArgs)

    # add default graal.options.file
    options_file = join(mx.primary_suite().dir, 'graal.options')
    if exists(options_file):
        argsPrefix.append('-Dgraal.options.file=' + options_file)

    if isJDK8:
        argsPrefix.append('-Djvmci.class.path.append=' + os.pathsep.join((e.get_path() for e in _jvmci_classpath)))
        argsPrefix.append('-Xbootclasspath/a:' + os.pathsep.join([dep.classpath_repr() for dep in _bootclasspath_appends]))
    else:
        deployedDists = [entry.dist() for entry in _jvmci_classpath] + \
                        [e for e in _bootclasspath_appends if e.isJARDistribution()]
        deployedModules = [as_java_module(dist, jdk) for dist in deployedDists]

        # Set or update module path to include Graal and its dependencies as modules
        jdkModuleNames = frozenset([m.name for m in jdk.get_modules()])
        graalModulepath = []
        graalUpgrademodulepath = []

        def _addToModulepath(modules):
            for m in modules:
                if m.jarpath:
                    modulepath = graalModulepath if m.name not in jdkModuleNames else graalUpgrademodulepath
                    if m not in modulepath:
                        modulepath.append(m)

        for deployedModule in deployedModules:
            _addToModulepath(deployedModule.modulepath)
            _addToModulepath([deployedModule])

        # Extend or set --module-path argument
        mpUpdated = False
        for mpIndex in range(len(args)):
            assert not args[mpIndex].startswith('--upgrade-module-path')
            if args[mpIndex] == '--module-path':
                assert mpIndex + 1 < len(args), 'VM option ' + args[mpIndex] + ' requires an argument'
                args[mpIndex + 1] = os.pathsep.join(_uniqify(args[mpIndex + 1].split(os.pathsep) + [m.jarpath for m in graalModulepath]))
                mpUpdated = True
                break
            elif args[mpIndex].startswith('--module-path='):
                mp = args[mpIndex][len('--module-path='):]
                args[mpIndex] = '--module-path=' + os.pathsep.join(_uniqify(mp.split(os.pathsep) + [m.jarpath for m in graalModulepath]))
                mpUpdated = True
                break
        if not mpUpdated:
            argsPrefix.append('--module-path=' + os.pathsep.join([m.jarpath for m in graalModulepath]))

        if graalUpgrademodulepath:
            argsPrefix.append('--upgrade-module-path=' + os.pathsep.join([m.jarpath for m in graalUpgrademodulepath]))

    if '-version' in args:
        ignoredArgs = args[args.index('-version') + 1:]
        if len(ignoredArgs) > 0:
            mx.log("Warning: The following options will be ignored by the VM because they come after the '-version' argument: " + ' '.join(ignoredArgs))

    return jdk.processArgs(argsPrefix + args, addDefaultArgs=addDefaultArgs)

def _check_bootstrap_config(args):
    """
    Issues a warning if `args` denote -XX:+BootstrapJVMCI but -XX:-UseJVMCICompiler.
    """
    bootstrap = False
    useJVMCICompiler = False
    for arg in args:
        if arg == '-XX:+BootstrapJVMCI':
            bootstrap = True
        elif arg == '-XX:+UseJVMCICompiler':
            useJVMCICompiler = True
    if bootstrap and not useJVMCICompiler:
        mx.warn('-XX:+BootstrapJVMCI is ignored since -XX:+UseJVMCICompiler is not enabled')

class StdoutUnstripping:
    """
    A context manager for logging and unstripping the console output for a subprocess
    execution. The logging and unstripping is only attempted if stdout and stderr
    for the execution were not already being redirected and existing *.map files
    were detected in the arguments to the execution.
    """
    def __init__(self, args, out, err, mapFiles=None):
        self.args = args
        self.out = out
        self.err = err
        self.capture = None
        self.mapFiles = mapFiles

    def __enter__(self):
        if mx.get_opts().strip_jars and self.out is None and (self.err is None or self.err == subprocess.STDOUT):
            delims = re.compile('[' + os.pathsep + '=]')
            for a in self.args:
                for e in delims.split(a):
                    candidate = e + '.map'
                    if exists(candidate):
                        if self.mapFiles is None:
                            self.mapFiles = set()
                        self.mapFiles.add(candidate)
            self.capture = mx.OutputCapture()
            self.out = mx.TeeOutputCapture(self.capture)
            self.err = self.out
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self.mapFiles:
            try:
                with tempfile.NamedTemporaryFile() as inputFile:
                    with tempfile.NamedTemporaryFile() as mapFile:
                        if len(self.capture.data) != 0:
                            inputFile.write(self.capture.data)
                            inputFile.flush()
                            for e in self.mapFiles:
                                with open(e, 'r') as m:
                                    shutil.copyfileobj(m, mapFile)
                                    mapFile.flush()
                            retraceOut = mx.OutputCapture()
                            proguard_cp = mx.classpath(['PROGUARD_RETRACE', 'PROGUARD'])
                            mx.run([jdk.java, '-cp', proguard_cp, 'proguard.retrace.ReTrace', mapFile.name, inputFile.name], out=retraceOut)
                            if self.capture.data != retraceOut.data:
                                mx.log('>>>> BEGIN UNSTRIPPED OUTPUT')
                                mx.log(retraceOut.data)
                                mx.log('<<<< END UNSTRIPPED OUTPUT')
            except BaseException as e:
                mx.log('Error unstripping output from VM execution with stripped jars: ' + str(e))
        return None

def run_java(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, env=None, addDefaultArgs=True):
    args = ['-XX:+UnlockExperimentalVMOptions', '-XX:+EnableJVMCI'] + _parseVmArgs(args, addDefaultArgs=addDefaultArgs)
    _check_bootstrap_config(args)
    cmd = get_vm_prefix() + [jdk.java] + ['-server'] + args
    with StdoutUnstripping(args, out, err) as u:
        return mx.run(cmd, nonZeroIsFatal=nonZeroIsFatal, out=u.out, err=u.err, cwd=cwd, env=env)

_JVMCI_JDK_TAG = 'jvmci'

class GraalJVMCI9JDKConfig(mx.JDKConfig):
    """
    A JDKConfig that configures Graal as the JVMCI compiler.
    """
    def __init__(self):
        mx.JDKConfig.__init__(self, jdk.home, tag=_JVMCI_JDK_TAG)

    def run_java(self, args, **kwArgs):
        return run_java(args, **kwArgs)

class GraalJDKFactory(mx.JDKFactory):
    def getJDKConfig(self):
        return GraalJVMCI9JDKConfig()

    def description(self):
        return "JVMCI JDK with Graal"

mx.addJDKFactory(_JVMCI_JDK_TAG, mx.JavaCompliance('9'), GraalJDKFactory())

def run_vm(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, debugLevel=None, vmbuild=None):
    """run a Java program by executing the java executable in a JVMCI JDK"""
    return run_java(args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd, timeout=timeout)

class GraalArchiveParticipant:

    providersRE = re.compile(r'(?:META-INF/versions/([1-9][0-9]*)/)?META-INF/providers/(.+)')
    def __init__(self, dist, isTest=False):
        self.dist = dist
        self.isTest = isTest

    def __opened__(self, arc, srcArc, services):
        self.services = services
        self.versionedServices = {}
        self.arc = arc

    def __add__(self, arcname, contents):
        m = GraalArchiveParticipant.providersRE.match(arcname)
        if m:
            if self.isTest:
                # The test distributions must not have their @ServiceProvider
                # generated providers converted to real services otherwise
                # bad things can happen such as InvocationPlugins being registered twice.
                pass
            else:
                provider = m.group(2)
                for service in contents.strip().split(os.linesep):
                    assert service
                    version = m.group(1)
                    if version is None:
                        # Non-versioned service
                        self.services.setdefault(service, []).append(provider)
                    else:
                        # Versioned service
                        services = self.versionedServices.setdefault(version, {})
                        services.setdefault(service, []).append(provider)
            return True
        elif arcname.endswith('_OptionDescriptors.class'):
            if self.isTest:
                mx.warn('@Option defined in test code will be ignored: ' + arcname)
            else:
                # Need to create service files for the providers of the
                # jdk.internal.vm.ci.options.Options service created by
                # jdk.internal.vm.ci.options.processor.OptionProcessor.
                provider = arcname[:-len('.class'):].replace('/', '.')
                self.services.setdefault('org.graalvm.compiler.options.OptionDescriptors', []).append(provider)
        return False

    def __addsrc__(self, arcname, contents):
        return False

    def __closing__(self):
        for version, services in self.versionedServices.iteritems():
            for service, providers in services.iteritems():
                arcname = 'META-INF/versions/{}/META-INF/services/{}'.format(version, service)
                # Convert providers to a set before printing to remove duplicates
                self.arc.zf.writestr(arcname, '\n'.join(frozenset(providers)) + '\n')

mx.add_argument('--vmprefix', action='store', dest='vm_prefix', help='prefix for running the VM (e.g. "gdb --args")', metavar='<prefix>')
mx.add_argument('--gdb', action='store_const', const='gdb --args', dest='vm_prefix', help='alias for --vmprefix "gdb --args"')
mx.add_argument('--lldb', action='store_const', const='lldb --', dest='vm_prefix', help='alias for --vmprefix "lldb --"')

def sl(args):
    """run an SL program"""
    mx.get_opts().jdk = 'jvmci'
    mx_truffle.sl(args)

def java_base_unittest(args):
    """tests whether graal compiler runs on a JDK with a minimal set of modules"""
    jlink = mx.exe_suffix(join(jdk.home, 'bin', 'jlink'))
    if not exists(jlink):
        raise mx.JDKConfigException('jlink tool does not exist: ' + jlink)
    basejdk_dir = join(_suite.get_output_root(), 'jdkbase')
    basemodules = 'java.base,jdk.internal.vm.ci,jdk.unsupported'
    if exists(basejdk_dir):
        shutil.rmtree(basejdk_dir)
    mx.run([jlink, '--output', basejdk_dir, '--add-modules', basemodules, '--module-path', join(jdk.home, 'jmods')])
    jdwp = mx.add_lib_suffix(mx.add_lib_prefix('jdwp'))
    shutil.copy(join(jdk.home, 'lib', jdwp), join(basejdk_dir, 'lib', jdwp))
    dt_socket = mx.add_lib_suffix(mx.add_lib_prefix('dt_socket'))
    shutil.copy(join(jdk.home, 'lib', dt_socket), join(basejdk_dir, 'lib', dt_socket))

    if not args:
        args = []

    fakeJavac = join(basejdk_dir, 'bin', 'javac')
    open(fakeJavac, 'a').close()

    basejdk = mx.JDKConfig(basejdk_dir)
    savedJava = jdk.java
    saved_jvmci_classpath = list(_jvmci_classpath)
    try:
        # Remove GRAAL_MANAGEMENT from the module path as it
        # depends on the java.management module which is not in
        # the limited module set
        _jvmci_classpath[:] = [e for e in _jvmci_classpath if e._name != 'GRAAL_MANAGEMENT']

        jdk.java = basejdk.java
        if mx_gate.Task.verbose:
            extra_args = ['--verbose', '--enable-timing']
        else:
            extra_args = []
        mx_unittest.unittest(['--suite', 'compiler', '--fail-fast'] + extra_args + args)
    finally:
        jdk.java = savedJava
        _jvmci_classpath[:] = saved_jvmci_classpath

def microbench(*args):
    mx.abort("`mx microbench` is deprecated.\n" +
             "Use `mx benchmark jmh-whitebox:*` and `mx benchmark jmh-dist:*` instead!")

def javadoc(args):
    # metadata package was deprecated, exclude it
    if not '--exclude-packages' in args:
        args.append('--exclude-packages')
        args.append('com.oracle.truffle.api.metadata')
    mx.javadoc(args, quietForNoPackages=True)

def create_archive(srcdir, arcpath, prefix):
    """
    Creates a compressed archive of a given directory.

    :param str srcdir: directory to archive
    :param str arcpath: path of file to contain the archive. The extension of `path`
           specifies the type of archive to create
    :param str prefix: the prefix to apply to each entry in the archive
    """

    def _taradd(arc, filename, arcname):
        arc.add(name=f, arcname=arcname, recursive=False)
    def _zipadd(arc, filename, arcname):
        arc.write(filename, arcname)

    if arcpath.endswith('.zip'):
        arc = zipfile.ZipFile(arcpath, 'w', zipfile.ZIP_DEFLATED)
        add = _zipadd
    elif arcpath.endswith('.tar'):
        arc = tarfile.open(arcpath, 'w')
        add = _taradd
    elif arcpath.endswith('.tgz') or arcpath.endswith('.tar.gz'):
        arc = tarfile.open(arcpath, 'w:gz')
        add = _taradd
    else:
        mx.abort('unsupported archive kind: ' + arcpath)

    for root, _, filenames in os.walk(srcdir):
        for name in filenames:
            f = join(root, name)
            # Make sure files in the image are readable by everyone
            file_mode = os.stat(f).st_mode
            mode = stat.S_IRGRP | stat.S_IROTH | file_mode
            if isdir(f) or (file_mode & stat.S_IXUSR):
                mode = mode | stat.S_IXGRP | stat.S_IXOTH
            os.chmod(f, mode)
            arcname = prefix + os.path.relpath(f, srcdir)
            add(arc, f, arcname)
    arc.close()

def makegraaljdk(args):
    """make a JDK with Graal as the default top level JIT"""
    parser = ArgumentParser(prog='mx makegraaljdk')
    parser.add_argument('-f', '--force', action='store_true', help='overwrite existing GraalJDK')
    parser.add_argument('-a', '--archive', action='store', help='name of archive to create', metavar='<path>')
    parser.add_argument('-b', '--bootstrap', action='store_true', help='execute a bootstrap of the created GraalJDK')
    parser.add_argument('dest', help='destination directory for GraalJDK', metavar='<path>')
    args = parser.parse_args(args)
    if isJDK8:
        dstJdk = os.path.abspath(args.dest)
        srcJdk = jdk.home
        if exists(dstJdk):
            if args.force:
                shutil.rmtree(dstJdk)
            else:
                mx.abort('Use --force to overwrite existing directory ' + dstJdk)
        mx.log('Creating {} from {}'.format(dstJdk, srcJdk))
        shutil.copytree(srcJdk, dstJdk)

        bootDir = mx.ensure_dir_exists(join(dstJdk, 'jre', 'lib', 'boot'))
        truffleDir = mx.ensure_dir_exists(join(dstJdk, 'jre', 'lib', 'truffle'))
        jvmciDir = join(dstJdk, 'jre', 'lib', 'jvmci')
        assert exists(jvmciDir), jvmciDir + ' does not exist'

        if mx.get_os() == 'darwin':
            jvmlibDir = join(dstJdk, 'jre', 'lib', 'server')
        elif mx.get_os() == 'windows':
            jvmlibDir = join(dstJdk, 'jre', 'bin', 'server')
        else:
            jvmlibDir = join(dstJdk, 'jre', 'lib', mx.get_arch(), 'server')
        jvmlib = join(jvmlibDir, mx.add_lib_prefix(mx.add_lib_suffix('jvm')))
        assert exists(jvmlib), jvmlib + ' does not exist'

        with open(join(jvmciDir, 'compiler-name'), 'w') as fp:
            print >> fp, 'graal'
        vmName = 'Graal'
        mapFiles = set()
        for e in _jvmci_classpath:
            src = basename(e.get_path())
            mx.log('Copying {} to {}'.format(e.get_path(), jvmciDir))
            candidate = e.get_path() + '.map'
            if exists(candidate):
                mapFiles.add(candidate)
            with open(join(dstJdk, 'release'), 'a') as fp:
                d = e.dist()
                s = d.suite
                print >> fp, '{}={}'.format(d.name, s.vc.parent(s.dir))
                vmName = vmName + ':' + s.name + '_' + s.version()
            shutil.copyfile(e.get_path(), join(jvmciDir, src))
        for e in _bootclasspath_appends:
            src = basename(e.classpath_repr())
            if e.suite.name == 'truffle':
                dstDir = truffleDir
            else:
                dstDir = bootDir
            mx.log('Copying {} to {}'.format(e.classpath_repr(), dstDir))
            candidate = e.classpath_repr() + '.map'
            if exists(candidate):
                mapFiles.add(candidate)

            with open(join(dstJdk, 'release'), 'a') as fp:
                s = e.suite
                print >> fp, '{}={}'.format(e.name, s.vc.parent(s.dir))
            shutil.copyfile(e.classpath_repr(), join(dstDir, src))

        out = mx.LinesOutputCapture()
        mx.run([jdk.java, '-version'], err=out)
        line = None
        pattern = re.compile(r'(.* )(?:Server|Graal) VM \(build.*')
        for line in out.lines:
            m = pattern.match(line)
            if m:
                with open(join(jvmlibDir, 'vm.properties'), 'w') as fp:
                    # Modify VM name in `java -version` to be Graal along
                    # with a suffix denoting the commit of each Graal jar.
                    # For example:
                    # Java HotSpot(TM) 64-Bit Graal:compiler_88847fb25d1a62977a178331a5e78fa5f8fcbb1a (build 25.71-b01-internal-jvmci-0.34, mixed mode)
                    print >> fp, 'name=' + m.group(1) + vmName
                line = True
                break
        if line is not True:
            mx.abort('Could not find "{}" in output of `java -version`:\n{}'.format(pattern.pattern, os.linesep.join(out.lines)))

        exe = join(dstJdk, 'bin', mx.exe_suffix('java'))
        if args.bootstrap:
            with StdoutUnstripping(args=[], out=None, err=None, mapFiles=mapFiles) as u:
                mx.run([exe, '-XX:+BootstrapJVMCI', '-version'], out=u.out, err=u.err)
        if args.archive:
            mx.log('Archiving {}'.format(args.archive))
            create_archive(dstJdk, args.archive, basename(args.dest) + '/')
    else:
        mx.abort('Can only make GraalJDK for JDK 8 currently')

def _find_version_base_project(versioned_project):
    extended_packages = versioned_project.extended_java_packages()
    if not extended_packages:
        mx.abort('Project with a multiReleaseJarVersion attribute must have sources in a package defined by project without multiReleaseJarVersion attribute', context=versioned_project)
    base_project = None
    base_package = None
    for extended_package in extended_packages:
        for p in mx.projects():
            if versioned_project != p and p.isJavaProject() and not hasattr(p, 'multiReleaseJarVersion'):
                if extended_package in p.defined_java_packages():
                    if base_project is None:
                        base_project = p
                        base_package = extended_package
                    else:
                        if base_project != p:
                            mx.abort('Multi-release jar versioned project {} must extend packages from exactly one project but extends {} from {} and {} from {}'.format(versioned_project, extended_package, p, base_project, base_package))
    if not base_project:
        mx.abort('Multi-release jar versioned project {} must extend package(s) from another project'.format(versioned_project))
    return base_project

SuiteJDKInfo = namedtuple('SuiteJDKInfo', 'name includes excludes')
GraalJDKModule = namedtuple('GraalJDKModule', 'name suites')

def updategraalinopenjdk(args):
    """updates the Graal sources in OpenJDK"""
    parser = ArgumentParser(prog='mx updategraalinopenjdk')
    parser.add_argument('--pretty', help='value for --pretty when logging the changes since the last JDK* tag')
    parser.add_argument('jdkrepo', help='path to the local OpenJDK repo')
    parser.add_argument('version', type=int, help='Java version of the OpenJDK repo')

    args = parser.parse_args(args)

    if jdk.javaCompliance.value < args.version:
        mx.abort('JAVA_HOME/--java-home must be Java version {} or greater: {}'.format(args.version, jdk))

    graal_modules = [
        GraalJDKModule('jdk.internal.vm.compiler',
            [SuiteJDKInfo('compiler', ['org.graalvm'], ['truffle', 'management']),
             SuiteJDKInfo('sdk', ['org.graalvm.collections', 'org.graalvm.word'], [])]),
        GraalJDKModule('jdk.internal.vm.compiler.management',
            [SuiteJDKInfo('compiler', ['org.graalvm.compiler.hotspot.management'], [])]),
        GraalJDKModule('jdk.aot',
            [SuiteJDKInfo('compiler', ['jdk.tools.jaotc'], [])]),
    ]

    package_renamings = {
        'org.graalvm.collections' : 'jdk.internal.vm.compiler.collections',
        'org.graalvm.word'        : 'jdk.internal.vm.compiler.word'
    }

    replacements = {
        'published by the Free Software Foundation.  Oracle designates this\n * particular file as subject to the "Classpath" exception as provided\n * by Oracle in the LICENSE file that accompanied this code.' : 'published by the Free Software Foundation.'
    }

    blacklist = ['"Classpath" exception']

    jdkrepo = args.jdkrepo

    for m in graal_modules:
        m_src_dir = join(jdkrepo, 'src', m.name)
        if not exists(m_src_dir):
            mx.abort(jdkrepo + ' does not look like a JDK repo - ' + m_src_dir + ' does not exist')

    def run_output(args, cwd=None):
        out = mx.OutputCapture()
        mx.run(args, cwd=cwd, out=out, err=out)
        return out.data

    for m in graal_modules:
        m_src_dir = join('src', m.name)
        mx.log('Checking ' + m_src_dir)
        out = run_output(['hg', 'status', m_src_dir], cwd=jdkrepo)
        if out:
            mx.abort(jdkrepo + ' is not "hg clean":' + '\n' + out[:min(200, len(out))] + '...')

    for dirpath, _, filenames in os.walk(join(jdkrepo, 'make')):
        for filename in filenames:
            if filename.endswith('.gmk'):
                filepath = join(dirpath, filename)
                with open(filepath) as fp:
                    contents = fp.read()
                new_contents = contents
                for old_name, new_name in package_renamings.iteritems():
                    new_contents = new_contents.replace(old_name, new_name)
                if new_contents != contents:
                    with open(filepath, 'w') as fp:
                        fp.write(new_contents)
                        mx.log('  updated ' + filepath)

    copied_source_dirs = []
    for m in graal_modules:
        classes_dir = join(jdkrepo, 'src', m.name, 'share', 'classes')
        for info in m.suites:
            mx.log('Processing ' + m.name + ':' + info.name)
            for e in os.listdir(classes_dir):
                if any(inc in e for inc in info.includes) and not any(ex in e for ex in info.excludes):
                    project_dir = join(classes_dir, e)
                    shutil.rmtree(project_dir)
                    mx.log('  removed ' + project_dir)
            suite = mx.suite(info.name)

            worklist = []
            for p in [e for e in suite.projects if e.isJavaProject()]:
                if any(inc in p.name for inc in info.includes) and not any(ex in p.name for ex in info.excludes):
                    assert len(p.source_dirs()) == 1, p
                    version = 0
                    new_project_name = p.name
                    if hasattr(p, 'multiReleaseJarVersion'):
                        version = int(getattr(p, 'multiReleaseJarVersion'))
                        if version <= args.version:
                            base_project = _find_version_base_project(p)
                            new_project_name = base_project.name
                        else:
                            continue

                    for old_name, new_name in package_renamings.iteritems():
                        if new_project_name.startswith(old_name):
                            new_project_name = new_project_name.replace(old_name, new_name)

                    source_dir = p.source_dirs()[0]
                    target_dir = join(classes_dir, new_project_name, 'src')
                    copied_source_dirs.append(source_dir)

                    workitem = (version, p, source_dir, target_dir)
                    worklist.append(workitem)

            # Ensure versioned resources are copied in the right order
            # such that higher versions override lower versions.
            worklist = sorted(worklist)

            for version, p, source_dir, target_dir in worklist:
                mx.log('  copying: ' + source_dir)
                mx.log('       to: ' + target_dir)
                for dirpath, _, filenames in os.walk(source_dir):
                    for filename in filenames:
                        src_file = join(dirpath, filename)
                        dst_file = join(target_dir, os.path.relpath(src_file, source_dir))
                        with open(src_file) as fp:
                            contents = fp.read()
                        old_line_count = len(contents.split('\n'))
                        if filename.endswith('.java'):
                            for old_name, new_name in package_renamings.iteritems():
                                old_name_as_dir = old_name.replace('.', os.sep)
                                if old_name_as_dir in src_file:
                                    new_name_as_dir = new_name.replace('.', os.sep)
                                    dst = src_file.replace(old_name_as_dir, new_name_as_dir)
                                    dst_file = join(target_dir, os.path.relpath(dst, source_dir))
                                contents = contents.replace(old_name, new_name)
                            for old_line, new_line in replacements.iteritems():
                                contents = contents.replace(old_line, new_line)
                            new_line_count = len(contents.split('\n'))
                            if new_line_count > old_line_count:
                                mx.abort('Pattern replacement caused line count to grow from {} to {} in {}'.format(old_line_count, new_line_count, src_file))
                            else:
                                if new_line_count < old_line_count:
                                    contents = contents.replace('\npackage ', '\n' * (old_line_count - new_line_count) + '\npackage ')
                            new_line_count = len(contents.split('\n'))
                            if new_line_count != old_line_count:
                                mx.abort('Unable to correct line count for {}'.format(src_file))
                            for forbidden in blacklist:
                                if forbidden in contents:
                                    mx.abort('Found blacklisted pattern \'{}\' in {}'.format(forbidden, src_file))
                        dst_dir = os.path.dirname(dst_file)
                        if not exists(dst_dir):
                            os.makedirs(dst_dir)
                        with open(dst_file, 'w') as fp:
                            fp.write(contents)
    mx.log('Adding new files to HG...')
    overwritten = ''
    for m in graal_modules:
        m_src_dir = join('src', m.name)
        out = run_output(['hg', 'log', '-r', 'last(keyword("Update Graal"))', '--template', '{rev}', m_src_dir], cwd=jdkrepo)
        last_graal_update = out.strip()
        if last_graal_update:
            overwritten += run_output(['hg', 'diff', '-r', last_graal_update, '-r', 'tip', m_src_dir], cwd=jdkrepo)
        mx.run(['hg', 'add', m_src_dir], cwd=jdkrepo)
    mx.log('Removing old files from HG...')
    for m in graal_modules:
        m_src_dir = join('src', m.name)
        out = run_output(['hg', 'status', '-dn', m_src_dir], cwd=jdkrepo)
        if out:
            mx.run(['hg', 'rm'] + out.split(), cwd=jdkrepo)

    out = run_output(['git', 'tag', '-l', 'JDK-*'], cwd=_suite.vc_dir)
    last_jdk_tag = sorted(out.split(), reverse=True)[0]

    pretty = args.pretty or 'format:%h %ad %>(20) %an %s'
    out = run_output(['git', '--no-pager', 'log', '--merges', '--abbrev-commit', '--pretty=' + pretty, '--first-parent', '-r', last_jdk_tag + '..HEAD'] +
            copied_source_dirs, cwd=_suite.vc_dir)
    changes_file = 'changes-since-{}.txt'.format(last_jdk_tag)
    with open(changes_file, 'w') as fp:
        fp.write(out)
    mx.log('Saved changes since {} to {}'.format(last_jdk_tag, os.path.abspath(changes_file)))
    if overwritten:
        overwritten_file = 'overwritten-diffs.txt'
        with open(overwritten_file, 'w') as fp:
            fp.write(overwritten)
        mx.warn('Overwritten changes detected in OpenJDK Graal! See diffs in ' + os.path.abspath(overwritten_file))


mx_sdk.register_graalvm_component(mx_sdk.GraalVmJvmciComponent(
    suite=_suite,
    name='Graal compiler',
    short_name='cmp',
    dir_name='graal',
    license_files=[],
    third_party_license_files=[],
    jar_distributions=[  # Dev jars (annotation processors)
        'compiler:GRAAL_PROCESSOR_COMMON',
        'compiler:GRAAL_OPTIONS_PROCESSOR',
        'compiler:GRAAL_SERVICEPROVIDER_PROCESSOR',
        'compiler:GRAAL_NODEINFO_PROCESSOR',
        'compiler:GRAAL_REPLACEMENTS_PROCESSOR',
        'compiler:GRAAL_COMPILER_MATCH_PROCESSOR',
    ],
    jvmci_jars=['compiler:GRAAL', 'compiler:GRAAL_MANAGEMENT'],
    graal_compiler='graal',
))


mx.update_commands(_suite, {
    'sl' : [sl, '[SL args|@VM options]'],
    'vm': [run_vm, '[-options] class [args...]'],
    'jaotc': [mx_jaotc.run_jaotc, '[-options] class [args...]'],
    'jaotc-test': [mx_jaotc.jaotc_test, ''],
    'ctw': [ctw, '[-vmoptions|noinline|nocomplex|full]'],
    'nodecostdump' : [_nodeCostDump, ''],
    'verify_jvmci_ci_versions': [verify_jvmci_ci_versions, ''],
    'java_base_unittest' : [java_base_unittest, 'Runs unittest on JDK java.base "only" module(s)'],
    'updategraalinopenjdk' : [updategraalinopenjdk, '[options]'],
    'microbench': [microbench, ''],
    'javadoc': [javadoc, ''],
    'makegraaljdk': [makegraaljdk, '[options]'],
})

def mx_post_parse_cmd_line(opts):
    mx.add_ide_envvar('JVMCI_VERSION_CHECK')
    for dist in _suite.dists:
        dist.set_archiveparticipant(GraalArchiveParticipant(dist, isTest=dist.name.endswith('_TEST')))
    add_bootclasspath_append(mx.distribution('sdk:GRAAL_SDK'))
    add_bootclasspath_append(mx.distribution('truffle:TRUFFLE_API'))
    global _vm_prefix
    _vm_prefix = opts.vm_prefix
