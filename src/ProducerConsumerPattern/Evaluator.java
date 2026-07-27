package ProducerConsumerPattern;

/**
 * 负责对一个operation求值
 */
public class Evaluator {
    public double evaluate(Operation op){
        switch (op.getOp()){
            case '+': return op.getV1() + op.getV2();
            case '-': return op.getV1() - op.getV2();
            case '*': return op.getV1() * op.getV2();
            case '/': return op.getV1() / op.getV2();
            default:
                throw new IllegalArgumentException("unknown operator" + op.getOp());
        }
    }
}
