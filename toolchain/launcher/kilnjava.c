/*
 * kilnjava — starts a JVM without the `java` launcher.
 *
 * Kiln runs every toolchain binary as `/system/bin/linker64 <binary> …` (an app
 * at targetSdk 36 may map, but not exec, files in its own data dir). Under that
 * model /proc/self/exe is the linker, and the stock `java` launcher derives
 * JAVA_HOME from /proc/self/exe — so it cannot find itself. This launcher takes
 * JAVA_HOME as an argument instead and calls JNI_CreateJavaVM directly; libjvm
 * then locates java.home from its own path (dladdr), which stays correct.
 *
 *   kilnjava <java_home> [jvm options…] <main.Class> [program args…]
 *
 * Heap pointer tagging is switched off first: Android tags heap pointers, the
 * JVM strips the tag (compressed oops, card tables), and bionic then aborts on
 * free() with "Pointer tag ... was truncated". Termux sidesteps this with
 * allowNativeHeapPointerTagging=false on its app; a plain process does it here.
 *
 * JVM options are the leading arguments that start with '-'; `-cp X` /
 * `-classpath X` become -Djava.class.path=X.
 */
#include <dlfcn.h>
#include <malloc.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static void slashify(char *s) { for (; *s; s++) if (*s == '.') *s = '/'; }

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: kilnjava <java_home> [jvm options] <main.Class> [args]\n");
        return 2;
    }
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
    const char *home = argv[1];
    char path[4096];
    snprintf(path, sizeof path, "%s/lib/server/libjvm.so", home);
    void *jvm = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!jvm) { fprintf(stderr, "kilnjava: %s\n", dlerror()); return 3; }
    CreateJavaVM_t create = (CreateJavaVM_t) dlsym(jvm, "JNI_CreateJavaVM");
    if (!create) { fprintf(stderr, "kilnjava: no JNI_CreateJavaVM\n"); return 3; }

    JavaVMOption *opts = calloc((size_t) argc, sizeof(JavaVMOption));
    int n = 0, i = 2;
    for (; i < argc && argv[i][0] == '-'; i++) {
        if ((!strcmp(argv[i], "-cp") || !strcmp(argv[i], "-classpath")) && i + 1 < argc) {
            size_t len = strlen(argv[i + 1]) + 32;
            char *o = malloc(len);
            snprintf(o, len, "-Djava.class.path=%s", argv[++i]);
            opts[n++].optionString = o;
        } else {
            opts[n++].optionString = argv[i];
        }
    }
    if (i >= argc) { fprintf(stderr, "kilnjava: no main class\n"); return 2; }
    char *mainClass = strdup(argv[i++]);
    slashify(mainClass);

    JavaVMInitArgs vmArgs = { .version = JNI_VERSION_21, .nOptions = n, .options = opts,
                              .ignoreUnrecognized = JNI_FALSE };
    JavaVM *vm; JNIEnv *env;
    if (create(&vm, (void **) &env, &vmArgs) != JNI_OK) {
        fprintf(stderr, "kilnjava: JNI_CreateJavaVM failed\n");
        return 4;
    }
    jclass cls = (*env)->FindClass(env, mainClass);
    if (!cls) { (*env)->ExceptionDescribe(env); return 5; }
    jmethodID mainId = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (!mainId) { (*env)->ExceptionDescribe(env); return 5; }
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jargs = (*env)->NewObjectArray(env, argc - i, strCls, NULL);
    for (int k = i; k < argc; k++)
        (*env)->SetObjectArrayElement(env, jargs, k - i, (*env)->NewStringUTF(env, argv[k]));
    (*env)->CallStaticVoidMethod(env, cls, mainId, jargs);
    int rc = 0;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); rc = 1; }
    /* Waits for non-daemon threads, like the real launcher; System.exit() exits directly. */
    (*vm)->DestroyJavaVM(vm);
    return rc;
}
