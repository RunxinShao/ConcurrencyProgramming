package ProducerConsumerPattern;

import java.util.Random;

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
            boolean added = buffer.add(c);
            try{
                Thread.sleep(added?1:5);
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }
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
