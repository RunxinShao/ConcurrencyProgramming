package matrixmultiplication;

import java.util.ArrayList;


public class Task2Main {
    private static final int NUM_OF_WORKERS = 20;
    private static final int N = 2000000;
    private static final int DIMENSION = 10;
    private static final int SECTION_SIZE = 100;

    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[NUM_OF_WORKERS];
        MultiplyWorker2[] workers = new MultiplyWorker2[NUM_OF_WORKERS];
        MatrixBuffer2 matrixBuffer = new MatrixBuffer2(N,DIMENSION,SECTION_SIZE);
        ArrayList<ArrayList<double[][]>> res = new ArrayList<>();


        for(int i = 0 ; i < NUM_OF_WORKERS; i++){

            workers[i] = new MultiplyWorker2(matrixBuffer);
            threads[i] = new Thread(workers[i]);

        }
        long startTime = System.nanoTime();
        for(int i =0; i < NUM_OF_WORKERS; i++){

            threads[i].start();
        }
        for(int i =0; i < NUM_OF_WORKERS; i++){
            threads[i].join();
        }
        long endTime = System.nanoTime();
        long elapseMs = (endTime - startTime) / 1_000_000;
        System.out.println("耗时: " + elapseMs);
        for(int i =0; i < NUM_OF_WORKERS; i++){
            res.add(workers[i].getRes());
        }


        //check result
        boolean flag = true;
        for(int i = 0; i < res.size(); i++){
            ArrayList<double[][]> workerRes = res.get(i);
            for(int j = 0; j < workerRes.size(); j++){
                double[][] m = workerRes.get(j);
                for(int row = 0; row < m.length; row++){
                    for(int col = 0; col < m[0].length; col++){
                        double expected = (row == col) ? 1.0 : 0.0;
                        if(Math.abs(m[row][col] - expected) > 1e-9){
                            flag = false;
                        }
                    }
                }
            }
        }
        System.out.println("final result is " + flag);

    }
}
