# OS4 native launcher validation

These host tests read launcher files and exercise module code. They do not load,
execute, install, or patch the supplied launcher. A successful offline check does
not establish rendering or gesture correctness on a phone.

Use `CXX=clang++` or `CXX=g++` (C++20 compiler required). The symbol tests also
need a C compiler and Python 3. Python uses only the standard library.

```sh
CXX=g++ bash tests/home-layout-native/run.sh
CXX=g++ bash tests/home-layout-native/run.sh /path/libapp_launcher.so /path/libapp.so
CXX=g++ bash tests/home-layout-native/run_tweaks_fixture.sh /path/libapp.so
NHK_FLUTTER_LIB=/path/libflutter.so CXX=g++ bash tests/run_host_native_tests.sh /path/libapp.so
```

`run.sh` covers geometry, folder state snapshots, indicator policy, capacity
rollback and symbol cache reset. Given actual library files, it independently
reads `.gnu_debugdata` with Python's XZ reader, obtains named function addresses,
and compares those with the production structural resolvers. There are no
saved OTA addresses in the expectation path. Missing or ambiguous symbols and
unresolved fixtures fail the test; they are not reported as successful skips.

`run_tweaks_fixture.sh` builds the production symbol index and patch planner. It
maps ELF load segments into ordinary data storage, checks all seven tweak
families separately, and verifies that proposed original words equal the ROM
bytes. It also runs the production workspace, dock and indicator compatibility
predicates copied verbatim at build time, plus the capacity/folder byte guards.
Failures are reported per feature and make the command exit nonzero. A failed
optional or device-inapplicable family does not imply every native function is
broken; the individual lines identify what was actually checked.

`python3 tests/home-layout-native/run_phone_grid_refresh.py` checks the production
phone-grid refresh branch, patch planner, and rollback against prevalidated
synthetic sites in ordinary host memory. It requires Linux/WSL and no ROM files.
It confirms dimension changes, exact restoration on disable, and re-enable;
it does not validate ROM site discovery or execute any patched instructions.

`python3 tests/home-layout-native/run_panel_gate.py` runs the layout worker's
production panel gate with a fake clock and transport, with and without Dock.
It checks that a failed IPC after a hidden reply permits maintenance without
speeding up the 10-second probe cadence. It does not exercise real Binder.

The real-image fixture runner finishes by clearing and reloading the production symbol
index, verifying that the same targets remain available after launcher child
preparation. `run_symbol_reset.sh` isolates the complementary cached-failure
case: repeated calls in one process must remain cached, while a child reset must
allow a fresh read. Before clearing `attempted_` in `SymbolIndex::ResetForTest`,
the second read was permanently blocked. That method is also used by production
`HomeLayoutEnginePrepareForLauncherChild`, despite its historical test-only name.

The ARM64 trampoline execution harness in `run_host_native_tests.sh` requires an
ARM64 Linux host. It is explicitly skipped on an x86-64 host. Building the native
Android library confirms compilation and linking, not trampoline behavior or
successful LSPosed injection.

For the supplied OS4.0.0.44 / launcher7719 artifact, the production planner and all
18 layout guards pass after the exact call variants are applied. The separate
legacy Dart structural diagnostic still fails its labelled-log aggregator
assertion. That diagnostic uses an old class-ID literal and is not called by the
shipped layout hook path. Keep this failure visible when reporting fixture
results; do not summarize all native tests as passing.
