package parallelcomputation;

import java.math.BigInteger;

/**
 * 创建一个实现 Runnable 接口的类。
 *
 * 零拷贝优化：构造函数不再接收一份复制出来的切片，而是接收
 * “原数组 + 本线程负责的起点 start + 长度 length”。线程直接去原数组
 * 读 [start, start+length) 这一段，避免了主线程串行复制 1500 万元素的开销。
 *
 * 为什么这样是线程安全的：所有线程都只【读】这个共享数组、没有任何线程去【写】它，
 * 而数据竞争只发生在“至少有一个线程写”的情况下；再加上各线程负责的区间互不重叠，
 * 所以既不需要加锁，也不会出现竞态。
 */
public class MeanWorker implements Runnable{
    // 这里存的是本线程负责区间的“和”，而不是“均值”。
    // 原因：如果每个 worker 各自先除法求均值，主线程再对这些均值取平均，
    // 只有在所有切片元素个数完全相等时才等于总体均值；一旦区间不等长就会算错。
    // 而且 BigInteger.divide 是向零截断的整数除法，每段各除一次会累积截断误差。
    // 所以 worker 只求和，把唯一的一次除法留到主线程（总和 / 总个数）来做。
    private BigInteger res;
    private final BigInteger[] data;  // 共享的原数组（只读）
    private final int start;          // 本线程负责区间的起始下标
    private final int length;         // 本线程负责区间的长度

    public MeanWorker(BigInteger[] data, int start, int length){
        this.data = data;
        this.start = start;
        this.length = length;
    }

    @Override
    public void run() {
        BigInteger sum = BigInteger.ZERO;
        // 直接在原数组上读自己那一段，不做任何复制
        for(int i = start; i < start + length; i++ ){
            sum = sum.add(data[i]) ;
        }
        this.res = sum;
    }

    // 返回本区间的和
    public BigInteger getRes(){
        return this.res;
    }

    // 返回本区间的元素个数，主线程需要它来累加总个数
    public int getCount(){
        return this.length;
    }
}
