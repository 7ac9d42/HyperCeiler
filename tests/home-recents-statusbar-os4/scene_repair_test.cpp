/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "nativehook/diagnostics.h"
#include <iostream>

// The production observer/health publication runs unchanged on the host;
// only Android logging and the assembly continuation are absent.
constexpr int ANDROID_LOG_INFO=4;
static void __android_log_print(int,const char*,const char*,...) {}
namespace nhk::android_diagnostics {
void bank(const char*,size_t,const DiagnosticEvent&,const char*) {}
void emit(const char*,const char*,const char*,const char*,const char*,const char*,const char*,const char*) {}
void counter(const char*,const char*,uint64_t) {}
}
extern "C" { int dock_motion_event=-1; }
extern "C" void hc_recent_dart_state_entry() {}
#include "targets/home/home_recent_statusbar.cpp"

namespace home_recent_bar {
void run_repair_test(){
    auto check=[](bool ok,const char* what){if(!ok){std::cerr<<what<<'\n';std::abort();}};
    uint8_t native[3]{0,1,0};
    for(unsigned i=0;i<3;++i)flags[i].store(reinterpret_cast<uintptr_t>(&native[i]));
    configure(true);
    auto healthy=[] {return true;};
    auto unexpected_repair=[] {std::abort();return false;};
    bool startup_live=false;
    check(refresh_bank([&]{return startup_live;},[&]{startup_live=true;return true;})&&(packed()&15)==10,
        "initial Rust fallback lost");

    uint64_t frame[2]{},thread[2]{},pool[2]{},table[2]{},receiver[2]{};
    dart_plan.receiver_frame=-8;dart_plan.static_table=8;dart_plan.overview=8;dart_plan.sentinel_pool=8;dart_plan.state_field=7;
    frame[0]=reinterpret_cast<uintptr_t>(receiver)|1;
    thread[1]=reinterpret_cast<uintptr_t>(table);table[1]=0x12001;pool[1]=0x14001;receiver[1]=table[1];
    dart_contract.store(true);
    auto observe_dart=[&]{hc_recent_dart_state_observe(reinterpret_cast<uintptr_t>(&frame[1]),
        reinterpret_cast<uintptr_t>(thread),reinterpret_cast<uintptr_t>(pool));};
    observe_dart();
    const auto overview=packed();
    check((overview&15)==9,"could not establish initial Dart Overview observation");
    check(refresh_bank(healthy,unexpected_repair)&&packed()==overview,"healthy bank lost a valid keepalive");

    // The underlying state exits Overview while the observation site is lost.
    // No Dart observer runs for this write. Rust's main bit is still stale.
    receiver[1]=0x16001;
    const auto old_epoch=bank_epoch.load();
    bool live=false;
    const auto is_live=[&]{return live;};
    const auto repair=[&]{
        check((packed()&15)==0,"repair started before withdrawal of the old scene");
        check(!commit_dart(old_epoch,3),"repair accepted a callback with old Dart state");
        live=true;return true;
    };
    check(refresh_bank(is_live,repair),"repair should make the bank live");
    const auto heartbeat=packed(); // Same packed source the HCT worker reads.
    check((heartbeat&15)==0&&(heartbeat>>4)>(overview>>4),"repair heartbeat can renew a missed Dart exit");
    check(!commit_dart(old_epoch,3)&&packed()==heartbeat,"late callback crossed the repaired bank boundary");
    check(refresh_bank(healthy,unexpected_repair)&&packed()==heartbeat,"healthy maintenance resurrected an invalid scene");
    observe_dart();
    check((packed()&15)==8,"fresh Dart exit did not recover; stale Rust main bit should stay suppressed");
    native[2]=1;snapshot();
    check((packed()&15)==12,"dedicated Rust recents window no longer works with fresh Dart state");
    receiver[1]=table[1];observe_dart();
    check((packed()&15)==13,"fresh Dart reentry did not recover");
    std::cout<<"Recents scene repair: missed exit heartbeat neutral; initial and dedicated Rust routes preserved\n";
}
}
int main(){home_recent_bar::run_repair_test();}
