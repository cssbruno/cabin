#include "cabin_io.hpp"
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cerrno>
#include <iostream>
#include <stdexcept>
namespace { int last_fd=-1, writes=0, result=4; bool expected_high=false, amplifier=false;
void check(bool value,const char* why) { if(!value) throw std::runtime_error(why); }
}
extern "C" int __real_open(const char*,int,...);
extern "C" int __wrap_open(const char* path,int flags,...) {
    check(std::strcmp(path,amplifier?"/sys/fytver/muteAMP":"/sys/fytver/Gpios")==0,"fixed control path");
    check(flags==(O_WRONLY|O_CLOEXEC|O_NOFOLLOW),"open flags");
    last_fd=__real_open("/dev/null",O_WRONLY|O_CLOEXEC); return last_fd;
}
extern "C" ssize_t __wrap_write(int fd,const void* data,size_t size) {
    check(fd==last_fd && size==(amplifier?1u:4u),"complete control write");
    const auto* bytes=static_cast<const unsigned char*>(data);
    if(amplifier) check(bytes[0]==(expected_high?'1':'0'),"amplifier ASCII value");
    else check(bytes[0]==(expected_high?1:0) && bytes[1]==0 && bytes[2]==1 && bytes[3]==0,"little endian GPIO-0 value");
    ++writes; if(result<0) errno=EIO; return result;
}
int main() {
    try {
        for(bool high:{false,true}) { expected_high=high; cabin::set_dsp_reset(high); check(fcntl(last_fd,F_GETFD)==-1,"descriptor closed"); }
        for(int failure:{-1,0,1,3}) {
            result=failure; int before=writes; bool threw=false;
            try { cabin::set_dsp_reset(true); } catch(const std::exception&) { threw=true; }
            check(threw && writes==before+1,"failure without retry"); check(fcntl(last_fd,F_GETFD)==-1,"failed descriptor closed");
        }
        amplifier=true; result=1;
        for(bool muted:{false,true}) { expected_high=muted; cabin::set_amplifier_mute(muted); check(fcntl(last_fd,F_GETFD)==-1,"amplifier descriptor closed"); }
        result=0; bool failed=false;
        try { cabin::set_amplifier_mute(true); } catch(const std::exception&) { failed=true; }
        check(failed,"short amplifier write rejected");
        std::cout<<"DSP reset syscall checks passed without hardware access\n"; return 0;
    } catch(const std::exception& ex) { std::cerr<<ex.what()<<'\n'; return 1; }
}
