#!/usr/bin/env python3
"""Compile Kotlin 2.4.21 stdlib with the installed kotlinc 2.0.21.

kotlinc 2.0.21 cannot lower this stdlib as shipped. The build copies the
system compiler jar, patches that copy, and does not install it.
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

DESTRUCTURE = (
    """                (val variance, val type) =
                    arguments.singleOrNull()
                        ?: throw IllegalArgumentException("kotlin.Array must have exactly one type argument: $this")
""",
    """                val arrayProjection =
                    arguments.singleOrNull()
                        ?: throw IllegalArgumentException("kotlin.Array must have exactly one type argument: $this")
                val variance = arrayProjection.variance
                val type = arrayProjection.type
""",
)

OPTINS = [
    "kotlin.ExperimentalMultiplatform",
    "kotlin.ExperimentalStdlibApi",
    "kotlin.contracts.ExperimentalContracts",
    "kotlin.io.encoding.ExperimentalEncodingApi",
    "kotlin.time.ExperimentalTime",
    "kotlin.uuid.ExperimentalUuidApi",
]

SKIP_REL = {
    "kotlin/internal/InternalAnnotations.kt",
    "kotlin/contextParameters/Context.kt",
    "kotlin/contextParameters/ContextOf.kt",
}


def run(cmd, log_path, env):
    print("RUN", cmd[0], "...", flush=True)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True)
    log_path.write_text(proc.stdout)
    if proc.returncode != 0:
        tail = "\n".join(proc.stdout.splitlines()[-40:])
        print(tail)
        raise SystemExit(f"command failed rc={proc.returncode} log={log_path}")
    return proc.stdout


def find_asm_pair():
    writers = []
    trees = []
    for path in sorted(glob.glob("/usr/share/java/asm*.jar")):
        try:
            with zipfile.ZipFile(path) as jar:
                names = set(jar.namelist())
        except zipfile.BadZipFile:
            continue
        if "org/objectweb/asm/ClassWriter.class" in names:
            writers.append(path)
        if "org/objectweb/asm/tree/ClassNode.class" in names:
            trees.append(path)
    for writer in writers:
        name = Path(writer).name
        if not name.startswith("asm-"):
            continue
        version = name[len("asm-"):]
        match = f"/usr/share/java/asm-tree-{version}"
        for tree in trees:
            if tree == match or tree.startswith(match):
                return writer, tree
    if writers and trees:
        return writers[0], trees[0]
    raise SystemExit("asm and asm-tree jars were not found")


def collect(root, relative_dirs):
    files = []
    for relative in relative_dirs:
        directory = root / relative
        for path in directory.rglob("*"):
            if not path.is_file() or path.suffix not in {".kt", ".java"}:
                continue
            rel = path.relative_to(directory).as_posix()
            if rel in SKIP_REL:
                continue
            files.append(path.resolve())
    files.sort()
    return files


def rewrite_parsed_syntax(source):
    """Apply the Kotlin 2.4 syntax edits inside the staged tree.

    kotlinc 2.0.21 parses (a, b), not [a, b], and rejects @IgnorableReturnValue
    unless the return-value checker is on. The compile that produced class files
    had already applied both edits in the source tree and shadowed only val _
    and TypesJVM.kt. Shadowing these edits leaves the file outside that tree,
    and the frontend then fails to actualize RuntimeException and AutoCloseable.
    """
    changed = 0
    for path in source.rglob("*.kt"):
        text = path.read_text()
        original = text
        text = re.sub(
            r"\b(val|var) \[([^\[\]\n]+)\]",
            lambda match: f"{match.group(1)} ({match.group(2)})",
            text,
        )
        text = re.sub(
            r"\bfor \(\[([^\[\]\n]+)\]",
            lambda match: f"for (({match.group(1)})",
            text,
        )
        text = re.sub(r"[ \t]*@IgnorableReturnValue[ \t]*\n", "", text)
        text = text.replace("@IgnorableReturnValue ", "")
        if "@IgnorableReturnValue" in text or re.search(r"\b(val|var) \[", text) or "for ([" in text:
            raise SystemExit(f"unrewritten Kotlin 2.4 syntax in {path}")
        if text != original:
            path.write_text(text)
            changed += 1
    print(f"rewrote {changed} sources in place", flush=True)


def adapt(path, source_root, shadow, types_path):
    text = path.read_text()
    original = text
    if path.resolve() == types_path:
        if DESTRUCTURE[0] not in text:
            raise SystemExit(f"TypesJVM destructuring site not found in {path}")
        text = text.replace(DESTRUCTURE[0], DESTRUCTURE[1], 1)
    count = 0

    def discard(match):
        nonlocal count
        count += 1
        return f"val unusedDiscard{count} ="

    text = re.sub(r"val _ =", discard, text)
    if text == original:
        return path
    dest = shadow / path.resolve().relative_to(source_root)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text)
    return dest


def kotlinc(java, preloader, compiler, args, log_path, env):
    cmd = [
        str(java),
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Xmx4g",
        "-cp", preloader,
        "org.jetbrains.kotlin.preloading.Preloader",
        "-cp", compiler,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    ]
    cmd.extend(args)
    return run(cmd, log_path, env)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--work", required=True)
    parser.add_argument("--jar", required=True)
    args = parser.parse_args()
    source = Path(args.source).resolve()
    java_home = Path(args.java_home)
    work = Path(args.work).resolve()
    java = java_home / "bin" / "java"
    javac = java_home / "bin" / "javac"
    if not java.is_file() or not javac.is_file():
        raise SystemExit(f"JDK 21 tools not found under {java_home}")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + ":/usr/bin:/bin"
    if work.exists():
        shutil.rmtree(work)
    classes = work / "classes"
    classes.mkdir(parents=True)
    tools = work / "tools"
    tools.mkdir()
    asm, asm_tree = find_asm_pair()
    here = Path(__file__).resolve().parent
    diag_src = here / "StdlibDiag.java"
    patch_src = here / "PatchOverride.java"
    fix_src = here / "FixMapMethods.java"
    for path in (diag_src, patch_src, fix_src):
        if not path.is_file():
            raise SystemExit(f"missing build tool source {path}")
    run([
        str(javac), "--release", "21", "-encoding", "UTF-8",
        "-d", str(tools), str(diag_src),
    ], work / "diag-javac.txt", env)
    run([
        str(javac), "--release", "21", "-encoding", "UTF-8",
        "-cp", f"{asm}:{asm_tree}",
        "-d", str(tools), str(patch_src), str(fix_src),
    ], work / "patch-javac.txt", env)
    compiler_in = "/usr/share/kotlin/lib/kotlin-compiler.jar"
    preloader = "/usr/share/kotlin/lib/kotlin-preloader.jar"
    # The preloader loads annotations-13.0.jar and the other compiler
    # jars from the directory that contains the compiler jar.
    lib_dir = work / "lib"
    lib_dir.mkdir()
    for entry in Path("/usr/share/kotlin/lib").iterdir():
        if entry.name == "kotlin-compiler.jar":
            continue
        (lib_dir / entry.name).symlink_to(entry)
    compiler_out = lib_dir / "kotlin-compiler.jar"
    run([
        str(java), "-Xmx2g",
        "-cp", f"{tools}:{asm}:{asm_tree}",
        "PatchOverride",
        compiler_in,
        str(compiler_out),
        str(tools / "StdlibDiag.class"),
    ], work / "patch.txt", env)
    if not compiler_out.is_file() or compiler_out.stat().st_size < 1000000:
        raise SystemExit("patched compiler jar was not written")

    # Stage the same tree shape the successful compile read. The compileOnly
    # stub stays outside that tree, matching the path that compiled.
    origin = source
    staged = work / "src"
    for src_rel, dest_rel in (
        ("libraries/stdlib/common/src", "common/src"),
        ("libraries/stdlib/src", "src"),
        ("libraries/stdlib/unsigned/src", "unsigned/src"),
        ("libraries/stdlib/jvm/src", "jvm/src"),
        ("libraries/stdlib/jvm/runtime", "jvm/runtime"),
        ("libraries/stdlib/jvm/builtins", "jvm/builtins"),
        ("libraries/stdlib/jdk7/src", "jdk7/src"),
        ("libraries/stdlib/jdk8/src", "jdk8/src"),
    ):
        shutil.copytree(origin / src_rel, staged / dest_rel)
    source = staged
    # Context.kt and ContextOf.kt use context-parameter syntax kotlinc 2.0.21
    # cannot parse. The tree that compiled does not contain them.
    for relative in SKIP_REL:
        for candidate in source.rglob(Path(relative).name):
            if candidate.is_file() and candidate.as_posix().endswith(relative):
                candidate.unlink()
    rewrite_parsed_syntax(source)
    types_path = (source / "jvm/src/kotlin/reflect/TypesJVM.kt").resolve()
    common = collect(source, [
        "common/src",
        "src",
        "unsigned/src",
    ])
    jvm = collect(source, [
        "jvm/src",
        "jvm/runtime",
        "jvm/builtins",
    ])
    # kotlinc resolves these files in absolute-path order. A shadow that sorts
    # before the staged tree is analyzed before RuntimeException's actual
    # typealias, and fake-override status resolution aborts.
    shadow = work / "zrewritten"
    common_sources = [adapt(path, source, shadow, types_path) for path in common]
    jvm_sources = [adapt(path, source, shadow, types_path) for path in jvm]
    shadowed = [path for path in common_sources + jvm_sources if Path(path).is_relative_to(shadow)]
    print(f"shadowed {len(shadowed)} fragment sources", flush=True)
    if len(shadowed) > 20:
        raise SystemExit(f"expected only val _ and TypesJVM shadows, got {len(shadowed)}")
    plain = [str(path) for path in common_sources + jvm_sources if not Path(path).is_relative_to(shadow)]
    if shadowed and plain and min(str(path) for path in shadowed) < max(plain):
        raise SystemExit("shadow paths sort before staged sources")
    stub = origin / "libraries/stdlib/jvm/compileOnly/JDK8PlatformImplementations.kt"
    if not stub.is_file():
        raise SystemExit(f"missing compileOnly stub {stub}")
    jvm_sources.append(stub)
    fragment = [
        "-jdk-home", str(java_home),
        "-jvm-target", "1.8",
        "-language-version", "2.0",
        "-api-version", "2.2",
        "-no-stdlib",
        "-Xallow-kotlin-package",
        "-Xstdlib-compilation",
        "-Xmulti-platform",
        "-Xexpect-actual-classes",
        "-Xcontext-receivers",
        "-Xwhen-guards",
        "-Xmultifile-parts-inherit",
        "-Xuse-14-inline-classes-mangling-scheme",
        "-Xno-new-java-annotation-targets",
        "-Xjvm-default=disable",
        "-Xdont-warn-on-error-suppression",
        "-Xsuppress-missing-builtins-error",
        "-Xsuppress-api-version-greater-than-language-version-error",
        "-Xfragments=commonMain",
        "-Xfragments=jvmMain",
        "-Xfragment-refines=jvmMain:commonMain",
        "-d", str(classes),
    ]
    for name in OPTINS:
        fragment.extend(["-opt-in", name])
    for path in common_sources:
        fragment.append("-Xfragment-sources=commonMain:" + str(path))
    for path in jvm_sources:
        fragment.append("-Xfragment-sources=jvmMain:" + str(path))
    fragment.extend(str(path) for path in common_sources)
    fragment.extend(str(path) for path in jvm_sources)
    print(f"sources common {len(common_sources)} jvm {len(jvm_sources)}", flush=True)
    fragment_log = kotlinc(java, preloader, str(compiler_out), fragment, work / "fragment.txt", env)
    errors = fragment_log.count(": error:")
    class_count = sum(1 for _ in classes.rglob("*.class"))
    print(f"FRAGMENT errors={errors} classes={class_count}", flush=True)
    if errors:
        raise SystemExit("fragment compile reported errors")
    module = classes / "META-INF" / "main.kotlin_module"
    if not module.is_file() or module.stat().st_size < 1000:
        raise SystemExit(f"main.kotlin_module missing or truncated: {module}")
    if b"CollectionsKt" not in module.read_bytes():
        raise SystemExit("main.kotlin_module does not list CollectionsKt")

    stub_class = classes / "kotlin/internal/jdk8/JDK8PlatformImplementations.class"
    if stub_class.is_file():
        stub_class.unlink()
    map_classes = [
        classes / "kotlin/collections/AbstractMap.class",
        classes / "kotlin/collections/EmptyMap.class",
        classes / "kotlin/collections/MapWithDefaultImpl.class",
    ]
    run([
        str(java), "-cp", f"{tools}:{asm}:{asm_tree}",
        "FixMapMethods",
        *(str(path) for path in map_classes),
    ], work / "fix-map.txt", env)

    runtime_shadow = work / "runtime-shadow"
    java_files = []
    for path in jvm:
        if path.suffix != ".java":
            continue
        if path.name == "ReflectionFactory.java":
            text = path.read_text()
            count = text.count("kotlin_stdlib")
            if count != 5:
                raise SystemExit(f"ReflectionFactory kotlin_stdlib count {count}")
            dest = runtime_shadow / "kotlin/jvm/internal/ReflectionFactory.java"
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text.replace("kotlin_stdlib", "main"))
            java_files.append(dest)
        else:
            java_files.append(path)
    if not any(path.name == "Intrinsics.java" for path in java_files):
        raise SystemExit("Intrinsics.java was not in the runtime sources")
    annotations = "/usr/share/java/org.jetbrains.annotations.jar"
    run([
        str(javac), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
        "-cp", f"{classes}:{annotations}",
        "-d", str(classes),
        *(str(path) for path in java_files),
    ], work / "runtime-javac.txt", env)

    jdk_sources = collect(source, [
        "jdk7/src",
        "jdk8/src",
    ])
    jdk_sources = [adapt(path, source, shadow, types_path) for path in jdk_sources if path.suffix == ".kt"]
    jdk_args = [
        "-jdk-home", str(java_home),
        "-jvm-target", "1.8",
        "-language-version", "2.0",
        "-api-version", "2.2",
        "-no-stdlib",
        "-Xallow-kotlin-package",
        "-Xsuppress-api-version-greater-than-language-version-error",
        "-Xdont-warn-on-error-suppression",
        "-Xfriend-paths=" + str(classes),
        "-module-name", "stdlibJdk8",
        "-cp", str(classes),
        "-d", str(classes),
    ]
    for name in (
        "kotlin.ExperimentalStdlibApi",
        "kotlin.contracts.ExperimentalContracts",
        "kotlin.io.encoding.ExperimentalEncodingApi",
        "kotlin.time.ExperimentalTime",
        "kotlin.io.path.ExperimentalPathApi",
    ):
        jdk_args.extend(["-opt-in", name])
    jdk_args.extend(str(path) for path in jdk_sources)
    kotlinc(java, preloader, str(compiler_out), jdk_args, work / "jdk.txt", env)
    if module.stat().st_size < 1000 or b"CollectionsKt" not in module.read_bytes():
        raise SystemExit("jdk compile replaced main.kotlin_module")
    jdk_module = classes / "META-INF" / "stdlibJdk8.kotlin_module"
    if not jdk_module.is_file():
        raise SystemExit("stdlibJdk8.kotlin_module was not written")
    for required in (
        "kotlin/jvm/internal/Intrinsics.class",
        "kotlin/collections/CollectionsKt.class",
        "kotlin/collections/AbstractMap.class",
        "kotlin/internal/jdk8/JDK8PlatformImplementations.class",
        "kotlin/internal/jdk7/JDK7PlatformImplementations.class",
    ):
        if not (classes / required).is_file():
            raise SystemExit(f"missing class {required}")
    path_classes = list((classes / "kotlin/io/path").glob("*.class"))
    if len(path_classes) < 5:
        raise SystemExit(f"jdk path classes missing: {len(path_classes)}")

    smoke = work / "smoke"
    smoke.mkdir()
    (smoke / "Smoke.java").write_text(
        "public final class Smoke {\n"
        "    public static void main(String[] args) throws Exception {\n"
        "        Class.forName(\"kotlin.collections.AbstractMap\");\n"
        "        Class.forName(\"kotlin.collections.EmptyMap\");\n"
        "        java.util.List<String> values = kotlin.collections.CollectionsKt.listOf(\"gradle\");\n"
        "        String stripped = kotlin.text.StringsKt.removePrefix(\"kotlin-stdlib\", \"kotlin-\");\n"
        "        java.util.Map<String, Integer> one = kotlin.collections.MapsKt.mapOf(\n"
        "            kotlin.TuplesKt.to(\"stdlib\", Integer.valueOf(241)));\n"
        "        if (values.size() != 1 || !\"gradle\".equals(values.get(0))) {\n"
        "            throw new AssertionError(values);\n"
        "        }\n"
        "        if (!\"stdlib\".equals(stripped) || one.size() != 1\n"
        "                || !Integer.valueOf(241).equals(one.get(\"stdlib\"))\n"
        "                || !one.keySet().contains(\"stdlib\")) {\n"
        "            throw new AssertionError(one);\n"
        "        }\n"
        "        Object impl = Class.forName(\"kotlin.internal.PlatformImplementationsKt\")\n"
        "            .getField(\"IMPLEMENTATIONS\").get(null);\n"
        "        if (!impl.getClass().getName().equals(\n"
        "                \"kotlin.internal.jdk8.JDK8PlatformImplementations\")) {\n"
        "            throw new AssertionError(impl.getClass().getName());\n"
        "        }\n"
        "        System.out.println(\"stdlib_build_ok \" + stripped + \" \" + one.get(\"stdlib\"));\n"
        "    }\n"
        "}\n"
    )
    run([
        str(javac), "--release", "21", "-encoding", "UTF-8",
        "-cp", str(classes), "-d", str(smoke), str(smoke / "Smoke.java"),
    ], work / "smoke-javac.txt", env)
    smoke_out = run([
        str(java), "-cp", f"{smoke}:{classes}", "Smoke",
    ], work / "smoke.txt", env)
    if "stdlib_build_ok stdlib 241" not in smoke_out:
        raise SystemExit(smoke_out)
    print(smoke_out.strip(), flush=True)

    manifest = work / "manifest.mf"
    manifest.write_text("Manifest-Version: 1.0\nAutomatic-Module-Name: kotlin.stdlib\n")
    jar_tool = java_home / "bin" / "jar"
    jar_path = Path(args.jar).resolve()
    if jar_path.exists():
        jar_path.unlink()
    run([
        str(jar_tool), "cfm", str(jar_path), str(manifest), "-C", str(classes), ".",
    ], work / "jar.txt", env)
    with zipfile.ZipFile(jar_path) as jar:
        names = set(jar.namelist())
    for required in (
        "kotlin/jvm/internal/Intrinsics.class",
        "kotlin/collections/CollectionsKt.class",
        "META-INF/main.kotlin_module",
        "META-INF/stdlibJdk8.kotlin_module",
    ):
        if required not in names:
            raise SystemExit(f"jar missing {required}")
    print(f"JAR {jar_path} entries={len(names)}", flush=True)


if __name__ == "__main__":
    main()
