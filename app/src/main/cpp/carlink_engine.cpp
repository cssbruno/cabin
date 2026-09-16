#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <string>
#include <stdexcept>

namespace {
[[maybe_unused]] void* required(void* library, const char* name) {
    dlerror();
    void* symbol = dlsym(library, name);
    const char* error = dlerror();
    if (error || !symbol) throw std::runtime_error(std::string("Carlink ABI unavailable: ") + name);
    return symbol;
}

// The ABI below is pinned to the imported libcps_7862.so (Android 10/ARM64).
// Factory disassembly: allocation=0x1b8, BBinder=+8, RefBase=+0x1a8.
// An sp<T> passed by const reference is one pointer. No C++ object is copied.
struct StrongBinder { void* pointer; };
static_assert(sizeof(StrongBinder) == sizeof(void*));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_cabin_carlink_CarlinkNative_create(JNIEnv* env, jobject, jstring directory) {
#if !defined(__aarch64__)
    (void) directory;
    env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"), "Carlink requires ARM64 Android 10");
    return nullptr;
#else
    // This process owns exactly one engine. It is terminated on unbind, rather
    // than unloading code underneath the vendor's persistent worker threads.
    static bool attempted = false;
    try {
        if (attempted) throw std::runtime_error("Carlink engine was already initialized in this process");
        attempted = true;
        const char* path = env->GetStringUTFChars(directory, nullptr);
        if (!path) return nullptr;
        const int changed = chdir(path);
        env->ReleaseStringUTFChars(directory, path);
        if (changed != 0) throw std::runtime_error("Cannot open Carlink's private storage");

        void* library = dlopen("libcps_7862.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            const char* error = dlerror();
            throw std::runtime_error(std::string("Carlink library load failed: ") + (error ? error : "unknown linker error"));
        }
        auto allocate = reinterpret_cast<void* (*)(size_t)>(required(library, "_Znwm"));
        auto construct = reinterpret_cast<void (*)(void*)>(required(library, "_ZN7android14CarplayServiceC1Ev"));
        auto retain = reinterpret_cast<void (*)(void*, const void*)>(required(library, "_ZNK7android7RefBase9incStrongEPKv"));
        auto global = reinterpret_cast<void**>(required(library, "_ZN7android9gmServiceE"));
        auto wrap = reinterpret_cast<jobject (*)(JNIEnv*, const StrongBinder&)>(
            required(RTLD_DEFAULT, "_ZN7android20javaObjectForIBinderEP7_JNIEnvRKNS_2spINS_7IBinderEEE"));
        if (*global) throw std::runtime_error("Carlink library already owns an engine");
        auto engine = static_cast<unsigned char*>(allocate(0x1b8));
        construct(engine);
        retain(engine + 0x1a8, global);
        *global = engine;
        const StrongBinder binder{engine + 8};
        jobject result = wrap(env, binder);
        if (!result && !env->ExceptionCheck()) throw std::runtime_error("Cannot expose the app-owned Carlink engine");
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
#endif
}
