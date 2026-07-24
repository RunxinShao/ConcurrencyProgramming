package diningphilosophersolutions;

public class DiningPhilosophers {
    private static final int SIZE  = 5;
    private static Fork[] forks = new Fork[SIZE];
    private static Philosopher[] philosophers = new Philosopher[SIZE];

    public static void main(String[] args){
        for(int i = 0; i < SIZE; i++){
            forks[i] = new Fork();
        }
        for(int i = 0; i < SIZE; i++){
            philosophers[i] = new Philosopher(forks[i],
                    forks[(i+1) % forks.length], i+1);
        }
        for(int i = 0; i < SIZE; i++){
            new Thread(philosophers[i]).start();
        }
    }
}
