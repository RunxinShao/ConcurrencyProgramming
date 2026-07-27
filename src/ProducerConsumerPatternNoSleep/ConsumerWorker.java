package ProducerConsumerPatternNoSleep;

/**
 * 与 ProducerConsumerPattern.ConsumerWorker 完全相同，唯一区别：
 * take() 取到空（null）时 **不再 Thread.sleep**，直接自旋重试。
 */
public class ConsumerWorker implements Runnable{
    private final Buffer buffer;
    private int consumed = 0;

    private volatile boolean running = true;

    public ConsumerWorker(Buffer buffer) {
        this.buffer = buffer;
    }
    public int getConsumed(){
        return this.consumed;
    }
    public void stop(){
        this.running = false;
    }
    @Override
    public void run() {
        while (running) {
            Computation c = buffer.take();
            if (c != null) {
                c.evaluate();
                consumed++;
            }
            // 无睡眠：取到空也立即重试
        }
    }
}
