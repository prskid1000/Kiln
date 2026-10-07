#!/usr/bin/env python3
"""
Builds the Kiln toolchain pack: everything an on-device build needs, in one
versioned zip that the app unpacks into its files dir.

    python toolchain/build_pack.py                    # -> toolchain/out/components/*.kpk (arm64 phones)
    KILN_ARCH=x86_64 python toolchain/build_pack.py   # -> toolchain/out/components-x86_64/ (emulators)

The components are bundled into the Kiln APK (app/build.gradle.kts, one flavour per ABI) and
installed or updated on launch when their content hash changes. Rebuild one part with e.g.
`python toolchain/build_pack.py kit` and reinstall the APK.

Inputs are downloaded into toolchain/.cache (gitignored) and pinned below, so a
pack is reproducible. Run `gradlew :kit:exportKit :toolserver:jar` first (the
script runs them itself if their outputs are missing).

Layout of the pack (all paths relative to its root):

    bin/kilnjava              JVM launcher (see launcher/kilnjava.c)
    bin/aapt2                 aapt2 (Termux PIE build; libs in lib/)
    lib/                      shared libraries for aapt2
    jdk/                      jlink'ed OpenJDK 21 (Termux build, bionic)
    tools/r8.jar              d8/R8 (latest; reads Kotlin 2.4 metadata)
    tools/apksigner.jar
    tools/toolserver.jar      warm-JVM tool host
    kotlinc/*.jar             Kotlin compiler + compose & serialization plugins
    sdk/android.jar           compile SDK
    kit/classpath/*.jar       compile classpath (kit + every dependency)
    kit/dex/*.dex             the same, pre-dexed (min API 30)
    kit/res/*.zip             precompiled library resources (aapt2 flats)
    kit/res/ids.txt           stable resource IDs for those resources
    kit/res/packages.txt      library packages (for --extra-packages)
    kit/manifest.xml          merged manifest fragment of every library
    kit/API.md                the kit reference the agent reads
    templates/                project templates
"""
import hashlib, io, json, os, re, shutil, subprocess, sys, tarfile, urllib.request, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "1"
ROOT = Path(__file__).resolve().parent            # toolchain/
REPO = ROOT.parent
CACHE = ROOT / ".cache"
OUT = ROOT / "out"
# Native parts (JDK, aapt2, launcher) are per ABI; everything else is shared.
ARCH = os.environ.get("KILN_ARCH", "aarch64")
ABI = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}[ARCH]
SUFFIX = "" if ARCH == "aarch64" else f"-{ARCH}"
PACK = OUT / f"pack{SUFFIX}"

SDK = Path(os.environ.get("ANDROID_HOME", r"C:\Users\prith\AppData\Local\Android\Sdk"))
NDK = SDK / "ndk" / "27.1.12297006"
JDK = Path(os.environ.get("KILN_HOST_JDK", r"C:\Users\prith\.jdks\corretto-21.0.12.1"))
ANDROID_JAR = SDK / "platforms" / "android-36" / "android.jar"
APKSIGNER = SDK / "build-tools" / "36.1.0" / "lib" / "apksigner.jar"
HOST_AAPT2 = SDK / "build-tools" / "36.1.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")

TERMUX = "https://packages.termux.dev/apt/termux-main/"
# openjdk-21-x only for java.desktop.jmod: kotlinc loads javax.swing.Icon (an
# interface, no native code) once compiler plugins are on. Runs headless.
TERMUX_PKGS = ["openjdk-21", "openjdk-21-x", "libandroid-shmem", "libandroid-spawn", "zlib"]
KOTLIN = "2.4.20"
R8 = "9.5.22"

# Modules kotlinc, javac, d8/R8 and apksigner need at runtime.
JDK_MODULES = ("java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,"
               "java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,"
               "java.xml,java.xml.crypto,jdk.compiler,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,"
               "jdk.charsets,jdk.management")
KOTLINC_JARS = ["kotlin-compiler.jar", "kotlin-stdlib.jar", "kotlin-reflect.jar", "kotlin-script-runtime.jar",
                "kotlinx-coroutines-core-jvm.jar", "annotations-13.0.jar", "trove4j.jar",
                "kotlin-annotations-jvm.jar", "compose-compiler-plugin.jar",
                "kotlinx-serialization-compiler-plugin.jar"]


def log(*a): print("[pack]", *a, flush=True)


def run(cmd, **kw):
    log(" ".join(str(c) for c in cmd)[:200])
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def fetch(url, name):
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name.replace(":", "_")   # Debian epochs ("1:11.2") aren't valid on Windows
    if not dest.exists():
        log("download", url)
        tmp = dest.with_suffix(".part")
        with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        tmp.rename(dest)
    return dest


def ar_members(data):
    assert data[:8] == b"!<arch>\n"
    i = 8
    while i + 60 <= len(data):
        h = data[i:i + 60]
        name = h[:16].decode().strip().rstrip("/")
        size = int(h[48:58].decode().strip())
        yield name, data[i + 60:i + 60 + size]
        i += 60 + size + (size % 2)


def termux_root():
    """Unpack the Termux packages into .cache/termux; returns its usr/ dir."""
    root = CACHE / f"termux{SUFFIX}"
    usr = root / "data" / "data" / "com.termux" / "files" / "usr"
    if usr.exists():
        return usr
    index = fetch(TERMUX + f"dists/stable/main/binary-{ARCH}/Packages", f"termux-Packages-{ARCH}").read_text()
    for pkg in TERMUX_PKGS:
        m = re.search(rf"^Package: {re.escape(pkg)}\n(?:.+\n)*?Filename: (\S+)", index, re.M)
        if m is None:
            raise SystemExit(f"termux package not found: {pkg}")
        deb = fetch(TERMUX + m.group(1), Path(m.group(1)).name).read_bytes()
        for name, data in ar_members(deb):
            if name.startswith("data.tar"):
                with tarfile.open(fileobj=io.BytesIO(data)) as t:
                    t.extractall(root, filter="tar")
    return usr


def build_jdk(usr):
    jhome = usr / "lib" / "jvm" / "java-21-openjdk"
    out = PACK / "jdk"
    run([JDK / "bin" / "jlink", "--module-path", jhome / "jmods", "--add-modules", JDK_MODULES,
         "--output", out, "--strip-debug", "--no-man-pages", "--no-header-files", "--compress", "zip-6"])
    # Native deps libjvm/libjava need beyond bionic: copy real files (no symlinks).
    for lib in ["libandroid-shmem.so", "libandroid-spawn.so", "libz.so.1"]:
        shutil.copyfile(os.path.realpath(usr / "lib" / lib), out / "lib" / lib)
    cxx = NDK / f"toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/{ARCH}-linux-android/libc++_shared.so"
    shutil.copyfile(cxx, out / "lib" / "libc++_shared.so")
    return jhome


AAPT2_PKGS = ["aapt2", "zlib", "libzopfli", "libc++", "libpng", "libexpat", "fmt", "libprotobuf", "abseil-cpp"]


def build_aapt2():
    """aapt2 from Termux: a PIE binary, so it runs as `linker64 bin/aapt2` inside
    the app sandbox (the static builds are ET_EXEC, which linker64 refuses).
    Its shared libraries go to lib/ under their SONAME-style names."""
    root = CACHE / f"termux-aapt2{SUFFIX}"
    usr = root / "data" / "data" / "com.termux" / "files" / "usr"
    if not usr.exists():
        index = fetch(TERMUX + f"dists/stable/main/binary-{ARCH}/Packages", f"termux-Packages-{ARCH}").read_text()
        for pkg in AAPT2_PKGS:
            m = re.search(rf"^Package: {re.escape(pkg)}\n(?:.+\n)*?Filename: (\S+)", index, re.M)
            if m is None:
                raise SystemExit(f"termux package not found: {pkg}")
            for name, data in ar_members(fetch(TERMUX + m.group(1), Path(m.group(1)).name).read_bytes()):
                if name.startswith("data.tar"):
                    with tarfile.open(fileobj=io.BytesIO(data)) as t:
                        t.extractall(root, filter="tar")
    (PACK / "bin").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(usr / "bin" / "aapt2", PACK / "bin" / "aapt2")
    lib = PACK / "lib"
    lib.mkdir(parents=True, exist_ok=True)
    for f in (usr / "lib").iterdir():
        if f.name.endswith(".so") or ".so." in f.name:
            if os.path.isfile(os.path.realpath(f)):
                shutil.copyfile(os.path.realpath(f), lib / f.name)


def build_bin(jhome):
    b = PACK / "bin"
    b.mkdir(parents=True, exist_ok=True)
    clang = NDK / f"toolchains/llvm/prebuilt/windows-x86_64/bin/{ARCH}-linux-android30-clang.cmd"
    run([clang, "-O2", "-Wall", "-Wl,-z,max-page-size=16384", f"-I{jhome / 'include'}",
         f"-I{jhome / 'include' / 'linux'}", ROOT / "launcher" / "kilnjava.c", "-o", b / "kilnjava", "-ldl"])


def build_tools():
    t = PACK / "tools"
    t.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(fetch(f"https://dl.google.com/android/maven2/com/android/tools/r8/{R8}/r8-{R8}.jar",
                          f"r8-{R8}.jar"), t / "r8.jar")
    shutil.copyfile(APKSIGNER, t / "apksigner.jar")
    jar = REPO / "toolserver" / "build" / "libs" / "toolserver.jar"
    if not jar.exists():
        run([REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"), ":toolserver:jar"], cwd=REPO)
    shutil.copyfile(jar, t / "toolserver.jar")
    k = PACK / "kotlinc"
    k.mkdir(parents=True, exist_ok=True)
    z = fetch(f"https://github.com/JetBrains/kotlin/releases/download/v{KOTLIN}/kotlin-compiler-{KOTLIN}.zip",
              f"kotlin-compiler-{KOTLIN}.zip")
    with zipfile.ZipFile(z) as zf:
        names = set(zf.namelist())
        for j in KOTLINC_JARS:
            if f"kotlinc/lib/{j}" in names:
                (k / j).write_bytes(zf.read(f"kotlinc/lib/{j}"))
    (PACK / "sdk").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ANDROID_JAR, PACK / "sdk" / "android.jar")


ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID_NS
TOOLS = "{http://schemas.android.com/tools}"


def merge_manifests(manifests):
    """Merge library manifests: permissions + application components, deduped by
    android:name; same-named components merge their children (meta-data)."""
    perms, decls, comps, order, queries = {}, {}, {}, [], []
    for text in manifests:
        root = ET.fromstring(text)
        for el in root.iter():
            for k in [k for k in el.attrib if k.startswith(TOOLS)]:
                del el.attrib[k]
        for p in root.findall("uses-permission"):
            perms[p.get(A + "name")] = p
        for p in root.findall("permission"):
            decls[p.get(A + "name")] = p
        # Package visibility a library needs (Play Billing binds to the Play Store).
        for q in root.findall("queries"):
            for child in q:
                if ET.tostring(child) not in [ET.tostring(x) for x in queries]:
                    queries.append(child)
        app = root.find("application")
        if app is None:
            continue
        for c in app:
            key = (c.tag, c.get(A + "name"))
            if key not in comps:
                comps[key] = c
                order.append(key)
            else:
                have = {(x.tag, x.get(A + "name")) for x in comps[key]}
                for child in c:
                    if (child.tag, child.get(A + "name")) not in have:
                        comps[key].append(child)
    ET.register_namespace("android", ANDROID_NS)
    out = ET.Element("manifest")
    for p in list(decls.values()) + list(perms.values()):
        out.append(p)
    if queries:
        q = ET.SubElement(out, "queries")
        for child in queries:
            q.append(child)
    app = ET.SubElement(out, "application")
    for key in order:
        app.append(comps[key])
    return ET.tostring(out, encoding="unicode")


def build_kit():
    export = REPO / "kit" / "build" / "kit-export"
    if not (export / "index.txt").exists():
        run([REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"), ":kit:exportKit"], cwd=REPO)
    kit = PACK / "kit"
    cp, dex, res = kit / "classpath", kit / "dex", kit / "res"
    for d in (cp, dex, res):
        d.mkdir(parents=True, exist_ok=True)
    work = OUT / "kitwork"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)

    manifests, packages, res_zips = [], [], []
    for f in sorted(export.iterdir()):
        if f.suffix == ".jar":
            shutil.copyfile(f, cp / f.name)
        elif f.suffix == ".aar":
            with zipfile.ZipFile(f) as z:
                names = z.namelist()
                if not (export / (f.stem + ".jar")).exists() and "classes.jar" in names:
                    (cp / (f.stem + ".jar")).write_bytes(z.read("classes.jar"))
                if "AndroidManifest.xml" in names:
                    text = z.read("AndroidManifest.xml").decode()
                    manifests.append(text)
                    pkg = ET.fromstring(text).get("package")
                    if pkg and any(n.startswith("res/") and not n.endswith("/") for n in names):
                        packages.append(pkg)
                if any(n.startswith("res/") and not n.endswith("/") for n in names):
                    rdir = work / f.stem / "res"
                    for n in names:
                        if n.startswith("res/") and not n.endswith("/"):
                            p = work / f.stem / n
                            p.parent.mkdir(parents=True, exist_ok=True)
                            p.write_bytes(z.read(n))
                    rz = res / (f.stem + ".zip")
                    run([HOST_AAPT2, "compile", "--dir", rdir, "-o", rz])
                    res_zips.append(rz)

    packages = sorted(set(packages))
    (res / "packages.txt").write_text("\n".join(packages))
    (kit / "manifest.xml").write_text(merge_manifests(manifests))

    # Link once against a stub app to fix every library resource ID, and to
    # generate the libraries' R classes for exactly those IDs.
    stub = work / "stub"
    (stub / "gen").mkdir(parents=True)
    (stub / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="kiln.stub"/>')
    cmd = [HOST_AAPT2, "link", "-I", ANDROID_JAR, "--manifest", stub / "AndroidManifest.xml",
           "--auto-add-overlay", "--emit-ids", res / "ids.txt", "--java", stub / "gen",
           "--extra-packages", ":".join(packages), "-o", stub / "stub.apk"]
    for rz in res_zips:
        cmd += ["-R", rz]
    run(cmd)
    srcs = [p for p in (stub / "gen").rglob("R.java") if "kiln/stub" not in p.as_posix()]
    (stub / "classes").mkdir()
    argfile = stub / "srcs.txt"
    argfile.write_text("\n".join(str(s) for s in srcs))
    run([JDK / "bin" / "javac", "--release", "17", "-d", stub / "classes", f"@{argfile}"])
    run([JDK / "bin" / "jar", "--create", "--date=2008-01-01T00:00:02Z", "--file", cp / "kiln-kit-R.jar", "-C", stub / "classes", "."])

    jars = sorted(cp.glob("*.jar"))
    dexout = work / "dex"
    dexout.mkdir()
    run([JDK / "bin" / "java", "-cp", PACK / "tools" / "r8.jar", "com.android.tools.r8.D8", "--release",
         "--min-api", "30", "--lib", ANDROID_JAR, "--output", dexout] + jars)
    for i, d in enumerate(sorted(dexout.glob("classes*.dex"), key=lambda p: (len(p.name), p.name))):
        shutil.copyfile(d, dex / f"kit-{i + 1:02d}.dex")
    shutil.copyfile(REPO / "kit" / "API.md", kit / "API.md")


def build_templates():
    shutil.copytree(ROOT / "templates", PACK / "templates", dirs_exist_ok=True)


# What the app installs and updates separately: each is one top-level part of the pack layout.
COMPONENTS = {
    "jdk": ["jdk"],
    "native": ["bin", "lib"],
    "tools": ["tools"],
    "kotlinc": ["kotlinc"],
    "sdk": ["sdk"],
    "kit": ["kit"],
    "templates": ["templates"],
}


def finish():
    """Write one .kpk per component into out/components<suffix>/ (bundled into the APK's assets).

    A .kpk is a zip whose first entry is component.json:
        {"name", "abi", "payloadSha256", "files": {relpath: sha256}, "size"}
    payloadSha256 = sha256 over "<relpath>\0<sha256>\n" for every file, sorted. The app installs a
    component when that hash differs from what it has: a rebuild that changes anything is picked up,
    with no version number to remember to bump."""
    out = OUT / f"components{SUFFIX}"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    total = 0
    for name, dirs in COMPONENTS.items():
        files = {}
        for d in dirs:
            for p in sorted((PACK / d).rglob("*")):
                if p.is_file():
                    files[p.relative_to(PACK).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
        if not files:
            raise SystemExit(f"component {name} is empty — build it first")
        payload = hashlib.sha256("".join(f"{r}\0{h}\n" for r, h in sorted(files.items())).encode()).hexdigest()
        meta = {"name": name, "abi": ABI, "payloadSha256": payload, "files": files,
                "size": sum((PACK / r).stat().st_size for r in files), "kotlin": KOTLIN, "r8": R8}
        z = out / f"{name}.kpk"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            zf.writestr(zipfile.ZipInfo("component.json", (2008, 1, 1, 0, 0, 0)), json.dumps(meta, indent=1))
            for r in sorted(files):
                info = zipfile.ZipInfo(r, (2008, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED if r.endswith((".jar", ".zip")) else zipfile.ZIP_DEFLATED
                info.external_attr = 0o755 << 16 if r.startswith("bin/") else 0o644 << 16
                zf.writestr(info, (PACK / r).read_bytes())
        total += z.stat().st_size
        log(f"component {name}: {len(files)} files, {payload[:12]}, {z.stat().st_size / 1e6:.1f} MB")
    log("components:", out, f"{total / 1e6:.1f} MB")


def main():
    steps = sys.argv[1:] or ["jdk", "bin", "tools", "kit", "templates"]
    usr = termux_root() if ("jdk" in steps or "bin" in steps) else None
    if "jdk" in steps and usr:
        shutil.rmtree(PACK / "jdk", ignore_errors=True)
        build_jdk(usr)
    if "bin" in steps and usr:
        shutil.rmtree(PACK / "bin", ignore_errors=True)
        build_bin(usr / "lib" / "jvm" / "java-21-openjdk")
        build_aapt2()
    if "tools" in steps:
        build_tools()
    if "kit" in steps:
        shutil.rmtree(PACK / "kit", ignore_errors=True)
        build_kit()
    if "templates" in steps:
        build_templates()
    finish()


if __name__ == "__main__":
    main()
