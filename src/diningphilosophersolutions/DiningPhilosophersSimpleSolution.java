package diningphilosophersolutions;

public class DiningPhilosophersSimpleSolution {
    private static final int SIZE = 5;
    private static final long RUN_MILLIS = 5000; // 运行多久后停下来统计
    private static Fork[] forks = new Fork[SIZE];
    private static Philosopher[] philosophers = new Philosopher[SIZE];

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < SIZE; i++) {
            forks[i] = new Fork();
        }
        // new all philosopher except the last one
        for (int i = 0; i < SIZE - 1; i++) {
            philosophers[i] = new Philosopher(forks[i],
                    forks[(i + 1) % forks.length], i + 1);
        }

        // reverse the order of forks for the last philosopher
        philosophers[SIZE - 1] = new Philosopher(forks[(SIZE) % forks.length],
                forks[SIZE -1], SIZE);

        Thread[] threads = new Thread[SIZE];
        for (int i = 0; i < SIZE; i++) {
            threads[i] = new Thread(philosophers[i]);
            threads[i].start();
        }

        // 让哲学家们吃/想一段时间
        Thread.sleep(RUN_MILLIS);

        // 通知所有哲学家停止，然后等各线程退出循环
        for (int i = 0; i < SIZE; i++) {
            philosophers[i].stop();
        }
        for (int i = 0; i < SIZE; i++) {
            threads[i].join();
        }

        // 所有线程 join 之后再读统计，天然满足 happens-before，读到的都是最终值
        System.out.println();
        for (int i = 0; i < SIZE; i++) {
            Philosopher p = philosophers[i];
            System.out.println("Philosopher " + p.getId()
                    + " thought " + p.getThinkingCount() + " times and ate "
                    + p.getEatingCount() + " times.");
        }
    }
}
