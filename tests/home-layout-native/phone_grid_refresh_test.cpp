/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "targets/home/home_layout_config.h"
#include "targets/home/tweaks/a64.h"
#include "targets/home/tweaks/ht_plan.h"
#include "nativehook/code_word_write.h"
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace home_layout_engine {
#include "state.inc"

State g_state;
std::mutex g_workMutex;
unsigned wakes=0;
void WakeMonitor() { ++wakes; }
void AcquireSites(State*, bool) {} // Already validated sites, supplied by the fixture.
void RefreshProtectedRanges(const std::vector<AppliedPatch>&) {}
uint64_t NowMs() { return 0; }
std::string DescribePlan(const PlanResult&) { return {}; }
void LogPlanReasons(const PlanResult&, const Config&) {}

bool ReadWord(State* state, uintptr_t address, uint32_t* value) {
    return CodeView(state->image).Word(static_cast<uint32_t>(address-state->image.base),value);
}
bool RecoverWrite(State*, nhk::WordWriteJournal& journal) { return !journal.pending(); }
nhk::WordWriteResult WriteWord(State* state, uintptr_t address, uint32_t expected,
                               uint32_t wanted, uint32_t) {
    uint32_t current=0;
    if(!ReadWord(state,address,&current)||current!=expected)return {};
    std::memcpy(reinterpret_cast<void*>(address),&wanted,sizeof(wanted));
    return {true,{}};
}
#include "engine.inc"
}

namespace {
constexpr char kTag[]="phone-grid-refresh";
std::atomic<int> g_cell_x{0},g_cell_y{0};
std::atomic<bool> g_ready{false};
struct LayoutSites { unsigned x=4,y=8; } sites;
const auto* located=&sites;
unsigned animation_publishes=0;
void reset_binding_retries() {}
void publish_animation_rate(const home_layout::TweaksConfig&) { ++animation_publishes; }
#include "worker.inc"

unsigned failures=0;
void check(bool value,const char* label) {
    std::printf("phone grid %s: %s\n",label,value?"PASS":"FAIL");
    if(!value)++failures;
}
}

int main() {
    using namespace home_layout_engine;
    const auto grid=[] (unsigned cols,unsigned rows) {
        return std::array<uint32_t,4>{a64::MakeMovzRaw(2,cols*2),a64::MakeMovzRaw(3,cols*2),
            a64::MakeMovzRaw(4,rows),a64::MakeMovzRaw(5,rows)};
    };
    const std::array<uint32_t,4> stock{a64::MakeMovzRaw(2,8),a64::MakeMovzRaw(3,10),
        0xcb010004u,0xcb010005u};
    auto mapped=stock;
    auto& state=g_state;
    state.image.base=reinterpret_cast<uintptr_t>(mapped.data());
    state.image.segments[0]={state.image.base,state.image.base+sizeof(mapped),5};
    state.image.segmentCount=1;
    state.imageFound=true;
    state.sites.ok19=state.sites.ok19r=true;
    for(unsigned i=0;i<2;++i){
        state.sites.phoneSiteVa[i]=i*4;
        state.sites.phoneSiteRd[i]=i+2;
        state.sites.phoneRowSiteVa[i]=(i+2)*4;
        state.sites.phoneRowRd[i]=i+4;
    }

    home_layout::Config current;
    current.grid_enabled=true;
    current.cell_x=6;
    current.cell_y=7;
    push_tweaks(current); // Same path as initial worker publication.
    SettleLocked(&state,false);
    check(mapped==grid(6,7)&&state.applied.size()==4,"initial apply");
    if(failures)return 1;
    const auto baseline_wakes=wakes;

    auto latest=current;
    latest.cell_x=7;latest.cell_y=8;
    refresh(current,latest);
    SettleLocked(&state,false);
    check(g_cell_x==7&&g_cell_y==8&&g_ready,"layout bank sees dimensions");
    check(wakes==baseline_wakes+1&&mapped==grid(7,8)&&state.applied.size()==4,"dimensions-only updates tweaks");

    latest.grid_enabled=false; // Keep stored dimensions, change only the enable bit.
    refresh(current,latest);
    SettleLocked(&state,false);
    check(!g_ready,"layout bank sees disable");
    check(wakes==baseline_wakes+2&&mapped==stock&&state.applied.empty()
        &&state.lastPlan.patches.empty()&&!state.restoreBlocked,"disable-only restores exact original words");
    check(animation_publishes==0,"grid-only changes do not publish animation");

    const auto disabled_wakes=wakes;
    refresh(current,latest);
    latest.cell_x=8;
    refresh(current,latest);
    check(wakes==disabled_wakes,"unchanged or inactive dimensions do not wake tweaks");
    latest.grid_enabled=true;
    refresh(current,latest);
    SettleLocked(&state,false);
    check(wakes==disabled_wakes+1&&mapped==grid(8,8),"re-enable applies latest dimensions");
    return failures?1:0;
}
