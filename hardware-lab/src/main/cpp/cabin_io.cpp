#include "cabin_io.hpp"
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <poll.h>
#include <stdexcept>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <termios.h>
#include <unistd.h>

namespace cabin {
namespace {
int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
[[noreturn]] void error(const char* operation) {
    throw std::runtime_error(std::string(operation) + ": " + std::strerror(errno));
}
void timeout(int value) {
    if (value < 1 || value > 5000) throw std::invalid_argument("Timeout must be 1..5000 ms");
}
int open_device(const std::string& path, int extra) {
    if (path.empty() || path[0] != '/' || path.find('\0') != std::string::npos)
        throw std::invalid_argument("Absolute device path required");
    int fd = ::open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW | extra);
    if (fd < 0) error("open device");
    struct stat info{};
    if (fstat(fd, &info) < 0 || !S_ISCHR(info.st_mode)) {
        ::close(fd);
        throw std::runtime_error("Expected a character device");
    }
    return fd;
}
}
SerialPort::SerialPort(const std::string& path, int baud) {
    speed_t speed;
    if (baud == 38400) speed = B38400;
    else if (baud == 115200) speed = B115200;
    else throw std::invalid_argument("Unsupported reference MCU baud");
    fd_ = open_device(path, O_NONBLOCK | O_NOCTTY);
    try {
        // This blocks future opens, but cannot detect a vendor process that opened first.
        if (ioctl(fd_, TIOCEXCL) < 0) error("exclusive serial");
        termios cfg{};
        if (tcgetattr(fd_, &cfg) < 0) error("get serial settings");
        cfmakeraw(&cfg);
        cfg.c_cflag = (cfg.c_cflag & ~(CSIZE | PARENB | CSTOPB | CRTSCTS)) | CS8 | CLOCAL | CREAD;
        cfg.c_cc[VMIN] = 0;
        cfg.c_cc[VTIME] = 0; // poll provides a monotonic deadline and cancellable waits.
        if (cfsetispeed(&cfg, speed) < 0 || cfsetospeed(&cfg, speed) < 0 ||
            tcsetattr(fd_, TCSANOW, &cfg) < 0) error("set serial settings");
        if (pipe2(wake_, O_CLOEXEC | O_NONBLOCK) < 0) error("cancel pipe");
    } catch (...) {
        ioctl(fd_, TIOCNXCL);
        ::close(fd_); fd_ = -1;
        throw;
    }
}
SerialPort::~SerialPort() {
    cancel();
    if (fd_ >= 0) { ioctl(fd_, TIOCNXCL); ::close(fd_); }
    for (int fd : wake_) if (fd >= 0) ::close(fd);
}
void SerialPort::cancel() noexcept {
    if (!cancelled_.exchange(true) && wake_[1] >= 0) {
        const uint8_t byte = 1;
        ssize_t result;
        do { result = ::write(wake_[1], &byte, 1); } while (result < 0 && errno == EINTR);
    }
}
bool SerialPort::wait(short events, int64_t deadline) {
    while (true) {
        if (cancelled_) throw std::runtime_error("Serial cancelled");
        auto remaining = deadline - now_ms();
        if (remaining <= 0) return false;
        pollfd fds[2] = {{fd_, events, 0}, {wake_[0], POLLIN, 0}};
        int result = ::poll(fds, 2, static_cast<int>(remaining));
        if (result < 0) { if (errno == EINTR) continue; error("poll serial"); }
        if (cancelled_ || fds[1].revents) throw std::runtime_error("Serial cancelled");
        if (fds[0].revents & (POLLERR | POLLNVAL)) throw std::runtime_error("Serial disconnected");
        // Drain final readable bytes before reporting hangup on the next operation.
        if (fds[0].revents & events) return true;
        if (fds[0].revents & POLLHUP) throw std::runtime_error("Serial disconnected");
        if (result == 0) return false;
    }
}
int SerialPort::read(uint8_t* data, size_t capacity, int timeout_ms) {
    timeout(timeout_ms);
    if (!data || capacity < 1 || capacity > 4096) throw std::invalid_argument("Invalid read buffer");
    std::lock_guard<std::mutex> lock(reader_);
    int64_t deadline = now_ms() + timeout_ms;
    while (wait(POLLIN, deadline)) {
        ssize_t count = ::read(fd_, data, capacity);
        if (count > 0) return static_cast<int>(count);
        if (count == 0) throw std::runtime_error("Serial EOF");
        if (errno != EAGAIN && errno != EINTR && errno != EWOULDBLOCK) error("read serial");
    }
    return 0;
}
void SerialPort::write(const uint8_t* data, size_t count, int timeout_ms) {
    timeout(timeout_ms);
    if (!data || count < 1 || count > 517) throw std::invalid_argument("Invalid MCU frame size");
    std::lock_guard<std::mutex> lock(writer_);
    int64_t deadline = now_ms() + timeout_ms;
    size_t sent = 0;
    while (sent < count) {
        if (!wait(POLLOUT, deadline)) throw std::runtime_error("Serial write deadline; delivery uncertain");
        ssize_t result = ::write(fd_, data + sent, count - sent);
        if (result > 0) sent += static_cast<size_t>(result);
        else if (result == 0) throw std::runtime_error("Serial write made no progress");
        else if (errno != EAGAIN && errno != EINTR && errno != EWOULDBLOCK) error("write serial");
    }
}
I2cDevice::I2cDevice(const std::string& path, int address) : address_(address) {
    if (address < 0x08 || address > 0x77) throw std::invalid_argument("Invalid 7-bit I2C address");
    fd_ = open_device(path, 0);
    // Do not override a kernel driver with the reference's I2C_SLAVE_FORCE operation.
    if (ioctl(fd_, I2C_SLAVE, address) < 0) {
        int saved = errno; ::close(fd_); fd_ = -1; errno = saved; error("select I2C address");
    }
}
I2cDevice::~I2cDevice() { if (fd_ >= 0) ::close(fd_); }
void I2cDevice::write(const uint8_t* data, size_t count) {
    if (!data || count < 1 || count > 8192) throw std::invalid_argument("Invalid I2C message size");
    std::lock_guard<std::mutex> lock(mutex_);
    ssize_t result = ::write(fd_, data, count);
    // A second write is a new I2C transaction. Never retry a partial/uncertain transfer.
    if (result < 0) error("I2C write; delivery uncertain");
    if (static_cast<size_t>(result) != count) throw std::runtime_error("Short I2C write; delivery uncertain");
}
void I2cDevice::read(const uint8_t* prefix, size_t prefix_count, uint8_t* data, size_t count) {
    if (!prefix || !data || prefix_count < 1 || prefix_count > 4096 || count < 1 || count > 4096)
        throw std::invalid_argument("Invalid I2C read size");
    std::lock_guard<std::mutex> lock(mutex_);
    // One repeated-start transaction: a STOP between messages changes the protocol.
    i2c_msg messages[2]{};
    messages[0].addr = static_cast<__u16>(address_);
    messages[0].len = static_cast<__u16>(prefix_count);
    messages[0].buf = const_cast<uint8_t*>(prefix);
    messages[1].addr = static_cast<__u16>(address_);
    messages[1].flags = I2C_M_RD;
    messages[1].len = static_cast<__u16>(count);
    messages[1].buf = data;
    i2c_rdwr_ioctl_data transfer{messages, 2};
    int result = ioctl(fd_, I2C_RDWR, &transfer);
    // Do not retry an interrupted/partial transaction or publish its output.
    if (result < 0) error("I2C combined read");
    if (result != 2) throw std::runtime_error("Incomplete I2C combined read");
}
void set_dsp_reset(bool high) {
    // ToolsJni cmd 251, GPIO index 0: little-endian 0x00010000 | value.
    const uint8_t value[4] = {static_cast<uint8_t>(high ? 1 : 0), 0, 1, 0};
    int fd = ::open("/sys/fytver/Gpios", O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) error("open DSP reset control");
    ssize_t count = ::write(fd, value, sizeof(value));
    int saved = errno;
    ::close(fd);
    errno = saved;
    if (count < 0) error("DSP reset write; state uncertain");
    if (count != 4) throw std::runtime_error("Short DSP reset write; state uncertain");
}
void set_amplifier_mute(bool muted) {
    const char value = muted ? '1' : '0';
    int fd = ::open("/sys/fytver/muteAMP", O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) error("open amplifier mute control");
    ssize_t count = ::write(fd, &value, 1);
    int saved = errno; ::close(fd); errno = saved;
    if (count < 0) error("amplifier mute write; state uncertain");
    if (count != 1) throw std::runtime_error("Short amplifier mute write; state uncertain");
}
}
