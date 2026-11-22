# OS4 status bar signal order regression

This suite compiles the actual production hook with a minimal hook API double and dispatches its registered factory callback. The input names come from the supplied popsicle OS4.0.0.44 SystemUI's `config_statusBarIcons` resource. The real target DEX has `provideStatusBarIconList(Context)`, `mSlots`, and `Slot.mName`; it has no `StatusBarIconList(String[])` constructor.

```sh
python3 tests/os4-statusbar-slot-order/run.py --java /path/to/java --kotlin-home /path/to/kotlinc
```

`--production /path/to/source.kt` runs the same tests against an alternate source file. On OS4, only the factory is hooked; on OS3, neither the factory nor the retired constructor is hooked. The factory callback tests check ordering, original object/list identity, missing and duplicate names, unfamiliar slots, and idempotence. The suite does not install hooks in LSPosed or verify device rendering. Full APK compilation and device testing remain separate requirements.
