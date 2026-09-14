#include "cabin_io.hpp"
#include <linux/i2c.h>
#include <linux/i2c-dev.h>
#include <cstdarg>
#include <cerrno>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
int completion = 2, calls = 0;
void check(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
template<class F> void rejects(F action) {
    bool rejected = false;
    try { action(); } catch (const std::exception&) { rejected = true; }
    check(rejected, "expected rejection");
}
}
// Inspect the actual production ioctl request, without any I2C hardware access.
extern "C" int __wrap_ioctl(int, unsigned long request, ...) {
    va_list args; va_start(args, request);
    if (request == I2C_SLAVE) {
        int address = va_arg(args, int); va_end(args);
        check(address == 0x1c, "address selection"); return 0;
    }
    auto* transfer = va_arg(args, i2c_rdwr_ioctl_data*); va_end(args);
    check(request == I2C_RDWR, "combined transfer required");
    check(transfer->nmsgs == 2, "two messages required");
    const auto& prefix = transfer->msgs[0]; const auto& response = transfer->msgs[1];
    check(prefix.addr == 0x1c && response.addr == 0x1c, "same device address");
    check(prefix.flags == 0 && response.flags == I2C_M_RD, "write then repeated-start read");
    check(prefix.len == 3 && prefix.buf[0] == 0x40 && prefix.buf[1] == 0 && prefix.buf[2] == 0xc0, "status prefix");
    check(response.len == 1, "one status byte");
    response.buf[0] = 4; ++calls;
    if (completion < 0) errno = EIO;
    return completion;
}
int main() {
    try {
        cabin::I2cDevice device("/dev/null",0x1c);
        std::vector<uint8_t> program(4603,0);
        device.write(program.data(),program.size());
        rejects([&] { device.write(program.data(),8193); });
        const uint8_t prefix[] = {0x40,0,0xc0}; uint8_t status = 0;
        device.read(prefix,3,&status,1); check(status == 4 && calls == 1,"status result");
        for (int result : {0,1,-1}) {
            completion = result; int before = calls;
            rejects([&] { device.read(prefix,3,&status,1); }); check(calls == before+1,"failed reads must not retry");
        }
        int before = calls;
        rejects([&] { device.read(prefix,0,&status,1); });
        rejects([&] { device.read(prefix,4097,&status,1); });
        rejects([&] { device.read(prefix,3,&status,4097); });
        rejects([&] { device.read(nullptr,3,&status,1); });
        check(calls == before,"validate before ioctl");
        std::cout << "I2C combined read: wire request, result checking, no retries and bounds passed\n";
        return 0;
    } catch(const std::exception& ex) { std::cerr << ex.what() << '\n'; return 1; }
}
