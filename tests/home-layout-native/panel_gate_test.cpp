/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include <cstdint>
#include <cstdio>
#include <vector>

namespace {
enum class Reply { Unavailable, Hidden, Visible };
struct Stop {};
struct Fixture {
    std::vector<Reply> replies;
    std::vector<uint64_t> probes, maintenance, config_reads;
    uint64_t now=100;
    bool dock_cache=true;
    bool overwrite_shared_cache=false;
};
Fixture fixture;
unsigned failures=0;

uint64_t monotonic_ms() { return fixture.now; }
void delay_ms(uint64_t delay) {
    fixture.now+=delay;
    if(fixture.now>40000)throw Stop{};
}
bool refresh_dock_screen_state(bool &drawable) {
    auto index=fixture.probes.size();
    fixture.probes.push_back(fixture.now);
    const auto reply=index<fixture.replies.size()?fixture.replies[index]:Reply::Visible;
    if(reply==Reply::Unavailable)return false;
    drawable=reply==Reply::Visible;
    fixture.dock_cache=fixture.overwrite_shared_cache?!drawable:drawable;
    return true;
}
bool refresh_dock_screen_state() { bool drawable; return refresh_dock_screen_state(drawable); }
bool dock_motion_screen_active() { return fixture.dock_cache; }
void on_maintenance(bool read_config) {
    fixture.maintenance.push_back(fixture.now);
    if(read_config)fixture.config_reads.push_back(fixture.now);
    if(fixture.maintenance.size()==5)throw Stop{};
}
#include "panel_gate.inc"

void run(std::vector<Reply> replies, bool overwrite_shared_cache=false) {
    fixture=Fixture{.replies=replies,.overwrite_shared_cache=overwrite_shared_cache};
    try { run_passes(); } catch(const Stop&) {}
}
void check(bool ok,const char* label) {
    std::printf("layout panel %s: %s\n",label,ok?"PASS":"FAIL");
    failures+=!ok;
}
}

int main() {
#if defined(HYPERCEILER_DOCK_NATIVE_MOTION)
    run({Reply::Hidden,Reply::Unavailable,Reply::Unavailable,Reply::Visible});
    check(!fixture.maintenance.empty()&&fixture.maintenance.front()==10600,
          "failed IPC after hidden allows a maintenance pass");
    check(!fixture.config_reads.empty()&&fixture.config_reads.front()==20600,
          "repeated failure still permits the normal config branch");
    check(fixture.probes==std::vector<uint64_t>{100,10100,20100,30100,31100},
          "known hidden failure retries at 10 seconds until visible");
    check(!fixture.maintenance.empty()&&fixture.maintenance.back()==31600,
          "fresh visible reply resumes the 500 ms cadence");
    run({Reply::Hidden,Reply::Hidden,Reply::Visible});
    check(fixture.probes.size()>=3&&fixture.probes[0]==100&&fixture.probes[1]==10100
          &&fixture.probes[2]==20100&&!fixture.maintenance.empty()&&fixture.maintenance.front()==20600,
          "fresh hidden reply still skips maintenance");
    run({Reply::Unavailable,Reply::Unavailable,Reply::Unavailable});
    check(fixture.probes==std::vector<uint64_t>{100,1100,2100}
          &&fixture.maintenance==std::vector<uint64_t>{600,1100,1600,2100,2600},
          "unsupported endpoint keeps the prior fast fallback");
    run({Reply::Hidden,Reply::Visible},true);
    check(fixture.probes.size()>=2&&fixture.probes[1]==10100
          &&!fixture.maintenance.empty()&&fixture.maintenance.front()==10600,
          "another Dock writer cannot substitute the shared cache for this reply");
#else
    run({});
    check(fixture.probes.empty()&&fixture.maintenance==std::vector<uint64_t>{600,1100,1600,2100,2600},
          "non-Dock build maintains without panel IPC");
#endif
    return failures?1:0;
}
