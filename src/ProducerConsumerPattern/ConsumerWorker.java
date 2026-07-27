package ProducerConsumerPattern;

import java.util.Random;

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
            } else {
                try {
                    Thread.sleep(5);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
