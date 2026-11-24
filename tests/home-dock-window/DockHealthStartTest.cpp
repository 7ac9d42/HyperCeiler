/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <pthread.h>
#include <semaphore>
#include <sys/eventfd.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace nhk {
namespace home_safe_mode {
struct Decision { bool blocked=false; };
Decision decision() { return {}; }
}
struct LsposedInlineBackend {
    struct Entries { int (*hook)(void*, void*, void**); int (*unhook)(void*); };
    static LsposedInlineBackend& instance() { static LsposedInlineBackend backend; return backend; }
    void install_entries(Entries) {}
};
}

namespace {
using Hook=int(*)(void*,void*,void**);
using Unhook=int(*)(void*);
Hook hook_function=nullptr;
Unhook unhook_function=nullptr;
std::atomic_bool started{false}, worker_alive{false}, runtime_ready{false};
std::atomic_uint32_t dock_motion_subscribed{0};
int dock_motion_event=-1;
constexpr const char* kTag="DockHealthStartTest";
constexpr int ANDROID_LOG_INFO=4, ANDROID_LOG_WARN=5;
void __android_log_print(int,const char*,const char*,...) {}
void *health_worker(void*) { return nullptr; }
void *motion_worker(void*);
void pause_for(long) {}
void deactivate_dock_motion() { dock_motion_subscribed.store(0); }
int install_hook(void*,void*,void**) { return 0; }

struct CreatedFds {
    std::vector<int> values;
    ~CreatedFds() { for (int fd:values) (void)close(fd); }
} created;
bool fail_eventfd=false;
unsigned health_failures=0, health_attempts=0, transport_starts=0;
std::binary_semaphore deliver{0}, delivered{0};
int transport_fd=-1;
eventfd_t transport_counter=0;
bool read_wake=false;

int fixture_eventfd(unsigned counter,int flags) {
    if(fail_eventfd){errno=EMFILE;return -1;}
    const int fd=eventfd(counter,flags);
    if(fd>=0)created.values.push_back(fd);
    return fd;
}
int fixture_create(pthread_t* thread,const pthread_attr_t*,void *(*entry)(void*),void* arg) {
    *thread=pthread_self();
    if(entry==motion_worker) {
        entry(arg);
        return 0;
    }
    if(entry!=health_worker) return EINVAL;
    ++health_attempts;
    if(health_failures){--health_failures;return EAGAIN;}
    return 0;
}
int fixture_detach(pthread_t) { return 0; }
int fixture_setname(pthread_t,const char*) { return 0; }

void run_dock_motion() {
    ++transport_starts;
    transport_fd=dock_motion_event;
    deliver.release();
    delivered.acquire();
    read_wake=eventfd_read(transport_fd,&transport_counter)==0;
    throw 0; // End the production restart loop after the first transport observation.
}

#define eventfd fixture_eventfd
#define pthread_create fixture_create
#define pthread_detach fixture_detach
#define pthread_setname_np fixture_setname
#include "dock_start.inc"
#undef pthread_setname_np
#undef pthread_detach
#undef pthread_create
#undef eventfd

unsigned failures=0;
void check(bool ok,const char* label) {
    std::printf("Dock health start %s: %s\n",label,ok?"PASS":"FAIL");
    failures+=!ok;
}
}

int main() {
    fail_eventfd=true;
    start_dock_native_motion(install_hook,nullptr);
    check(!started&&!worker_alive&&dock_motion_event<0&&created.values.empty()&&health_attempts==0,
          "eventfd creation failure leaves startup retryable");
    fail_eventfd=false;
    health_failures=4;
    start_dock_native_motion(install_hook,nullptr);
    const int first_fd=dock_motion_event;
    if(first_fd<0)return 1;
    const int status_flags=fcntl(first_fd,F_GETFL);
    const int descriptor_flags=fcntl(first_fd,F_GETFD);
    check(!started&&!worker_alive&&transport_starts==0&&health_attempts==1,
          "health creation failure permits another startup");
    check(status_flags>=0&&(status_flags&O_NONBLOCK)&&descriptor_flags>=0&&(descriptor_flags&FD_CLOEXEC),
          "eventfd retains nonblocking and close-on-exec flags");
    for(unsigned retry=0;retry<3;++retry)start_dock_native_motion(install_hook,nullptr);
    check(created.values.size()==1&&dock_motion_event==first_fd&&health_attempts==4,
          "repeated failures keep exactly one allocated descriptor");

    bool written=false;
    std::thread callback([first_fd,&written]{
        // The producer cached its descriptor before the final startup attempt.
        deliver.acquire();
        written=eventfd_write(first_fd,1)==0;
        delivered.release();
    });
    start_dock_native_motion(install_hook,nullptr);
    callback.join();
    check(transport_starts==1&&health_attempts==5,"successful retry starts transport");
    check(transport_fd==first_fd&&created.values.size()==1,"successful retry does not abandon the original descriptor");
    check(written&&read_wake&&transport_counter==1,"captured producer descriptor still wakes new transport");
    std::printf("Dock eventfd allocations: %zu\n",created.values.size());
    return failures?1:0;
}
