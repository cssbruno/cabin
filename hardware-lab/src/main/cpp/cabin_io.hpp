#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

namespace cabin {
void set_dsp_reset(bool high);
void set_amplifier_mute(bool muted);
// Cancellation wakes poll; descriptors live until all active callers release the object.
class SerialPort {
public:
    SerialPort(const std::string& path, int baud);
    ~SerialPort();
    SerialPort(const SerialPort&) = delete;
    SerialPort& operator=(const SerialPort&) = delete;
    int read(uint8_t* data, size_t capacity, int timeout_ms);
    void write(const uint8_t* data, size_t count, int timeout_ms);
    void cancel() noexcept;
private:
    int fd_ = -1, wake_[2] = {-1, -1};
    std::atomic<bool> cancelled_{false};
    std::mutex reader_, writer_;
    bool wait(short events, int64_t deadline);
};

class I2cDevice {
public:
    I2cDevice(const std::string& path, int address);
    ~I2cDevice();
    I2cDevice(const I2cDevice&) = delete;
    void write(const uint8_t* data, size_t count);
    void read(const uint8_t* prefix, size_t prefix_count, uint8_t* data, size_t count);
private:
    int fd_ = -1, address_ = 0;
    std::mutex mutex_;
};
}
