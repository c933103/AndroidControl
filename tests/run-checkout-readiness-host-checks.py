#!/usr/bin/env python3
"""Run test-only parser/model checks using the verified Gradle Kotlin compiler.

Example: JAVA_HOME=/path/to/jdk python3 tests/run-checkout-readiness-host-checks.py \
  --kotlin-lib /path/to/gradle-8.14/lib [--input-dump /path/to/authorized-input.txt]
No Android device, downloads, or production routing are used.
"""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--kotlin-lib', type=Path, required=True)
p.add_argument('--input-dump', type=Path)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
if not java:
    p.error('Set JAVA_HOME to a JDK with the java.compiler and jdk.compiler modules.')
lib = a.kotlin_lib.resolve()
stdlib = next(lib.glob('kotlin-stdlib-[0-9]*.jar'))
annotations = next(lib.glob('annotations-*.jar'))
classpath = os.pathsep.join(map(str, [stdlib, annotations]))
with tempfile.TemporaryDirectory(prefix='checkout-readiness-') as temporary:
    temp = Path(temporary)
    classes = temp / 'classes'
    subprocess.run([java, '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes),
        str(root/'manager/src/androidTest/java/org/androidcontrol/app/regression/CheckoutReadiness.kt'),
        str(root/'tests/CheckoutReadinessTest.kt')], check=True)
    command = [java, '-cp', str(classes)+os.pathsep+classpath,
        'org.androidcontrol.app.regression.CheckoutReadinessTestKt']
    if a.input_dump:
        command.append(str(a.input_dump.resolve()))
    subprocess.run(command, check=True)
    # Parse Java 8 syntax without resolving Android types. This is not an APK build.
    parser = temp / 'CheckJavaSyntax.java'
    parser.write_text('''import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.util.*;
public class CheckJavaSyntax {
  public static void main(String[] args) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("JDK compiler required");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
          Arrays.asList("-source", "8", "-proc:none"), null, files.getJavaFileObjects(args[0]));
      task.parse();
      for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
        if (d.getKind() == Diagnostic.Kind.ERROR) throw new IllegalStateException(d.toString());
      }
    }
    System.out.println("PASS: checkout Java fixture syntax parsed as Java 8; Android type checking still requires the APK build");
  }
}
''')
    subprocess.run([java, str(parser), str(root/'tests/checkout-fixture/CheckoutActivity.java')], check=True)
