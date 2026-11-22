/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Host regression for the shared bounded maps parser used by safe_read.
#include "nativehook/memory_read.h"
#include <cstdio>
#include <cstdlib>

#define CHECK(expression) do { if (!(expression)) { \
    std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #expression); std::exit(1); \
} } while (false)

int main() {
    const std::string first = "1000-2000 r--p 00000000 08:01 12\n";
    const std::string second = "2000-3000 rw-p 00001000 08:01 12\n";
    std::vector<nhk::ReadRegion> regions;
    CHECK(nhk::ParseReadableRanges(first + second, 0x1fff, 2, regions));
    CHECK(regions.size() == 2 && regions[0].end == regions[1].begin);
    CHECK(regions[1].offset == 0x1000 && regions[1].inode == 12);

    const auto complete = regions;
    CHECK(!nhk::ParseReadableRanges(first, 0x1fff, 2, regions));
    CHECK(!nhk::ParseReadableRanges(first + "2001-3000 r--p 00001000 08:01 12\n", 0x1fff, 3, regions));
    CHECK(!nhk::ParseReadableRanges(first + "2000-3000 ---p 00001000 08:01 12\n", 0x1fff, 2, regions));
    CHECK(!nhk::ParseReadableRanges(first + second, UINTPTR_MAX - 1, 2, regions));
    CHECK(regions == complete);
    std::puts("PASS bounded maps reads require complete readable ranges and preserve output on failure");
    return 0;
}
