package ProducerConsumerPattern;

/**
 * 一个算术运算：两个操作数 + 一个运算符。
 */
public class Operation {
    private int v1;
    private int v2;
    private char op;
    public Operation(int v1, int v2, char op){

        this.op = op;
        this.v1 = v1;
        this.v2 = v2;
    }

    public char getOp() {
        return op;
    }

    public int getV2() {
        return v2;
    }

    public int getV1() {
        return v1;
    }
    @Override
    public String toString() {
        return v1 + " " + op + " " + v2;
    }
}
