package ProducerConsumerPatternNoSleep;

/**
 * 生产者产生、消费者消费的核心对象
 * 一个 Computation 由一个 Operation 和一个 Evaluator 组成。
 */
public class Computation {
    private final Operation operation;
    private final Evaluator evaluator;
    public Computation(Operation op, Evaluator ev){
        this.evaluator = ev;
        this.operation = op;
    }
    public double evaluate(){
        return evaluator.evaluate(operation);
    }
    public Operation getOperation(){
        return this.operation;
    }
    @Override
    public String toString(){
        return operation.toString();
    }
}
