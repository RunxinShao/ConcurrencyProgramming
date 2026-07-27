package ProducerConsumerPattern;

/**
 * 一个算术运算：两个操作数 + 一个运算符。
 */
public class Operation {
    private double v1;
    private double v2;
    private char op;
    public Operation(double v1, double v2, char op){

        this.op = op;
        this.v1 = v1;
        this.v2 = v2;
    }

    public char getOp() {
        return op;
    }

    public double getV2() {
        return v2;
    }

    public double getV1() {
        return v1;
    }
    @Override
    public String toString() {
        return v1 + " " + op + " " + v2;
    }
}
