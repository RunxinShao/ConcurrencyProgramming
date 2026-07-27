package ProducerConsumerPatternNoSleep;

import java.util.Random;

/**
 * 与 ProducerConsumerPattern.ProducerWorker 完全相同，唯一区别：
 * 循环里 **没有任何 Thread.sleep**。生产者不停地生产并尝试 add，
 * 失败（缓冲区满/没抢到信号量）也立即重试，不再退避睡眠。
 * 这把系统从“睡眠主导”变成“自旋 / CPU 主导”。
 */
public class ProducerWorker implements Runnable {
    private final Buffer buffer;
    private final Evaluator evaluator = new Evaluator();
    private final Random rand  = new Random();
    private volatile boolean running = true;

    public ProducerWorker(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void run() {
        while(running){
            Computation c = new Computation(randomOperation(),evaluator);
            buffer.add(c); // 无睡眠：成功或失败都立即进入下一轮
        }
    }
    public void stop(){
        running = false;
    }
    private Operation randomOperation(){
        char[] ops = {'+', '-', '*', '/'};
        char op = ops[rand.nextInt(ops.length)];

        double left = rand.nextInt(-100,101) ;
        double right = rand.nextInt(-100, 101);

        if (op == '/'){ // 保证除数合法
            while(right == 0){
                right = rand.nextInt(-100,101);
            }
        }
        return new Operation(left,right,op);
    }


}
