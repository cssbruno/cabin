#include "cabin_io.hpp"
#include <jni.h>
#include <map>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
std::mutex registry_mutex;
jlong sequence = 0;
std::map<jlong, std::string> device_paths;
void check_open(const std::string& name) {
    if (device_paths.size() >= 8) throw std::runtime_error("Too many open devices");
    for (const auto& entry : device_paths)
        if (entry.second == name) throw std::runtime_error("Device already owned by this process");
}
std::map<jlong, std::shared_ptr<cabin::SerialPort>> serial;
std::map<jlong, std::shared_ptr<cabin::I2cDevice>> i2c;
void fail(JNIEnv* env, const std::exception& ex) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), ex.what());
}
std::string path(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing path");
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) throw std::runtime_error("Path allocation failed");
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
template <class T> std::shared_ptr<T> get(const std::map<jlong, std::shared_ptr<T>>& map, jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = map.find(handle);
    if (found == map.end()) throw std::runtime_error("Device closed");
    return found->second;
}
std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray data, int maximum) {
    if (!data) throw std::invalid_argument("Missing bytes");
    int length = env->GetArrayLength(data);
    if (length < 1 || length > maximum) throw std::invalid_argument("Invalid message size");
    std::vector<uint8_t> result(length);
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read bytes");
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_cabin_hardware_replacement_NativeIo_openSerial(
        JNIEnv* env, jclass, jstring name, jint baud) {
    try {
        auto device_path = path(env, name);
        std::lock_guard<std::mutex> lock(registry_mutex);
        check_open(device_path);
        auto port = std::make_shared<cabin::SerialPort>(device_path, baud);
        auto handle = ++sequence; serial.emplace(handle, port); device_paths.emplace(handle, device_path); return handle;
    } catch (const std::exception& ex) { fail(env, ex); return 0; }
}
extern "C" JNIEXPORT jint JNICALL Java_com_cabin_hardware_replacement_NativeIo_readSerial(
        JNIEnv* env, jclass, jlong handle, jbyteArray output, jint timeout) {
    try {
        if (!output || env->GetArrayLength(output) < 1 || env->GetArrayLength(output) > 4096)
            throw std::invalid_argument("Invalid read buffer");
        std::vector<uint8_t> buffer(env->GetArrayLength(output));
        auto port = get(serial, handle);
        int count = port->read(buffer.data(), buffer.size(), timeout);
        if (count > 0) env->SetByteArrayRegion(output, 0, count, reinterpret_cast<jbyte*>(buffer.data()));
        return count;
    } catch (const std::exception& ex) { fail(env, ex); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_writeSerial(
        JNIEnv* env, jclass, jlong handle, jbyteArray input, jint timeout) {
    try { auto data = bytes(env, input, 517); get(serial, handle)->write(data.data(), data.size(), timeout); }
    catch (const std::exception& ex) { fail(env, ex); }
}
extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_closeSerial(
        JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<cabin::SerialPort> port;
    { std::lock_guard<std::mutex> lock(registry_mutex); auto found = serial.find(handle);
      if (found != serial.end()) { port = found->second; serial.erase(found); device_paths.erase(handle); } }
    if (port) port->cancel();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_cabin_hardware_replacement_NativeIo_openI2c(
        JNIEnv* env, jclass, jstring name, jint address) {
    try {
        auto device_path = path(env, name);
        std::lock_guard<std::mutex> lock(registry_mutex);
        check_open(device_path);
        auto device = std::make_shared<cabin::I2cDevice>(device_path, address);
        auto handle = ++sequence; i2c.emplace(handle, device); device_paths.emplace(handle, device_path); return handle;
    } catch (const std::exception& ex) { fail(env, ex); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_writeI2c(
        JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    try { auto data = bytes(env, input, 8192); get(i2c, handle)->write(data.data(), data.size()); }
    catch (const std::exception& ex) { fail(env, ex); }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_cabin_hardware_replacement_NativeIo_readI2c(
        JNIEnv* env, jclass, jlong handle, jbyteArray prefix, jint count) {
    try {
        if (count < 1 || count > 4096) throw std::invalid_argument("Invalid I2C read size");
        auto request = bytes(env, prefix, 4096);
        std::vector<uint8_t> result(static_cast<size_t>(count));
        get(i2c, handle)->read(request.data(), request.size(), result.data(), result.size());
        jbyteArray output = env->NewByteArray(count);
        if (!output) return nullptr;
        env->SetByteArrayRegion(output, 0, count, reinterpret_cast<const jbyte*>(result.data()));
        return output;
    } catch (const std::exception& ex) { fail(env, ex); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_closeI2c(
        JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex); if (i2c.erase(handle)) device_paths.erase(handle);
}

extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_setDspReset(
        JNIEnv* env, jclass, jboolean high) {
    try { cabin::set_dsp_reset(high == JNI_TRUE); }
    catch (const std::exception& ex) { fail(env, ex); }
}

extern "C" JNIEXPORT void JNICALL Java_com_cabin_hardware_replacement_NativeIo_setAmplifierMute(
        JNIEnv* env, jclass, jboolean muted) {
    try { cabin::set_amplifier_mute(muted == JNI_TRUE); }
    catch (const std::exception& ex) { fail(env, ex); }
}
