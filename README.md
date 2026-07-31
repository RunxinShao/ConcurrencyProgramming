# Concurrency & Multithreading — Coding Practices

A hands-on exploration of parallel and concurrent programming on the JVM (Java, JDK 26). Each lab implements a classic problem from scratch, then **measures** how design choices affect performance on real hardware — an 18-core Apple M5 Max (6 Super + 12 Performance Cores) — and writes up the results with the underlying reasoning (Amdahl's Law, lock contention, load balancing, the compute-bound vs. blocking-bound divide).

**What's inside:**

- **Lab 1 — Parallel mean** ([`parallelcomputation`](src/parallelcomputation)): summing 15M `BigInteger`s across N threads; thread-count scaling, the Super/Performance core knee, and a zero-copy slicing optimization that lifts the Amdahl ceiling from ~7.5× to ~15.7×.
- **Lab 2 — Matrix multiplication** ([`matrixmultiplication`](src/matrixmultiplication)): static partitioning (lock-free) vs. dynamic work-stealing (`synchronized` cursor); the `SECTION_SIZE` contention-vs-balance trade-off; plus an **async-profiler** flame graph that pinpoints the naive multiply loop and a **~4.2× i-k-j cache-locality fix**.
- **Lab 3 — Producer–consumer** ([`ProducerConsumerPattern`](src/ProducerConsumerPattern)): a sliced buffer with one semaphore per slice; throughput vs. buffer size, semaphore count, and producer/consumer ratio ([interactive charts](viz/producer-consumer.html)).
- **Dining philosophers** ([`diningphilosophersolutions`](src/diningphilosophersolutions)): deadlock and two fixes — resource ordering (`synchronized`) vs. back-off (`tryLock`).
- **Thread pool** ([`ThreadPool`](src/ThreadPool)): a fixed-size pool built from scratch on a `ReentrantLock` + `Condition` work queue — the classic wait/notify producer-consumer, applied to *tasks*.

Each section below has the tables, charts, and analysis. Measurements are demonstrative (preemptive scheduler, JIT/GC noise), so the **trends** — not the absolute numbers — are the takeaway.

## Lab 1 Performance Test Summary

**Hardware:** Apple M5 Max (top-spec) — 18-core CPU comprising **6 Super Cores + 12 Performance Cores**. No hyper-threading, so each core runs exactly one thread at a time.

> **Note:** M5 Max uses Apple's newer naming convention — Super Cores are the fastest tier, and the 12 Performance Cores are a new mid-tier optimized for efficient multithreaded work, distinct from the classic Efficiency Cores found in M1–M4 generations. The heterogeneous speed gap between the two tiers still applies.

**Workload:** Parallel mean of 15,000,000 `BigInteger` values, split into N equal slices, one thread per slice. Each configuration was run 10 times and averaged.

### Results

| Threads / Slices | Avg time | Speedup (vs 1) | Efficiency | Core placement |
|---|---|---|---|---|
| 1  | 150 ms | 1.00× | 100% | 1 Super Core |
| 2  | 79 ms  | 1.90× | 95%  | 2 Super Cores |
| 4  | 38 ms  | 3.95× | 99%  | 4 Super Cores |
| 8  | 29 ms  | 5.17× | 65%  | 6 Super + 2 Performance |
| 16 | 21 ms  | 7.14× | 45%  | 6 Super + 10 Performance |
| 32 | 20 ms  | 7.50× | 23%  | oversubscribed (> 18 cores) |

### Analysis

**1. Near-linear scaling (1 → 4 threads).** The task is embarrassingly parallel — slices are summed independently with no shared state, no locks, no contention. Up to 4 threads, everything fits inside the 6 Super Cores, giving ~99% efficiency.

**2. Knee at 8 threads — the 6-Super-Core limit.** Efficiency drops from 99% to 65% at 8 threads. This is exactly where thread count exceeds the 6 Super Cores: the 2 extra threads spill onto the slower Performance Cores. Since the program `join()`s all threads, total time is bounded by the *slowest* thread — so the Performance-Core threads become the bottleneck. (A back-of-envelope estimate, `150/8 × ~1.5 Super/Performance speed ratio ≈ 28 ms`, matches the measured 29 ms.)

**3. Diminishing returns (8 → 16).** With 16 threads (6 on Super Cores, 10 on Performance Cores), additional gains come from *more* cores, not *faster* cores. Most work now runs on the slower tier, so efficiency keeps falling.

**4. Plateau at 32 (oversubscription).** Beyond the 18 physical cores, extra threads don't add parallelism — cores just time-slice between threads, adding context-switch overhead. 16 → 32 barely moves (21 → 20 ms).

**5. Amdahl's Law ceiling (~7.5×).** The speedup flattens around 7.5× regardless of thread count. The serial bottleneck is `getSlicedData`, which single-threadedly copies all 15M elements every run (~13% of the 1-thread time). Amdahl's Law predicts a max speedup of `1 / 0.13 ≈ 7.7×`, matching the observed plateau almost exactly.

### Takeaway

On this M5 Max, the sweet spot is **~4–6 threads**, aligned with the 6 Super Cores. Past that, the heterogeneous Super/Performance-core architecture plus a `join`-on-slowest design causes sharp efficiency loss, and the serial slicing step caps the overall speedup at ~7.5× — a core-count-independent limit set by Amdahl's Law. To push further, eliminate the serial copy (use offset/length index ranges so threads read the original array directly).

## Optimization: Zero-Copy Slicing

Following the takeaway above, the serial slicing step was removed. Instead of copying each slice into a fresh array on the main thread, every worker now receives `(sharedArray, start, length)` and reads its own contiguous range **directly from the original array** — no data is copied.

This is thread-safe: all threads only **read** the shared array (no thread writes it), and their ranges are non-overlapping. A data race requires at least one writer, so read-only sharing needs no locks.

### Results — before vs after

| Threads / Slices | Before (copy) | After (zero-copy) | Speedup vs 1 (after) |
|---|---|---|---|
| 1  | 150 ms | 141 ms | 1.00× |
| 2  | 79 ms  | 63 ms  | 2.24× |
| 4  | 38 ms  | 22 ms  | 6.4× |
| 8  | 29 ms  | 12 ms  | 11.75× |
| 16 | 21 ms  | 9 ms   | 15.7× |
| 32 | 20 ms  | 9 ms   | 15.7× |

### Why it got faster

Two distinct effects combine:

**1. Less total work (absolute times drop across the board).** The ~15M-element copy is gone entirely, so even a single thread improves (150 → 141 ms) despite having no parallelism to gain from — the work itself shrank.

**2. Smaller serial fraction → higher Amdahl ceiling (this is the big one).** Amdahl's Law caps max speedup at `1 / serial_fraction`. Before, the serial copy was ~13% of the 1-thread time, capping speedup at `1 / 0.13 ≈ 7.7×` — which is exactly where the old numbers plateaued. Removing it drives the serial fraction toward ~0, lifting the ceiling toward the physical core count. The measured speedup now reaches **~15.7×** (approaching the 18-core limit) instead of stalling at 7.5×, and the previously-flat 8→16 region regains near-linear gains.

> Note: these are demonstrative measurements, not a rigorous benchmark. The OS scheduler is preemptive (threads can be interrupted or migrated between cores), and JIT warmup / GC pauses add noise, so the near-/super-linear figures past 4 threads carry measurement error. The **trend** — a much higher speedup ceiling once the serial copy is removed — is the robust, reproducible result.

## Lab 2 Matrix Multiplication — Discussion Experiments

**Hardware:** same 18-core machine (6 Super Cores + 12 Performance Cores), JDK 26.

Two parallelization strategies for multiplying `N/2` pairs of matrices held in a shared buffer:

- **Task 1 — static partitioning (lock-free):** the work range is split up front, each worker owns a fixed `[leftIndex, leftIndex + count)` interval, reads the shared buffer, and writes into its own private `res` list. No shared mutable state, so no locks.
- **Task 2 — dynamic work-stealing:** workers pull `SECTION_SIZE`-sized chunks at runtime via a `synchronized getNextSection()` cursor. Self-balancing, but every fetch contends on one lock.

All timings use a JIT warm-up pass and report the steady-state run.

### Task 1 — A. Varying `NUM_OF_WORKERS` (dim = 200, 500 pairs, compute-bound)

| Workers | Time | Speedup |
|--------:|-----:|--------:|
| 1     | 1682 ms | 1.0× |
| 2     | 903 ms  | 1.9× |
| 4     | 471 ms  | 3.6× |
| 8     | 296 ms  | 5.7× |
| 16    | 173 ms  | 9.7× |
| 1000  | 151 ms  | 11.1× |
| 10000 | 344 ms  | 4.9× |

Speedup climbs but stays **sub-linear** (16 threads → 9.7×, not 16×) because of thread scheduling overhead, memory bandwidth limits, and 500 pairs not dividing evenly across workers. It saturates near the physical core count (18). Going to 1000 threads squeezes out a hair more by hiding tail load-imbalance, but **10000 threads slow down to 344 ms** — for compute-bound work, far more threads than cores buys only creation and context-switch overhead.

### Task 1 — B. Varying `DIMENSION` (single thread, testing the "double → 8×" law)

Small dims (amplified to 200,000 pairs to be measurable):

| dim | Time | Ratio |
|----:|-----:|------:|
| 2 | 15 ms | — |
| 4 | 29 ms | 1.9× |
| 8 | 76 ms | 2.6× |

Large dims (2000 pairs each):

| dim | Time | Ratio |
|----:|-----:|------:|
| 32  | 23 ms   | — |
| 64  | 174 ms  | 7.5× |
| 128 | 1596 ms | 9.1× |

Matrix multiply is O(dim³), so doubling the dimension should cost ~8×. But that law is **asymptotic**: at small dims the per-pair fixed costs (allocating the result array, `ArrayList.add`, loop setup — all O(dim²) or constant) dominate, so the ratio is only 1.9–2.6×. Once the arithmetic dominates (32 → 64 → 128), the ratio reaches 7.5× then 9.1× (slightly above 8× as cache locality degrades).

### Task 1 — C. Adding `Thread.sleep(10)` in the loop (dim = 10, 500 pairs, 10 ms per pair)

| Workers | Time |
|--------:|-----:|
| 1   | 6096 ms |
| 2   | 3034 ms |
| 4   | 1517 ms |
| 8   | 757 ms |
| 16  | 391 ms |
| 32  | 196 ms |
| 64  | 102 ms |
| 500 | 22 ms |
| 1000| 25 ms |

This is the key contrast. Serial sleep time ≈ 500 × 10 ms = 5000 ms, and adding threads **halves the time almost perfectly** — and keeps helping *far past* 18 cores (32 → 196, 64 → 102, 500 → 22 ms). `sleep` simulates **blocking / I/O**: a sleeping thread holds no CPU, so oversubscription pays off. This is exactly the dividing line: **compute-bound → threads ≈ cores; blocking-bound → many more threads than cores wins.**

### Task 2 — A. Varying `SECTION_SIZE` (18 workers, 500,000 pairs, dim = 10, compute-bound)

| SECTION_SIZE | Time | ≈ Lock acquisitions |
|-------------:|-----:|--------------------:|
| 1      | 168 ms | 500000 |
| 10     | 65 ms  | 50000 |
| 100    | 59 ms  | 5000 |
| 1000   | 47 ms  | 500 |
| 500000 (whole task) | 294 ms | 1 |

A classic **U-shape** — both extremes are bad:

- **Too small (1):** every single pair grabs the lock → 500k `synchronized` contentions, dragging the time to 168 ms.
- **Too large (whole task):** the first thread claims all 500k pairs; the other 17 immediately get `-1` and idle → **degenerates to serial**, the worst at 294 ms.
- **Middle (~1000) is optimal (47 ms):** lock traffic is negligible and the load still spreads evenly.

### Task 2 — B. Varying `NUM_OF_WORKERS` (dim = 100, 4000 pairs, section = 10)

| Workers | Time |
|--------:|-----:|
| 1     | 1450 ms |
| 2     | 758 ms |
| 4     | 392 ms |
| 8     | 208 ms |
| 16    | 145 ms |
| 1000  | 133 ms |
| 10000 | 335 ms |

Same shape as Task 1: scales to ~16 threads, plateaus at 1000, regresses at 10000 due to thread-creation overhead. Versus Task 1's static split, dynamic scheduling adds **automatic load balancing** (fast threads grab more chunks) at the cost of per-fetch lock overhead.

### Task 2 — C. Adding `Thread.sleep(10)` (dim = 10, 500 pairs, section = 10 → 50 chunks)

| Workers | Time |
|--------:|-----:|
| 1   | 6078 ms |
| 2   | 3017 ms |
| 4   | 1570 ms |
| 8   | 845 ms |
| 16  | 478 ms |
| 32  | 245 ms |
| 64  | 123 ms |
| 500 | 125 ms |

Blocking work again keeps scaling past the core count (32 → 245, 64 → 123 ms). But note **64 and 500 both flatline at ~123 ms**: with `section = 10`, the 500 pairs form only **50 chunks**, so at most 50 threads can ever be busy — extra threads find nothing to steal. In other words, `SECTION_SIZE` also **caps the maximum useful parallelism** at `offset / section`.

### Summary — Task 1 vs Task 2

| Aspect | Task 1 (static) | Task 2 (dynamic) |
|---|---|---|
| Assignment | fixed intervals decided up front | chunks claimed at runtime |
| Locking | none (private `res` per worker) | `synchronized` cursor — contended |
| Load balancing | poor (slow thread drags the `join`) | good (fast threads grab more) |
| Key knob(s) | `NUM_OF_WORKERS` | `NUM_OF_WORKERS` + `SECTION_SIZE` |
| Main lesson | compute → threads ≈ cores; 8× law is asymptotic | too small → lock contention; too large → serial + parallelism cap |

**Cross-cutting conclusions:**

1. **Compute-bound** work is fastest at roughly the physical core count (18 here); more is pure overhead (10000 threads regressed in both tasks).
2. **Blocking / I/O-bound** work (`sleep`) keeps speeding up well beyond the core count, because blocked threads free the CPU.
3. Task 2's `SECTION_SIZE` is the **lock-contention ↔ load-balance / parallelism** trade-off dial: too small explodes lock traffic, too large serializes the work and caps the maximum concurrency at `offset / section`.

> Note: like Lab 1, these are demonstrative measurements (preemptive scheduler, JIT/GC noise), so absolute numbers vary run to run; the **trends** — the compute-vs-blocking divide and the U-shaped `SECTION_SIZE` curve — are the reproducible results.

## Lab 2 Profiling — async-profiler & the i-k-j cache optimization

All the Task 1/2 experiments above vary *how the work is scheduled* across threads. This section instead asks **where the time actually goes inside a single worker**, using [async-profiler](https://github.com/async-profiler/async-profiler) to sample the running JVM and render a flame graph — then acts on what it shows.

**How it was profiled** (macOS, Apple Silicon — `perf`'s `cpu` event is Linux-only, so use `itimer`). The full commands to reproduce both flame graphs from scratch:

```bash
# 0. Install async-profiler (one-time). macOS:
brew install async-profiler
LIB=/opt/homebrew/opt/async-profiler/lib/libasyncProfiler.dylib   # agent library path
# Linux: download from github.com/async-profiler/async-profiler/releases
#        LIB=/path/to/async-profiler/lib/libasyncProfiler.so
#        (and enable perf: sudo sysctl kernel.perf_event_paranoid=1)

# 1. Compile the benchmark
cd bench && javac Bench1.java

# 2. Attach the profiler as a JVM agent and run — flame graph written on exit.
#    event=itimer  -> CPU sampling that works on macOS (use event=cpu on Linux)
#    interval=5ms  -> sample every 5 ms   file=*.html -> render a flame graph
#    args "2 48 800 0 0" = 2 workers, 48 matrices of 800×800 (24 pairs), no sleep, no warmup
java -agentpath:$LIB=start,event=itimer,interval=5ms,file=flame-bench1.html \
     Bench1 2 48 800 0 0

# 3. Open it (widest frame = hottest). macOS: `open`, Linux: `xdg-open`
open flame-bench1.html

# --- Optional: emit folded/collapsed stacks (raw per-frame counts) ---
java -agentpath:$LIB=start,event=itimer,interval=5ms,collapsed,file=flame.collapsed \
     Bench1 2 48 800 0 0
#   rank leaf frames by self-samples:
awk '{n=$NF;$NF="";m=split($0,a,";");leaf=a[m];sub(/^ +/,"",leaf);s[leaf]+=n}
     END{for(k in s)print s[k],k}' flame.collapsed | sort -rn | head

# --- Optional: render a static SVG from the collapsed stacks (the images below) ---
#     async-profiler 4.x no longer emits .svg directly, so use Brendan Gregg's script:
curl -fsSL -o flamegraph.pl \
     https://raw.githubusercontent.com/brendangregg/FlameGraph/master/flamegraph.pl
perl flamegraph.pl --colors=java --width=1000 --countname=samples \
     --title="Bench1.multiply (i-k-j)" flame.collapsed > flame.svg

# --- Optional: profile an already-running JVM instead of launching one ---
asprof -d 30 -e itimer -f flame.html <pid>      # sample <pid> for 30 s (find it with: jps)
```

> The before/after graphs in this repo were produced by running step 2 with the original i-j-k `multiply` (saved as `flame-bench1-before-ijk.html`) and again after the i-k-j edit (`flame-bench1-after-ikj.html`).

**What the flame graph showed (before).** One frame dominated everything:

| Frame | Samples | Share | Meaning |
|---|--:|--:|---|
| `Bench1.multiply` | 841 | **93.3%** | the triple loop — essentially all the time |
| `fwd_copy_again` (G1 GC) | 45 | ~5% | GC evacuating the result matrices `multiply` allocates each pair |
| locks / malloc / JIT | 15 | ~1.7% | noise |

93% of the program's CPU time was one method. The culprit was the loop order in the original `multiply`:

```java
for (int j = 0; j < b[0].length; j++) {
    double sum = 0;
    for (int k = 0; k < a[0].length; k++) sum += a[i][k] * b[k][j];  // b[k][j] walks a COLUMN
    r[i][j] = sum;
}
```

`b[k][j]` marches **down a column** of a row-major array — each `k++` jumps a full row (800 doubles = 6.4 KB) in memory, so almost every access is a cache miss.

**The fix — reorder to i-k-j** so the inner loop walks `b[k]` and `r[i]` **left-to-right** (contiguous), turning column-striding into sequential reads:

```java
for (int k = 0; k < a[0].length; k++) {
    double aik = a[i][k];
    double[] bk = b[k];
    for (int j = 0; j < b[0].length; j++) r[i][j] += aik * bk[j];  // bk[j], r[i] both sequential
}
```

Mathematically identical (just re-associates the same sum); only the memory access pattern changes.

### Results — before vs after (same config, steady-state, warm JIT, median of 4)

| Config | i-j-k (before) | i-k-j (after) | Speedup |
|---|--:|--:|--:|
| 2 workers, dim 800, 24 pairs | 3816 ms | 911 ms | **4.19×** |
| 1 worker, dim 800, 24 pairs  | 7016 ms | 1639 ms | **4.28×** |

A **~4.2× speedup from reordering three lines** — no extra threads, no new data structures, purely better cache locality. The flame graph confirms it: `Bench1.multiply`'s share drops from **93.3% → 80.7%**, and total CPU samples over the run fall from 901 to 331.

**Before — i-j-k** (`Bench1.multiply` is the single wide plateau eating the whole stack):

![Flame graph before — i-j-k, multiply is 93% of CPU](bench/flame-bench1-before-ijk.svg)

**After — i-k-j** (same run, far fewer samples; the `multiply` plateau shrinks and the G1 GC frames beside it now take a visibly larger slice):

![Flame graph after — i-k-j, multiply share drops, GC relatively larger](bench/flame-bench1-after-ikj.svg)

### The Amdahl's-Law reading — is `multiply` the serial part?

**No — `multiply` is the *parallel* part.** It is exactly the work that gets split across the worker threads (each worker multiplies its own disjoint set of pairs into a private `res` list, no shared state). The **serial** portion of this program is elsewhere: `initBuffer` allocating all N matrices on the main thread, the `new Thread(...)` creation + `join()`, and — in Task 2 — the `synchronized getNextSection()` cursor. So optimizing `multiply` shrinks the *parallelizable* work, not the serial bottleneck.

This has a subtle Amdahl consequence, and the flame graph makes it visible. Making the parallel part ~4.2× cheaper while the fixed overheads stay constant **raises the serial *fraction*** — the opposite of Lab 1's zero-copy win (which cut the serial part and *lifted* the ceiling). You can see the shift directly: the G1 GC frame `fwd_copy_again` is roughly the same in absolute samples (45 → 47, allocation is unchanged) but jumps from **~5% to ~14%** of the run, because the compute it used to hide behind collapsed. In other words, the i-k-j fix removes CPU work from the part that already scaled well; to push *this* program's Amdahl ceiling next you'd attack the now-relatively-larger fixed costs — reuse/pre-allocate the result matrices to cut that GC, and reduce per-pair allocation — not the loop.

**Artifacts:** the static SVGs embedded above (`bench/flame-bench1-before-ijk.svg`, `bench/flame-bench1-after-ikj.svg`), plus the fully interactive versions [`bench/flame-bench1-before-ijk.html`](bench/flame-bench1-before-ijk.html) and [`bench/flame-bench1-after-ikj.html`](bench/flame-bench1-after-ikj.html) (open in a browser to zoom/search; widest frame = hottest). Both `Bench1.java` and `Bench2.java` now use the i-k-j order.

> Note: as elsewhere, demonstrative measurements — absolute ms and sample counts vary run to run; the reproducible results are the **~4× locality speedup** and the **rise in the GC/overhead fraction** once the dominant compute is optimized.

## Lab 3 Producer–Consumer — Throughput Experiments

![Producer–Consumer throughput experiments](viz/producer-consumer.png)

> The four charts above are rendered by [`viz/producer-consumer.html`](viz/producer-consumer.html) — a self-contained page with hover tooltips, a light/dark toggle, and a data-table view.

**Hardware:** same 18-core machine (6 Super Cores + 12 Performance Cores), JDK 26.

**System.** Producers generate random legal arithmetic operations (operands in −100…+100; division guards against a zero divisor) and try to `add()` them to a shared, *sliced* buffer; consumers `take()` and evaluate them, discarding the result. The buffer is one fixed-size array split into `numSlices` slices, **each slice guarded by its own binary semaphore**. `add()`/`take()` walk the slices, `tryAcquire()` a slice's semaphore, scan that slice for an empty/occupied slot, and always `release()` in a `finally`. Both are **non-blocking**: if no semaphore is won or the slice is full/empty they return failure, and the caller discards the item (producer) or retries (consumer) after a short sleep. The only metric is **throughput = total computations consumed per second**.

**Method.** A harness builds the buffer + P producers + C consumers with **P and C controlled independently** (the deliverable `main` happens to tie both counts to `numSlices`; the harness decouples them so each knob can be varied in isolation). It starts all threads, runs for a fixed window, sets the `running` flags false, `join()`s, and sums every consumer's `consumed`. Each configuration is **3 × 3 s runs averaged, one warm-up discarded**. (The assignment specifies a 30 s window; shorter windows are used here only to keep the ~30-config sweep tractable — a per-second rate is duration-independent.)

**The mechanism that governs everything.** A producer sleeps **1 ms after a successful add, 5 ms after a failure**; a consumer **never sleeps after a successful take**, only 5 ms when it finds the buffer empty. So this is a **blocking / sleep-bound** system, and — as every table below shows — throughput is set almost entirely by how fast producers are throttled by their 1 ms sleep: **≈ 665 successful ops/sec per producer** on this machine.

### A. Buffer size (slices = 4, P = C = 4)

| Buffer size | Slots per slice | Throughput (ops/s) |
|------------:|----------------:|-------------------:|
| 4    | 1   | 1382 |
| 8    | 2   | 2431 |
| 16   | 4   | 3187 |
| 40   | 10  | 3201 |
| 400  | 100 | 2749 |

An **inverted-U**. When the buffer is tiny (1–2 slots per slice) it is constantly full or empty, so threads keep hitting the **failure path and its 5 ms sleep** → starvation drags throughput to 1382. More slots remove those stalls until throughput hits the 4-producer ceiling (≈ 4 × per-producer rate) and **plateaus around 16–40**. At 400 it dips again: `add()`/`take()` scan the *entire slice* while holding the semaphore, so a 100-slot slice means up to 100 slot-checks per operation — pure overhead once the buffer is already big enough. Sweet spot: **just large enough that slices rarely fill**, no larger.

### B. Number of slices / semaphores (buffer = 48, P = C = 8)

| numSlices | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 5034 |
| 2  | 5403 |
| 4  | 5429 |
| 8  | 5436 |
| 16 | 5416 |
| 48 | 5422 |

**Essentially flat.** More semaphores allow more genuinely-concurrent buffer access, but since producers self-throttle at 1 ms the buffer is *never* the bottleneck — so extra slices buy almost nothing. Only `numSlices = 1` (a single global semaphore that fully serializes all access) is measurably lower (5034 vs ~5420), and even that penalty is tiny because contention isn't the limiter here. **Lesson:** adding concurrency to a shared structure only helps if that structure is actually the bottleneck; here it isn't.

### C. Producer : consumer ratio (buffer = 48, slices = 8)

Varying **producers** (consumers fixed at 8):

| Producers | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 670   |
| 2  | 1346  |
| 4  | 2702  |
| 8  | 5427  |
| 16 | 10828 |

Varying **consumers** (producers fixed at 8):

| Consumers | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 5432 |
| 2  | 5419 |
| 4  | 5420 |
| 8  | 5427 |
| 16 | 5432 |

Two opposite behaviours, and this is the heart of the "conveyor belt" balance from the brief:

- **Producers are the throughput lever** — dead linear, throughput ≈ `producers × 665/s`.
- **Consumers barely matter** — dropping from 16 → 1 consumer against 8 producers leaves throughput unchanged (~5420). A **single** consumer keeps up with 8 producers, because a consumer never sleeps on success and is far faster than the sleep-throttled producers. Under these sleep settings the belt is **producer-limited**; extra consumers just find the buffer empty, sleep, and add nothing.

### D. Scaling balanced producer + consumer count (buffer = 64, slices = 8)

| Producers = Consumers | Threads total | Throughput (ops/s) |
|----------------------:|--------------:|-------------------:|
| 1  | 2   | 668   |
| 2  | 4   | 1343  |
| 4  | 8   | 2699  |
| 8  | 16  | 5421  |
| 16 | 32  | 10873 |
| 32 | 64  | 21686 |
| 64 | 128 | 42547 |

**Perfect linear doubling all the way to 128 threads** — throughput ∝ producer count (≈ 665 × n), with no knee at the 18-core limit. This is the same lesson as Lab 2's `Thread.sleep` experiment: the system is **blocking/sleep-bound**, threads spend almost all their time asleep holding no CPU, so hundreds of threads oversubscribe the 18 cores happily and keep paying off. Contrast the *compute-bound* matrix experiments, which saturated at ~18 threads.

### Summary

| Knob | Effect on throughput |
|---|---|
| **Producer count** | The lever. Throughput ≈ `producers × ~665/s`, linear well past the core count. |
| **Consumer count** | Nearly irrelevant under these sleep settings — even 1 consumer keeps up with 8+ producers. |
| **Buffer size** | Inverted-U: too small → starvation via the 5 ms failure sleep; too large → per-op slice-scan overhead. |
| **Slices / semaphores** | Nearly flat — contention isn't the bottleneck, so more semaphores don't help (only `=1` is slightly worse). |

**Cross-cutting conclusion.** Because a producer sleeps on every cycle and a consumer only sleeps when starved, this system is **blocking-bound and producer-throttled**: throughput is governed by *producer count × per-producer rate*, scales linearly with threads far beyond 18 cores, and is almost insensitive to slice count and consumer count. To turn it into a *consumer*- or *contention*-bound system (where slices and consumer count would start to matter), one would shrink the producer sleep or make evaluation expensive — mirroring the compute-vs-blocking divide seen in Lab 2.

### Variant: no sleeps at all (spin instead of back off)

The paragraph above predicts what happens if the sleeps are removed — so here is the experiment. Package [`ProducerConsumerPatternNoSleep`](src/ProducerConsumerPatternNoSleep) is a **byte-for-byte copy of the pattern with every `Thread.sleep` deleted**: producers produce-and-`add` in a tight loop (retry immediately on a full buffer), consumers `take`-and-evaluate in a tight loop (retry immediately when empty). Nothing else changes. This converts the system from **blocking/sleep-bound** to **spin / CPU-and-contention-bound**, and every result flips. (Throughput here is measured against *wall-clock* elapsed time, because the spinning threads fully saturate all cores.)

![Producer–Consumer no-sleep throughput experiments](viz/producer-consumer-nosleep.png)

> Rendered by [`viz/producer-consumer-nosleep.html`](viz/producer-consumer-nosleep.html). Note the y-axes are in **millions/sec** here vs **thousands/sec** in the sleep-version charts above — and every curve shape is inverted.

**A. Buffer size (slices = 4, P = C = 4)** — now **monotonically rising**, not an inverted-U:

| Buffer size | No-sleep (ops/s) | (sleep version) |
|------------:|-----------------:|----------------:|
| 4    | 2,327,497 | 1382 |
| 8    | 3,143,074 | 2431 |
| 16   | 3,548,308 | 3187 |
| 40   | 3,982,743 | 3201 |
| 400  | 4,329,675 | 2749 |

Without the 1 ms cap, a bigger buffer simply means fewer full/empty collisions, so throughput keeps climbing — the per-op slice-scan cost that produced the sleep version's dip at 400 no longer outweighs the win from fewer failed attempts.

**B. Slices / semaphores (buffer = 48, P = C = 8)** — was flat; now the **single most important knob** (a 30× swing):

| numSlices | No-sleep (ops/s) | (sleep version) |
|----------:|-----------------:|----------------:|
| 1  | 172,046   | 5034 |
| 2  | 1,716,965 | 5403 |
| 4  | 2,948,475 | 5429 |
| 8  | 5,150,900 | 5436 |
| 16 | 5,467,982 | 5416 |
| 48 | 4,733,582 | 5422 |

This is the headline reversal. With 16 threads all spinning, **semaphore contention *is* the bottleneck**, so more slices = more genuine parallelism. A single global semaphore serializes everything (172 K/s — 30× worse than 8 slices); throughput peaks around 8–16 slices (≈ the thread count) and then dips at 48, where each slice holds only one slot (48/48) so full/empty misses and cross-slice scanning creep back.

**C. Producer : consumer ratio (buffer = 48, slices = 8)** — consumers now matter, and **balance wins**:

| Config | No-sleep (ops/s) | | Config | No-sleep (ops/s) |
|---|--:|---|---|--:|
| 1 prod : 8 con | 1,581,322 | | 8 prod : 1 con | 2,069,988 |
| 2 prod : 8 con | 2,625,040 | | 8 prod : 2 con | 3,102,473 |
| 4 prod : 8 con | 3,819,574 | | 8 prod : 4 con | 4,576,203 |
| 8 prod : 8 con | **4,940,039** | | 8 prod : 8 con | **4,940,039** |
| 16 prod : 8 con | 4,162,202 | | 8 prod : 16 con | 3,130,370 |

In the sleep version consumers were irrelevant; here throughput peaks at the **balanced 8 : 8** and falls off on both sides. Starve either role and the other spins uselessly; oversupply either (16 : 8 or 8 : 16 → 24 threads > cores) and oversubscription plus contention drag it back down.

**D. Scaling balanced P = C (buffer = 64, slices = 8)** — the sharpest reversal: **peaks at ~2 pairs, then *declines*** (the sleep version scaled linearly to 128 threads):

| Producers = Consumers | Threads total | No-sleep (ops/s) | (sleep version) |
|----------------------:|--------------:|-----------------:|----------------:|
| 1  | 2   | 5,135,606 | 668   |
| 2  | 4   | 7,401,608 | 1343  |
| 4  | 8   | 5,460,735 | 2699  |
| 8  | 16  | 5,356,637 | 5421  |
| 16 | 32  | 4,224,525 | 10873 |
| 32 | 64  | 2,888,100 | 21686 |
| 64 | 128 | 1,764,024 | 42547 |

Spinning threads hold the CPU, so once past a handful of threads the extra ones don't add throughput — they compete for the 18 cores and for the 8 semaphores. Beyond ~4 pairs it goes **backwards** (128 threads → 1.76 M, a 4× loss from the 7.4 M peak) to context-switching and lock contention. This is exactly the *compute-bound* behavior of the Lab 2 matrix experiments, and the mirror image of the sleep version's linear-to-128 scaling.

**What removing the sleeps changed:**

| Knob | Sleep version (blocking-bound) | No-sleep version (spin / contention-bound) |
|---|---|---|
| Absolute throughput | thousands/s (capped by the 1 ms sleep) | **millions/s** (~100–1000× higher) |
| Buffer size | inverted-U (peak 16–40) | monotonically rising (bigger = fewer collisions) |
| Slices / semaphores | flat — irrelevant | **dominant** — contention is the bottleneck (30× swing) |
| Consumer count | irrelevant | matters — balance with producers wins |
| Scaling threads | linear past 128 threads | peaks at ~2–4 pairs, then **regresses** (saturates at core count) |

One system, one deleted `sleep`, and it moves cleanly across the **blocking-bound ↔ compute-bound divide**: the sleep version rewards *more threads* and ignores the shared structure; the no-sleep version rewards *less contention* (more semaphores, balanced roles, threads ≈ cores) and punishes oversubscription. The higher raw throughput of the spin version is not free — it **burns every CPU cycle busy-waiting even when idle**, which is exactly why back-off (the sleeps) is the more resource-friendly design in practice.

> Note: as in Labs 1–2 these are demonstrative measurements (preemptive scheduler, JIT/GC noise, `Thread.sleep` granularity), so absolute ops/sec vary run to run; the **trends** — linear scaling in producer count (sleep version), the inverted-U in buffer size, the near-irrelevance of slice/consumer count under sleep and their dominance without it, and the blocking-vs-spin reversal — are the reproducible results.

## Thread Pool — a fixed-size pool from scratch

[`ThreadPool`](src/ThreadPool) implements a minimal fixed-size thread pool in one class, [`MyThreadPool`](src/ThreadPool/MyThreadPool.java), without touching `java.util.concurrent`'s executors. It is the producer–consumer pattern again, but the *items* are `Runnable` tasks: callers **submit** tasks (producers), and a fixed set of worker threads **take and run** them (consumers).

**Design.**

- **Fixed pool.** `poolSize` worker threads are started in the constructor; each runs `workerLoop()` forever until shutdown.
- **One lock, one condition.** A `ReentrantLock` guards a shared `ArrayDeque<Runnable>` work queue; a single `Condition` (`notEmpty`) is the "queue has work" signal — the explicit-lock equivalent of `synchronized` + `wait`/`notify`.
- **Workers block, never spin.** An idle worker calls `notEmpty.await()`, which **releases the lock and parks the thread** — no busy-waiting, no CPU burned while the queue is empty (the resource-friendly design the no-sleep experiment above argues for).
- **`submit()`** locks, rejects if already shut down, `offer`s the task, and `signal()`s one waiting worker.
- **`shutdown()`** sets a `volatile` flag, `signalAll()`s every parked worker so they can drain the queue and exit, then `join()`s them — a **graceful** shutdown that finishes queued work rather than dropping it.

**Correctness points worth calling out:**

- The wait is a `while (queue.isEmpty() && !shutdown)` loop, not an `if` — guarding against spurious wakeups and lost races, the standard condition-variable discipline.
- `lock`/`unlock` is always paired in `try/finally`, so an exception can never leak the lock.
- `task.run()` is called **outside** the lock, so one task's work never blocks other workers from pulling from the queue; a throwing task is caught so it can't kill its worker thread.
- Shutdown only exits a worker once `shutdown && queue.isEmpty()`, guaranteeing every submitted task still runs.

**Demo** ([`Main`](src/ThreadPool/Main.java)) — pool size 3, eight 300 ms tasks:

```
>>> [版本B: ReentrantLock] 池大小 N = 3，提交 8 个任务
  [开始] 任务 1 @ worker-0
  [开始] 任务 2 @ worker-1
  [开始] 任务 3 @ worker-2
  [完成] 任务 1 @ worker-0
  ...
  [开始] 任务 8 @ worker-1
>>> 全部完成，线程池已关闭
```

The three workers pick up tasks 1–3 immediately, then reuse the same threads for 4–6 and 7–8 as they free up — the whole point of a pool: **threads are reused across many tasks instead of one-thread-per-task**, so the eight tasks run three-at-a-time on a constant three OS threads.
