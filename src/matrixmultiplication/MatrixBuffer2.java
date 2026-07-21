package matrixmultiplication;

/**
 * Task 2 —— 大缓冲区管理类。
 *
 * 缓冲区里连续存放 N 个矩阵：buffer[m][i][j]
 *   m = 第几个矩阵 (0 .. N-1)
 *   i, j = 该矩阵内部的行、列坐标
 *
 * 乘法配对规则（文档："Mn 配 M(n+N/2)"）：
 *   前一半 (0 .. N/2-1) 是左操作数，后一半 (N/2 .. N-1) 是右操作数。
 *   getOffset() 返回 N/2，即"右操作数区从哪里开始"。
 *
 * 这个类是纯粹的存储/访问层，不涉及线程.
 */
public class MatrixBuffer2{
    private double[][][] buffer;
    private int n ;
    private int dimension ;
    private int currentIndex = 0;
    private int sectionSize;
    public MatrixBuffer2(int n, int dimension, int sectionSize){
        this.n = n;
        this.dimension = dimension;
        buffer = new double[n][dimension][dimension];
        this.sectionSize = sectionSize;
        initIdentity();

    }
    private void initIdentity(){
        for(int i = 0; i < n; i++){
            for (int j = 0; j < dimension; j++){
                this.buffer[i][j][j] = 1;
            }
        }
    }
    public synchronized int getNextSection( ){
        int start = currentIndex;
        if(currentIndex >= getOffset()){
           return -1;
       }else{
           currentIndex += sectionSize;
       }

        return start;
    }

    public int getSectionSize(){
        return sectionSize;
    }

    public int getOffset(){
        return n/2;

    }
    public double[][] getMatrix(int index){
        return buffer[index];
    }



}
