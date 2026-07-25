package diningphilosophersolutions;

import java.util.Random;

public class PhilosopherLock implements Runnable {
    private int id;
    private ForkLock fork1; // 共享对象1（shared object
    private ForkLock fork2; // 共享对象2
    Random rand = new Random();
    int thinkingCount= 0;
    int eatingCount=0;
    private volatile boolean running = true; // 主线程写、工作线程读，需要 volatile 保证可见性
    public PhilosopherLock(ForkLock f1, ForkLock f2, int id){
        this.id = id;
        this.fork1 = f1;
        this.fork2 = f2;
    }


    @Override
    public void run() {
        while(running){
            act("is thinking");
            thinkingCount++;
            if(fork1.pickUp()){ // 尝试获取forklock内部的显示锁
                act("has picked up left fork.");
                if(fork2.pickUp()){ // 拿到对象1的锁之后，尝试获取对象2的锁,如果不成功（false）

                    act("has picked up right fork.");
                    act("is eating");
                    eatingCount++;
                    //记得任务完毕之后把锁都释放掉unlock（）
                    fork1.putDown();
                    fork2.putDown();
                }else{
                    //没获取到fork2的锁，放弃获取fork2的锁，
                    //同时手上的fork1的锁也放下
                    fork1.putDown();

                }
            }
        }
    }
    // 让主线程通知哲学家停止就餐循环
    public void stop(){
        running = false;
    }

    public int getId(){
        return id;
    }

    public int getThinkingCount(){
        return thinkingCount;
    }

    public int getEatingCount(){
        return eatingCount;
    }

    private void act(String action){

        System.out.println("philosopher" + this.id +" " + action);
        int randNum = rand.nextInt(200);
        try{
            Thread.sleep(randNum);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


    }
}
