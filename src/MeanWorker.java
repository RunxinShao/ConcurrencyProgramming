import java.math.BigInteger;

/**
 * 创建一个实现 Runnable 接口的类。
 * 为该类添加一个构造函数，接收一个数据切片（由任务 1 中的类创建），
 * 因此该切片是一个 BigInteger 数组。
 * 该类的 run() 方法将计算该切片中数字的均值。
 * 由于我们不能改变 run() 方法的签名（它的返回值类型为 void，且不接收任何参数），
 * 所以它不能直接返回该值。因此，run() 方法应将结果存储在一个实例变量中，供之后取用。
 * 该类还需要一个方法，用于返回这个结果。当然，调用该方法所得到的结果取决于调用时机（如果调用得太早，将返回零），
 * 因此我们会在后续任务中确保每个线程确实已经执行完毕。这个方法本身不需要考虑这一点，它只需在被要求时返回结果即可。
 */
public class MeanWorker implements Runnable{
    // 这里存的是切片的“和”，而不是切片的“均值”。
    // 原因：如果每个 worker 各自先做除法求均值，再由主线程对这些均值取平均，
    // 只有在所有切片元素个数完全相等时才等于总体均值；一旦切片不等长（比如余数被分摊后），
    // “均值的均值”就会算错。而且 BigInteger.divide 是向零截断的整数除法，
    // 每个切片都除一次会逐层累积截断误差。
    // 所以让 worker 只负责求和，把唯一的一次除法留到主线程（总和 / 总个数）来做。
    private BigInteger res;
    private BigInteger[] dataSlice;
    public MeanWorker(BigInteger[] dataSlice){
        this.dataSlice = dataSlice;
    }

    @Override
    public void run() {
        BigInteger sum = BigInteger.ZERO;
        for(int i = 0; i < dataSlice.length; i++ ){
            sum = sum.add(dataSlice[i]) ;
        }
        this.res = sum;
    }

    // 返回本切片的和
    public BigInteger getRes(){
        return this.res;
    }

    // 返回本切片的元素个数，主线程需要它来累加总个数
    public int getCount(){
        return this.dataSlice.length;
    }
}
