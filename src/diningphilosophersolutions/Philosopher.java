package diningphilosophersolutions;

import java.util.Random;

public class Philosopher implements Runnable {
    private int id;
    private Fork fork1; // 共享对象1（shared object
    private Fork fork2; // 共享对象2
    Random rand = new Random();
    public Philosopher(Fork f1, Fork f2, int id){
        this.id = id;
        this.fork1 = f1;
        this.fork2 = f2;
    }


    @Override
    public void run() {
        while(true){
            act("is thinking");
            synchronized(fork1){ // 外部手动给共享对象1上锁
                act("has picked up left fork.");
                synchronized(fork2){ // 拿到对象1的锁之后，尝试获取对象2的锁
                    act("has picked up right fork.");
                    act("is eating");
                }
            }
        }
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
