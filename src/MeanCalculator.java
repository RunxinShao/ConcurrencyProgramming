import java.math.BigInteger;

/**
 * 使用任务 1 中的类设置输入数据。
 * 使用任务 1 中的类创建数据切片。
 * 使用任务 2 中的 Runnable 类，为每个数据切片创建一个线程。
 * 启动所有线程。
 * 等待所有线程完成。
 * 收集所有线程的结果。
 * 计算所收集均值的均值²。
 * 将均值输出到控制台。
 */
public class MeanCalculator {
    private static int INPUT_LIST_SIZE = 15000000;
    private static int NUM_OF_WORKERS = 5;

    public static void main(String[] args) throws InterruptedException{
        DataSet dataSet = new DataSet(INPUT_LIST_SIZE);
        BigInteger[][] data = dataSet.getSlicedData(NUM_OF_WORKERS);

        MeanWorker[] workers = new MeanWorker[NUM_OF_WORKERS];
        Thread[] threads = new Thread[NUM_OF_WORKERS];
        for(int i = 0 ; i < NUM_OF_WORKERS; i++){
            workers[i] = new MeanWorker(data[i]);
            threads[i] = new Thread(workers[i]);
        }

        for(int i = 0; i < NUM_OF_WORKERS; i++){
            threads[i].start();
        }
        for(int i = 0; i < NUM_OF_WORKERS; i++){
            threads[i].join();
        }


        // 收集所有线程的结果：累加各切片的“和”与“元素个数”。
        // 用 总和 / 总个数 一次性求总体均值，而不是对各切片均值再取平均，
        // 这样切片是否等长都不影响正确性，并且只在最后做一次截断除法。
        BigInteger totalSum = BigInteger.ZERO;
        BigInteger totalCount = BigInteger.ZERO;
        for(int i = 0; i < NUM_OF_WORKERS; i++){
            totalSum = totalSum.add(workers[i].getRes());
            totalCount = totalCount.add(BigInteger.valueOf(workers[i].getCount()));
        }

        // 说明：BigInteger.divide 仍是整数除法，会截断小数部分（例如真实均值 7499999.5 会得到 7499999）。
        // 如果需要保留小数，可改用 BigDecimal：
        //   new BigDecimal(totalSum).divide(new BigDecimal(totalCount), 10, RoundingMode.HALF_UP)
        System.out.println("final res: " + totalSum.divide(totalCount));

    }
}
