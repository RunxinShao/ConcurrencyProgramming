package matrixmultiplication;

import java.util.ArrayList;
import java.util.List;


public class MultiplyWorker2 implements Runnable {
    /**
     *
     */

    private MatrixBuffer2 matrixBuffer;
    private ArrayList<double[][]> res;

    public MultiplyWorker2( MatrixBuffer2 matrixBuffer){
        this.matrixBuffer = matrixBuffer;
        res = new ArrayList<>();
    }

    /**
     * 矩阵乘法的规则是：
     * 结果矩阵 C 的每个元素 C[i][j] 等于 a 的第 i 行和 b 的第 j 列做点积。
     *
     * 具体来说需要三层循环：
     *
     * 外层循环 i：遍历结果矩阵的每一行
     * 中层循环 j：遍历结果矩阵的每一列
     * 内层循环 k：做点积，也就是 a[i][k] * b[k][j] 累加
     *
     * time complixity: O(n3)
     * @param a : 矩阵a
     * @param b：矩阵b
     * @return 结果矩阵
     */
    private double[][] multiply(double[][] a, double[][] b){
        double[][] res = new double[a.length][b[0].length];
        for(int i = 0; i < a.length; i++){
            for(int j = 0; j < b[0].length; j++){
                double sum = 0;
                for (int k = 0; k < a[0].length; k++){
                    double mul = a[i][k] * b[k][j];
                    sum += mul;
                }
                res[i][j] = sum;
            }
        }
        return res;
    }

    @Override
    public void run() {
        int start = matrixBuffer.getNextSection();
        int sectionSize = matrixBuffer.getSectionSize();

        while(start != -1){
            int end = Math.min(matrixBuffer.getOffset(), start+sectionSize);
            for(int i =start; i < end; i++){
                double[][] leftM = this.matrixBuffer.getMatrix(i);
                int right = i+ this.matrixBuffer.getOffset();
                double[][] rightM = this.matrixBuffer.getMatrix(right);
                this.res.add(multiply(leftM,rightM));
            }
            start = matrixBuffer.getNextSection();
        }
    }
    public ArrayList<double[][]> getRes(){
        return res;
    }
}
