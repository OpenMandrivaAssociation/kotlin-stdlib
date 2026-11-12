Name:		kotlin-stdlib
Version:	2.4.21
Release:	2
Summary:	Kotlin standard library for the JVM
Group:		Development/Java
License:	Apache-2.0
URL:		https://github.com/JetBrains/kotlin
# Kotlin tag v2.4.21, commit 9180ce6a7a7d3627f88b424717583f28ddca052d.
# The JVM stdlib sources are compiled with the installed kotlinc 2.0.21.
# That compiler jar is patched in the build directory and is not installed.
# Context parameter sources use syntax 2.0.21 cannot parse, so Context.kt
# and ContextOf.kt are left out. Square-bracket destructuring and
# @IgnorableReturnValue are rewritten in the staged tree. TypesJVM
# destructuring and discarded underscore bindings are shadowed.
# kotlinc 2.0.21 resolves setOf(x), listOf(x), and mapOf(x) to the
# varargs overloads in this fragment compile. One-element toSet() and
# toMap() then recurse. Those call sites go through singletonSet,
# singletonList, and singletonMap.
# -Xoutput-builtins-metadata arrived in Kotlin 2.1.20, so this compiler
# does not write .kotlin_builtins. Those seven resources are copied from
# the installed kotlin-stdlib 2.0.21 jar. kotlin-reflect loads kotlin.Any
# from them.
# The jar is installed under a versioned file name. kotlin-libs 2.0.21 keeps
# /usr/share/java/kotlin-stdlib.jar and its sysjar index.
Source0:	kotlin-stdlib-%{version}.tar.gz
Source1:	stdlib-build.py
Source2:	PatchOverride.java
Source3:	StdlibDiag.java
Source4:	FixMapMethods.java

BuildArch:	noarch
BuildRequires:	java-21-openjdk-devel
BuildRequires:	kotlin = 2.0.21
BuildRequires:	sysjar
BuildRequires:	javapackages-filesystem
BuildRequires:	mvn(org.jetbrains:annotations)
BuildRequires:	mvn(org.ow2.asm:asm)
BuildRequires:	mvn(org.ow2.asm:asm-tree)

%description
org.jetbrains.kotlin:kotlin-stdlib %{version}. Collections, text, and the
JDK 7 and JDK 8 platform implementations are compiled from the Kotlin tag.
The package does not replace the kotlin-libs 2.0.21 jars.

%prep
%autosetup -n kotlin-stdlib-%{version} -p1
find . -type f \( -name '*.jar' -o -name '*.class' -o -name '*.so' -o -name '*.node' -o -name '*.wasm' \) -print > binaries.list
if [ -s binaries.list ]; then
	cat binaries.list
	exit 1
fi
test -s libraries/stdlib/jvm/runtime/kotlin/jvm/internal/Intrinsics.java
test -s libraries/stdlib/jvm/compileOnly/JDK8PlatformImplementations.kt
test -s license/LICENSE.txt

%build
set -eo pipefail
export JAVA_HOME=%{_jvmdir}/java-21-openjdk
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
export TMPDIR=%{_tmppath}
cp -f %{_sourcedir}/stdlib-build.py %{_sourcedir}/PatchOverride.java \
	%{_sourcedir}/StdlibDiag.java %{_sourcedir}/FixMapMethods.java .
python3 stdlib-build.py \
	--source . \
	--java-home "$JAVA_HOME" \
	--work %{_builddir}/kotlin-stdlib-work \
	--jar %{_builddir}/kotlin-stdlib-%{version}.jar
test -s %{_builddir}/kotlin-stdlib-%{version}.jar
jar tf %{_builddir}/kotlin-stdlib-%{version}.jar | grep -F -q 'kotlin/jvm/internal/Intrinsics.class'
jar tf %{_builddir}/kotlin-stdlib-%{version}.jar | grep -F -q 'META-INF/main.kotlin_module'
jar --describe-module --file %{_builddir}/kotlin-stdlib-%{version}.jar > module.txt
cat module.txt
if grep -F -q 'Unable' module.txt; then
	exit 1
fi
grep -F -q 'kotlin.stdlib' module.txt

cat > kotlin-stdlib.pom << END
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-stdlib</artifactId>
  <version>%{version}</version>
  <name>kotlin-stdlib</name>
  <description>Kotlin standard library for the JVM.</description>
  <url>https://github.com/JetBrains/kotlin</url>
  <licenses>
    <license>
      <name>Apache-2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <dependencies>
    <dependency>
      <groupId>org.jetbrains</groupId>
      <artifactId>annotations</artifactId>
      <version>26.1.0</version>
    </dependency>
  </dependencies>
</project>
END

%install
set -eo pipefail
mkdir -p %{buildroot}
export TMPDIR=%{_tmppath}
sysjar install --buildroot %{buildroot} \
	--artifact kotlin-stdlib.pom:%{_builddir}/kotlin-stdlib-%{version}.jar \
	--file-name kotlin-stdlib-%{version} \
	--filelist .mfiles
idx="%{buildroot}/usr/share/sysjar/index/org.jetbrains.kotlin/kotlin-stdlib.sysjar"
moved="%{buildroot}/usr/share/sysjar/index/org.jetbrains.kotlin/kotlin-stdlib-%{version}.sysjar"
test -s "$idx"
grep -F -q '"version": "%{version}"' "$idx"
grep -F -q '"artifactId": "kotlin-stdlib"' "$idx"
/bin/mv "$idx" "$moved"
cat > .mfiles << EOF
%%dir /usr/share/sysjar/index/org.jetbrains.kotlin
/usr/share/sysjar/index/org.jetbrains.kotlin/kotlin-stdlib-%{version}.sysjar
%{_javadir}/kotlin-stdlib-%{version}.jar
/usr/share/maven-poms/kotlin-stdlib-%{version}.pom
EOF
test -s %{buildroot}%{_javadir}/kotlin-stdlib-%{version}.jar
if [ -e %{buildroot}%{_javadir}/kotlin-stdlib.jar ]; then
	exit 1
fi

%check
set -eo pipefail
export JAVA_HOME=%{_jvmdir}/java-21-openjdk
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
export TMPDIR=%{_tmppath}
JAR=%{buildroot}%{_javadir}/kotlin-stdlib-%{version}.jar
test -s "$JAR"
mkdir -p check
cat > check/LibCall.kt << 'EOF'
import kotlin.collections.listOf
import kotlin.collections.mapOf
import kotlin.collections.toList
import kotlin.collections.toMap
import kotlin.collections.toSet
import kotlin.text.removePrefix
import kotlin.to

fun main() {
    val values = listOf("gradle")
    val stripped = "kotlin-stdlib".removePrefix("kotlin-")
    val one = mapOf("stdlib" to 241)
    val fromArray = arrayOf("stdlib").toSet()
    val fromList = arrayOf(241).toList()
    val fromPairs = arrayOf("stdlib" to 241).toMap()
    if (values.single() != "gradle" || stripped != "stdlib" || one.getValue("stdlib") != 241) {
        throw AssertionError(values.toString())
    }
    if (fromArray.single() != "stdlib" || fromList.single() != 241 || fromPairs.getValue("stdlib") != 241) {
        throw AssertionError(fromArray.toString())
    }
    if (object {}::class.java.classLoader.getResource("kotlin/kotlin.kotlin_builtins") == null) {
        throw AssertionError("kotlin_builtins missing")
    }
    println("stdlib_kt_ok " + stripped + " " + one.getValue("stdlib") + " toset")
}
EOF
kotlinc -J-Xmx1g -jvm-target 1.8 -language-version 2.0 -no-stdlib -no-reflect \
	-jdk-home "$JAVA_HOME" -cp "$JAR" -d check/out check/LibCall.kt
java -cp "check/out:$JAR" LibCallKt > check/out.txt
cat check/out.txt
grep -F -qx 'stdlib_kt_ok stdlib 241 toset' check/out.txt

%files -f .mfiles
%license license/LICENSE.txt
