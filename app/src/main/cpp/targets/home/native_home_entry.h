/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <cstdint>

namespace home_native {
using HookFunction = int (*)(void *, void *, void **);
using UnhookFunction = int (*)(void *);
using LibraryLoaded = void (*)(const char *, void *);
struct ApiEntries {
    uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
};
}
extern "C" home_native::LibraryLoaded native_init(const home_native::ApiEntries *entries);
// Local JNI preference and safe-mode check; no Binder, discovery or Dart call.
bool hyperceiler_home_prestart_disabled();
