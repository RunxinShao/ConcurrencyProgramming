import java.math.BigInteger;

/**
 *该类还应能够生成该数组的元素。最简单的方法是遍历数组，
 * 将每个元素的值设为循环索引的值，从零开始。也就是说，每个元素的值就是它自身下标的值。
 * 这本质上只是一组测试数据，无需构造更复杂的数据集。
 * 该类最重要的功能是创建数据切片。该类需要一个方法，接收一个整数，表示所需的切片数量，
 * 并返回一个 BigInteger 数组的数组，其中包含各个切片。
 */
public class DataSet {
    /**
     * 为什么用big integer：通过增大计算和内存开销，让并行化的效果在实验中"测得出来"。
     * 如果用int会很快得到结果，看不出效果
     */
    private BigInteger[] nums;
    private int length;
    private BigInteger[][] slicedData;
    public DataSet(int length){
        this.length = length;
        nums = new BigInteger[length];
        createBigIntegerArray();

    }
    private void createBigIntegerArray(){
        for(int i = 0; i < length; i++){
            nums[i] = BigInteger.valueOf(i);
        }
    }
    public BigInteger[][] getSlicedData (int slices){
        // 一个数组切成 n 份切片，然后放在一个新数组 res 里。
        // 注意：length 不一定能被 n 整除，如果直接用 length / n 做每片大小，
        // 余数部分的元素会被悄悄丢弃（数据丢失）。
        // 这里改为把余数平摊到前 remainder 个切片，保证所有元素都被覆盖、且各片大小最多相差 1。
        int n = slices;
        int base = length / n;        // 每个切片至少有的元素个数
        int remainder = length % n;   // 需要额外分配 1 个元素的切片数量

        // 用python切片代替下边的所有代码：
        //return [this.nums[i*slicesSize:(i+1)*slicesSize] for i in range(n)]
        BigInteger[][] res = new BigInteger[n][];
        int start = 0;                // 当前切片在 nums 中的起始下标
        for(int i = 0; i < n; i++){
            // 前 remainder 个切片各多分到 1 个元素
            int sliceSize = base + (i < remainder ? 1 : 0);
            res[i] = new BigInteger[sliceSize];
            for(int j = 0; j < sliceSize; j++){
                res[i][j] = this.nums[start + j];
            }
            start += sliceSize;
        }
        return res;

    }


}
