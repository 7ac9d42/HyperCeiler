# Hyos control and authoritative preference regressions

These tests compile actual production sources. They never inject the launcher, communicate with a device, or replace production algorithms with copied ones.

The old Home companion Native controller is retired. The APK still serves HCY1 and HCS1 replies pending an upgrade compatibility decision.

From the source directory:

```sh
python3 tests/hyos-control/run_java.py --output /new/output/java
python3 tests/home-startup-snapshot/run_java.py --output /new/output/startup
python3 tests/hyos-control/run_legacy_java.py --output /new/output/legacy
```

The Java runners use JDK 21 or newer; `JAVA_HOME` selects it.

`HyosEndpointTest.java` compiles the actual endpoint, actual `HomeLayoutPrefsSnapshot` provider row builder, and shared framework gate. The ContentResolver fixture delegates row construction to that actual builder using one `SharedPreferences.getAll()` snapshot. Tests cover schema/typed fields, freshness, identity binding, gates and metadata, no stale restored authorization, caller checks, failed provider reads, and revision/retirement races. The extended Java suite is maintained with the shared framework gate; use its output for the current count.

`tests/home-startup-snapshot/run_java.py` runs `CombinedSnapshotTest` against the production HCS1 endpoint. It checks the wire reply and provider publication, and writes the Java-generated corpus to `<output>/corpus`.

`run_legacy_java.py` runs the three pre-existing layout tests. The legacy fixture was extended to serve text/long metadata with real types; all-key fault injection remains enabled. Its provider-shape assertion now follows the actual schema's exact number of keys rather than the old hard-coded 37-column size. The legacy endpoint test, ten idle snapshot cases and ten atomic reload cases must still pass.
