#include "cabin_io.hpp"
#include <array>
#include <chrono>
#include <future>
#include <iostream>
#include <pty.h>
#include <stdexcept>
#include <termios.h>
#include <unistd.h>

static void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template <class F> static void rejects(F action) {
    bool threw = false; try { action(); } catch (const std::exception&) { threw = true; }
    check(threw, "Expected failure");
}
struct Pty {
    int master, slave; char path[128]{};
    Pty() { check(openpty(&master, &slave, path, nullptr, nullptr) == 0, "openpty"); }
    ~Pty() { if (master >= 0) close(master); close(slave); }
};
int main() {
    try {
        rejects([] { cabin::SerialPort port("/dev/null", 123); });
        rejects([] { cabin::SerialPort port("/dev/null", 38400); });
        rejects([] { cabin::I2cDevice device("/dev/null", 0x1c); });
        {
            Pty tty; cabin::SerialPort port(tty.path, 115200);
            std::array<uint8_t, 512> buffer{};
            check(port.read(buffer.data(), buffer.size(), 25) == 0, "idle timeout");
            const uint8_t packet[] = {0x88, 0x55, 0, 2, 0xc1, 4, 0xc7};
            check(::write(tty.master, packet, sizeof(packet)) == sizeof(packet), "feed capture");
            check(port.read(buffer.data(), buffer.size(), 500) == sizeof(packet), "receive bytes");
            check(std::equal(std::begin(packet), std::end(packet), buffer.begin()), "byte accuracy");
            port.write(packet, sizeof(packet), 500);
            check(::read(tty.master, buffer.data(), buffer.size()) == sizeof(packet), "send bytes");
            check(std::equal(std::begin(packet), std::end(packet), buffer.begin()), "write accuracy");
            rejects([&] { port.read(buffer.data(), 0, 20); });
            rejects([&] { port.write(buffer.data(), 518, 20); });
            auto pending = std::async(std::launch::async, [&] {
                try { port.read(buffer.data(), buffer.size(), 5000); return false; }
                catch (const std::exception&) { return true; }
            });
            port.cancel();
            check(pending.wait_for(std::chrono::milliseconds(500)) == std::future_status::ready, "cancel latency");
            check(pending.get(), "cancel result");
            rejects([&] { port.write(packet, sizeof(packet), 20); });
        }
        {
            Pty tty; cabin::SerialPort port(tty.path, 38400);
            close(tty.master); tty.master = -1;
            uint8_t byte;
            rejects([&] { port.read(&byte, 1, 500); });
        }
        {
            Pty tty; cabin::SerialPort port(tty.path, 38400);
            std::array<uint8_t, 517> data{};
            // Fill the PTY output queue without draining it: write must meet its deadline.
            bool timedOut = false;
            for (int i = 0; i < 1024 && !timedOut; ++i) {
                try { port.write(data.data(), data.size(), 10); }
                catch (const std::exception&) { timedOut = true; }
            }
            check(timedOut, "bounded blocked writes");
        }
        std::cout << "Native serial: idle, bytes, cancellation, hangup, bounds and blocked-write checks passed\n";
        return 0;
    } catch (const std::exception& ex) { std::cerr << ex.what() << '\n'; return 1; }
}
