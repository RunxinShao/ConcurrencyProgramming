package ProducerConsumerPatternNoSleep;

import java.util.ArrayList;
import java.util.List;

/**
 * 与 ProducerConsumerPattern.ProducerConsumerPatternApp 结构完全一致，
 * 只是用的是本包的无睡眠 Producer/Consumer。
 */
public class ProducerConsumerPatternApp {
    static void main() throws InterruptedException{
        final int bufferSize = 12;
        final int numSlices = 4;
        final int numProducer = 3;
        final int numComsumer = 3;
        final long runTimeMillis = 30_000;



        Buffer buffer = new Buffer(bufferSize,numSlices);
        List<ProducerWorker> producers = new ArrayList<>();
        List<ConsumerWorker> consumers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for(int i = 0 ; i < numSlices; i++){
            producers.add(new ProducerWorker(buffer));
            consumers.add(new ConsumerWorker(buffer));
            threads.add(new Thread(producers.get(i)));
            threads.add(new Thread(consumers.get(i)));

        }
        for(int i = 0 ; i < threads.size(); i++){
            threads.get(i).start();

        }
        Thread.sleep(runTimeMillis);
        // 通知所有线程停止
        for (ProducerWorker p : producers) p.stop();
        for (ConsumerWorker c : consumers) c.stop();

        for(Thread t: threads){
            t.join();
        }
        int total = 0;
        for(ConsumerWorker c : consumers){
            total += c.getConsumed();
        }
        System.out.println("被消费的计算总数: " + total);


    }
}
