# 调试与性能分析 · 实验手册(来自MIT missing semester课程的Debugging and profiling 这节课）

> 面向环境:macOS(Apple Silicon)+ OrbStack Linux Machine,Ubuntu `questing`(25.10)、**amd64**(x86_64 模拟)。
> 用法:在 Mac 终端里 `orb` 进入这台 Ubuntu 机器后,按本手册从上到下操作即可。

---

## 0. 一次性准备

```bash
# 在 Mac 终端进入 Ubuntu
orb
# (提示符会变成 alexshao@ubuntu)

# 确认环境
uname -a          # 应看到 x86_64 GNU/Linux

# 装齐工具(rr 故意不装,原因见调试第 2 题)
sudo apt update
sudo apt install -y gcc gdb strace valgrind htop stress hyperfine \
                    fd-find ripgrep linux-tools-generic

# 建个工作目录
mkdir -p ~/lab && cd ~/lab
```

注意两个坑:
- **fd**:在 Ubuntu 上命令名叫 `fdfind`(不是 `fd`)。想用 `fd`,加一行别名:`alias fd=fdfind`。
- **perf**:在模拟环境里硬件性能计数器可能不可用。先测一下:`perf stat ls`。如果各项显示 `<not supported>` 或全是 0,就用本手册给的 `valgrind --tool=callgrind` 替代方案。

---

# 调试篇

## 调试 1 · 找出归并排序的 bug

**目标**:伪代码实现的 merge sort 有个索引 bug,用调试器定位并修复。

创建含 bug 的版本(照伪代码原样写):

```bash
cat > mergesort_buggy.py << 'EOF'
def merge_sort(arr):
    if len(arr) <= 1:
        return arr
    mid = len(arr) // 2
    left = merge_sort(arr[0:mid])
    right = merge_sort(arr[mid:])
    return merge(left, right)


def merge(left, right):
    result = []
    i = 0
    j = 0
    while i < len(left) and j < len(right):
        if left[i] <= right[j]:
            result.append(left[i])
            i = i + 1
        else:
            result.append(right[i])   # BUG:这里用了 right[i]
            j = j + 1
    result.extend(left[i:])
    result.extend(right[j:])
    return result


if __name__ == "__main__":
    print(merge_sort([3, 1, 4, 1, 5, 9, 2, 6]))
EOF

python3 mergesort_buggy.py
```

**预期(错误)输出**:`[1, 5, 4, 3, 4, 5, 6, 9]` —— 数字有重复有缺失,不是有序的。

**用 pdb 定位**:

```bash
python3 -m pdb mergesort_buggy.py
```

在 pdb 里依次敲:

```
b merge                 # 在 merge 设断点
c                       # 运行到第一次进入 merge
# 每次停下时,重点看走 else 分支的时刻:
p i, j                  # 两个索引
p left[i], right[j]     # 正在比较的值
p right[i]              # 被错误 append 的值
n                       # 单步,观察 append 进 result 的到底是谁
```

**根因**:进入 `else` 说明 `left[i] > right[j]`,本应把较小的 `right[j]` 放进结果,但代码 append 的是 `right[i]`——**索引用错了**。

**修复并验证**:

```bash
sed 's/result.append(right\[i\])/result.append(right[j])/' \
    mergesort_buggy.py > mergesort_fixed.py
python3 mergesort_fixed.py
```

**预期(正确)输出**:`[1, 1, 2, 3, 4, 5, 6, 9]` ✅

---

## 调试 2 · 内存破坏(corruption.c)

**目标**:一个只动 student 0 的函数,却破坏了 student 1 的 id。定位越界写入。

> **关于 rr**:本题原意是用 `rr` 反向调试。但 rr 需要硬件性能计数器,在 Apple Silicon 上的模拟/虚拟化 Linux 里几乎跑不起来。所以本手册用「读代码 + 地址布局 + gdb watchpoint」的方式定位,效果相同。若你在真实 Linux 物理机上,可照原题用 `rr record ./corruption` → `rr replay` → `watch students[1].id` → `reverse-continue`。

```bash
cat > corruption.c << 'EOF'
#include <stdio.h>
typedef struct { int id; int scores[3]; } Student;
Student students[2];
void init() {
    students[0].id=1001; students[0].scores[0]=85; students[0].scores[1]=92; students[0].scores[2]=78;
    students[1].id=1002; students[1].scores[0]=90; students[1].scores[1]=88; students[1].scores[2]=95;
}
void curve_scores(int student_idx, int curve) {
    for (int i = 0; i < 4; i++) { students[student_idx].scores[i] += curve; }  // BUG: i<4 越界
}
int main() {
    init();
    printf("Student 1 id (before) = %d\n", students[1].id);
    curve_scores(0, 5);
    printf("Student 1 id (after)  = %d\n", students[1].id);
    if (students[1].id != 1002) { printf("ERROR: corrupted! got %d\n", students[1].id); return 1; }
    return 0;
}
EOF

gcc -g corruption.c -o corruption && ./corruption
```

**预期输出**:`before = 1002`,`after = 1007`,报 `ERROR: corrupted! got 1007`。

**用 gdb watchpoint 定位是哪一行改的**:

```bash
gdb ./corruption
```

```
break main
run
watch students[1].id      # 监视这个字段的任何写入
continue                  # gdb 会在它被改写的那一刻停下
```

gdb 会停在 `curve_scores` 里 `students[student_idx].scores[i] += curve;`(此时 `i == 3`)。

**根因(内存布局)**:`Student` = `{int id; int scores[3]}` 共 16 字节,`students[0]` 紧挨 `students[1]`。`scores` 只有下标 0/1/2,循环写到 `i<4` 多写了 `students[0].scores[3]`,这个地址正好等于 `students[1].id`。验证地址相同:

```bash
cat > layout.c << 'EOF'
#include <stdio.h>
typedef struct { int id; int scores[3]; } Student;
Student students[2];
int main(){
  printf("&students[0].scores[3] = %p\n", (void*)&students[0].scores[3]);
  printf("&students[1].id        = %p\n", (void*)&students[1].id);
  return 0;
}
EOF
gcc layout.c -o layout && ./layout
```

两个地址会**完全一致**。

**修复**:把 `curve_scores` 的循环条件 `i < 4` 改成 `i < 3`。

---

## 调试 3 · use-after-free(uaf.c + AddressSanitizer)

**目标**:释放内存后又使用,用 ASan 抓出来。

```bash
cat > uaf.c << 'EOF'
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
int main() {
    char *greeting = malloc(32);
    strcpy(greeting, "Hello, world!");
    printf("%s\n", greeting);
    free(greeting);
    greeting[0] = 'J';          // BUG: free 之后又写
    printf("%s\n", greeting);
    return 0;
}
EOF

# 先不带 sanitizer:可能"看似正常",但第二行是乱码
gcc uaf.c -o uaf && ./uaf

# 再用 AddressSanitizer:
gcc -fsanitize=address -g uaf.c -o uaf_asan && ./uaf_asan
```

**预期**:ASan 报 `heap-use-after-free`,并给出三处行号:
- `WRITE ... main uaf.c:9` —— 非法使用点(`greeting[0]='J'`)
- `freed by ... uaf.c:8` —— 谁 free 的
- `allocated by ... uaf.c:5` —— 谁 malloc 的

**修复**:不要在 `free` 之后再使用这块内存(把写入移到 free 之前,或删掉 free 后两行)。

---

## 调试 4 · 系统调用追踪(strace)

**目标**:看 `ls -l` 向内核发了哪些系统调用。

```bash
strace ls -l                      # 全部系统调用
strace -e trace=openat ls -l      # 只看打开了哪些文件
strace -f -e trace=openat curl https://example.com   # 追踪子进程(需先装 curl)
```

**要识别的关键调用**:
- `execve(...)` —— 启动程序本身
- `openat(..., "libc.so.6", ...)` —— 加载共享库
- `openat(AT_FDCWD, ".", ...)` + `getdents64(...)` —— 打开当前目录并读目录项(`ls` 的核心)
- `statx` / `lstat` —— 对每个文件取元数据(`-l` 要显示大小/权限/时间)
- `write(1, ...)` —— 结果写到标准输出(fd 1)

> macOS 原生(非 Linux)下无 strace,对应工具是 `sudo dtruss ls -l`。

---

## 调试 5 · 用 LLM 解释晦涩报错

**目标**:练习把工具输出喂给 LLM。先造一个报错:

```bash
cat > tmpl.cpp << 'EOF'
#include <vector>
#include <string>
int main() {
    std::vector<int> v = {1, 2, 3};
    std::string s = v;   // 类型不匹配
    return 0;
}
EOF
g++ tmpl.cpp -o tmpl        # 会报编译错误
```

**做法**:把**完整**报错(不要只截一行)连同相关代码贴给 LLM,问「这个错误什么意思?根因在哪?怎么改?」。也可以把调试 3 的 ASan 报告、或调试 4 的 strace 输出整段贴进去让它解读。

**注意**:LLM 可能给出听起来对但实际错的解释,或建议「掩盖 bug」而非「修复 bug」的改法——务必用真实工具(编译器、ASan、gdb)验证。

---

# 性能分析篇

## 性能 1 · perf stat 基本统计

```bash
perf stat ls          # 先测 perf 在模拟环境里能否工作
perf stat ./你的程序
```

若能工作,各计数器含义:
- **task-clock** —— 程序占用的 CPU 时间(ms)
- **context-switches** —— 上下文切换次数(被换下 CPU 的次数)
- **cpu-migrations** —— 进程在核心间迁移次数
- **page-faults** —— 缺页次数
- **cycles** —— CPU 周期总数
- **instructions** + `insn per cycle`(IPC)—— 指令数与每周期指令数(越高越好,理想 >1)
- **branches / branch-misses** —— 分支数 / 预测失败数(miss 率高会拖慢流水线)

> 若 perf 输出全是 `<not supported>`(模拟层不暴露硬件计数器),就用 `bash -c 'time ./程序'` 看 real/user/sys 三种时间做粗略判断。

---

## 性能 2 · perf record + 火焰图(slow.c)

```bash
cat > slow.c << 'EOF'
#include <math.h>
#include <stdio.h>
double slow_computation(int n) {
    double result = 0;
    for (int i = 0; i < n; i++)
        for (int j = 0; j < 1000; j++)
            result += sin(i * j) * cos(i + j);
    return result;
}
int main() {
    double r = 0;
    for (int i = 0; i < 100; i++) r += slow_computation(1000);
    printf("Result: %f\n", r);
    return 0;
}
EOF

gcc -g -O2 slow.c -o slow -lm
bash -c 'time ./slow'      # 预期约几秒,且几乎全是 user 时间 → 纯 CPU 密集
```

**方案 A(perf 可用时)**:

```bash
perf record -g ./slow
perf report               # 热点会指向 slow_computation / sin / cos
```

生成火焰图(需先获取 Brendan Gregg 的 FlameGraph 脚本):

```bash
git clone https://github.com/brendangregg/FlameGraph
perf script | ./FlameGraph/stackcollapse-perf.pl | ./FlameGraph/flamegraph.pl > flame.svg
# 用浏览器打开 flame.svg,最宽的条=最耗时
```

**方案 B(perf 不可用时,用 callgrind 替代)**:

```bash
valgrind --tool=callgrind ./slow          # 慢,但用软件模拟,不依赖硬件计数器
callgrind_annotate callgrind.out.*        # 文本查看每个函数的指令占比
```

无论哪种,结论都会指向 `slow_computation` 里的 `sin`/`cos` 运算是热点。

---

## 性能 3 · hyperfine 基准对比

```bash
# 对比两种做同样事的命令,多次运行取均值方差
hyperfine --warmup 3 'find . -iname "*.md"' 'fdfind -e md'
# 注意 Ubuntu 上 fd 命令名是 fdfind
```

**要点**:`--warmup 3` 先空跑 3 次预热文件系统缓存,避免第一次因冷缓存偏慢造成不公平。hyperfine 会输出每条命令的 `mean ± σ`,并在最后告诉你哪个快几倍。也可用它对比你自己代码的两个版本。

---

## 性能 4 · htop + taskset + stress

**思考题:为什么 `taskset --cpu-list 0,2 stress -c 3` 用不满 3 个 CPU?**
因为 `--cpu-list 0,2` 把进程限定只能在 **0、2 两个核**上运行。`stress -c 3` 想开 3 个 worker,但它们只能挤在这 2 个核上,最多吃满 2 核算力,总利用率约等于 2 核而非 3 核。

**动手验证**(开两个 `orb` 终端,或用 tmux):

```bash
# 终端 A:实时监控每个核的占用
htop            # 按 F2 → Display options → 可显示每核;观察哪些核跑满

# 终端 B:限制到 0、2 两核,却想开 3 个 worker
taskset --cpu-list 0,2 stress -c 3
```

在 htop 里你会看到只有 0、2 两核跑满、其余核闲着,印证上面的解释。按 `q` 退出 htop,`Ctrl-C` 停 stress。

---

## 性能 5 · 找出占用端口的进程

**目标**:端口被占 → 找到进程 → 终止。开两个 `orb` 终端。

```bash
# 终端 A:占用 4444 端口(会卡住,正常,别关)
python3 -m http.server 4444

# 终端 B:找出占用者
ss -tlnp | grep 4444
#   -t TCP   -l 监听中   -n 数字端口   -p 显示进程
#   输出里的 pid=XXXX 就是要找的 PID

# 终端 B:终止它
kill <PID>            # 把 <PID> 换成上面看到的数字

# 验证已释放:
ss -tlnp | grep 4444  # 应无输出
```

> 若 `kill` 无效、进程赖着不走,用 `kill -9 <PID>`(强制,最后手段)。

---

# 完成度对照

| # | 练习 | 在你环境能否真跑 | 结论 / 修复 |
|---|------|------------------|-------------|
| 调试1 | 归并排序 | ✅ | `else` 里 `right[i]`→`right[j]` |
| 调试2 | corruption.c | ✅(用 gdb watchpoint 替代 rr) | 循环 `i<4`→`i<3`,越界覆盖相邻 struct |
| 调试3 | uaf.c ASan | ✅ | free 后勿再使用 |
| 调试4 | strace | ✅ | ls -l 关键调用:execve/openat/getdents64/statx/write |
| 调试5 | LLM 解释报错 | ✅ | 贴完整报错+代码,务必验证 |
| 性能1 | perf stat | ⚠️ perf 可能不可用 | 各计数器含义已列;不行用 time |
| 性能2 | perf record+火焰图 | ⚠️ | perf 不行则用 valgrind callgrind |
| 性能3 | hyperfine | ✅ | 注意 fd→fdfind |
| 性能4 | taskset/htop/stress | ✅ | 3 进程限 2 核,只能用满 2 核 |
| 性能5 | ss+kill 找端口 | ✅ | ss 找 pid → kill |

> `rr`(调试2 原方案)在 Apple Silicon 的模拟/虚拟化 Linux 上基本不可用;`perf`(性能1、2)可能不可用,已给替代方案。其余全部可在这台 OrbStack Ubuntu 上真跑。
