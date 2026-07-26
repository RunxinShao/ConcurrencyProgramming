package ProducerConsumerPattern;

import java.util.concurrent.Semaphore;
/**
 * 系统最核心的类：被“切片”的共享缓冲区。
 *
 * - 整个缓冲区是一个定长数组 slots（null 表示该格为空）。
 * - 数组被平均分成 numSlices 个切片，每个切片由一个二元信号量守护。
 * - 线程访问某个切片前必须 tryAcquire 该切片的信号量，用完 release。
 * - 信号量的操作全部隐藏在 add / take 内部，生产者/消费者无需知道信号量的存在。
 */
public class Buffer {
    private final int numSlices;
    private final int sliceSize;
    private Semaphore[] semaphores;
    private Computation[] slots; //buffer数组：存computation对象，null=空位

    /**
     * @param size      缓冲区总大小（格子总数）
     * @param numSlices 切片数量（= 信号量数量），必须能整除 size
     */
    public Buffer(int size, int numSlices) {

        if (size % numSlices != 0) {
            throw new IllegalArgumentException("size 必须能被 numSlices 整除");
        }
        this.slots = new Computation[size];
        this.numSlices = numSlices;
        this.sliceSize = size / numSlices;
        this.semaphores = new Semaphore[numSlices];
        for (int i = 0; i < numSlices; i++) {
            semaphores[i] = new Semaphore(1); // 二元信号量：一次只允许一个线程进该切片
        }
    }

    /**
     * 生产者调用。尝试把 c 放进某个切片的空位。
     * @return true 表示成功放入；false 表示没抢到信号量或没有空位（调用方应丢弃并重试）。
     */
    public boolean add(Computation c) {
        for(int s = 0; s < numSlices; s++) {
            //尝试获取该切片的锁
            if(semaphores[s].tryAcquire()){
                //获取到这个锁了，尝试找到该切片里的空位
                //用tryfinally，因为获取到了要在一起finnally里release
                try{
                    int start = s * sliceSize;
                    for (int i = start; i< start + sliceSize; i++){
                        if(slots[i] == null){
                            slots[i] = c;
                            return true;
                        }
                    }
                    // 这个切片里没有空位，尝试获取下一个切片的锁
                }finally{
                    semaphores[s].release();
                }
            }
        }
        return false; // 所有切片都没抢到，或者都满了，丢弃这个computation
    }
    /**
     * 消费者调用。尝试从某个切片取出一个 Computation。
     * @return 取到的对象；若没抢到信号量或缓冲区为空则返回 null（调用方应睡眠后重试）。
     */
    public Computation take(){

    }
}
