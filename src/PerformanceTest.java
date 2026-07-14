import java.math.BigInteger;

/**
 * 性能测试：在固定输入规模下，测量不同"切片数 / 线程数"对并行求均值耗时的影响。
 * 对每种切片数重复运行多次取平均，减少偶然波动带来的误差。
 */
public class PerformanceTest {
    private static final int INPUT_LIST_SIZE = 15000000;
    private static final int RUNS_PER_SETTING = 10;

    public static void main(String[] args) throws InterruptedException {
        // 数据生成很贵（1500 万个 BigInteger），且与切片数无关，
        // 所以在计时循环外只生成一次，之后所有测试复用同一份数据。
        DataSet dataSet = new DataSet(INPUT_LIST_SIZE);

        int[] sliceCounts = {1, 2, 4, 8, 16, 32};

        for (int numSlices : sliceCounts) {
            long totalTime = 0;
            for (int run = 0; run < RUNS_PER_SETTING; run++) {
                long start = System.nanoTime();

                // 被测量的并行工作：切片 -> 创建并启动所有线程 -> join 全部 -> 汇总结果
                runParallelMean(dataSet, numSlices);

                long end = System.nanoTime();
                totalTime += (end - start);
            }
            long avgTimeMs = (totalTime / RUNS_PER_SETTING) / 1_000_000;
            System.out.println("Slices: " + numSlices + ", Avg time: " + avgTimeMs + " ms");
        }
    }

    /**
     * 用 numSlices 个线程并行计算总体均值，返回结果。
     * 逻辑与 MeanCalculator 一致：每个 worker 求切片的和，主线程用 总和 / 总个数 求均值。
     */
    private static BigInteger runParallelMean(DataSet dataSet, int numSlices) throws InterruptedException {
        // 零拷贝：只拿共享原数组和各线程的 {start, length}，不复制数据。
        // 于是原来那段串行复制 1500 万元素的开销（约占 1 线程耗时的 13%）被消除，
        // Amdahl 定律里的串行占比大幅下降，加速上限被推高。
        BigInteger[] data = dataSet.getData();
        int[][] ranges = dataSet.getSliceRanges(numSlices);

        MeanWorker[] workers = new MeanWorker[numSlices];
        Thread[] threads = new Thread[numSlices];
        for (int i = 0; i < numSlices; i++) {
            workers[i] = new MeanWorker(data, ranges[i][0], ranges[i][1]);
            threads[i] = new Thread(workers[i]);
        }

        // 先全部启动，再逐个 join，保证线程真正并行执行
        for (int i = 0; i < numSlices; i++) {
            threads[i].start();
        }
        for (int i = 0; i < numSlices; i++) {
            threads[i].join();
        }

        // 汇总：累加各切片的和与元素个数，最后只做一次除法
        BigInteger totalSum = BigInteger.ZERO;
        BigInteger totalCount = BigInteger.ZERO;
        for (int i = 0; i < numSlices; i++) {
            totalSum = totalSum.add(workers[i].getRes());
            totalCount = totalCount.add(BigInteger.valueOf(workers[i].getCount()));
        }
        return totalSum.divide(totalCount);
    }
}
