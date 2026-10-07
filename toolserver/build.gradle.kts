// ToolServer: the long-lived JVM process that runs kotlinc / javac / d8 /
// apksigner in-process, so the JIT warm-up is paid once per session.
// Plain Java, no dependencies: the tools are on its classpath at runtime.
plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
