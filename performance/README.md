# Preview data path

Preview JPEGs are limited to 8 MiB at the HTTP reader as well as at decode.
An oversized Content-Length is rejected before opening the response body;
chunked responses stop after at most one byte beyond the limit, without waiting
for EOF. The HTTP connection remains covered by lifecycle cancellation.

Focus peaking stores luminance in an unsigned byte representation. Its
central-difference formula, 512 Ki-pixel processing budget, threshold, and
transparent border remain unchanged.

## JVM benchmark

Build the Kotlin classes, then point the benchmark at those classes and the
Kotlin standard library used by the build:

```bash
./gradlew compileDebugKotlin
KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar
java -Xmx256m \
  -cp "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes:$KOTLIN_STDLIB" \
  performance/FocusPeakingBenchmark.java
```

Run the same harness against separately compiled baseline and candidate
checkouts with the same JVM and heap settings. It warms 100 calls, records
11 samples of 10 calls, checks output against the central-difference reference,
and uses JVM per-thread allocation counters. The Java reference is used only
for correctness; timing always invokes the compiled Kotlin implementation.

Local Linux / OpenJDK 21 measurements on 2026-09-08, with 1024 x 512 pixels:

| Version | Bytes allocated per frame | Median compute time | Mask hash |
| --- | ---: | ---: | ---: |
| Main at 7f0d20b | 4,194,363 | 2.0453 ms | -1808897385 |
| Compact luminance | 2,621,499 | 1.9805 ms | -1808897385 |

This removes 1.5 MiB of allocation per frame at the processing budget, about
37.5% of this computation's allocations. The timings are similar and are not
a CI speed threshold. These measurements exclude JPEG decode, Compose drawing,
network I/O, and Android runtime behavior; they do not claim an Android FPS
or battery-life improvement.

## Regression coverage

- The network tests cover oversized headers, chunked overflow without EOF,
  exact-limit JPEGs, short reads, and lifecycle cancellation.
- A never-ending input stream verifies that the reader consumes no more than
  the byte limit plus one byte.
- Seeded pixel tests compare every output pixel across small/border dimensions,
  ordinary preview sizes, unsigned luminance values, and thresholds 0, 1, 72,
  and 510.
