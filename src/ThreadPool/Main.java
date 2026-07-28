package ThreadPool;

public class Main {
    public static void main(String[] args) {
        System.out.println(">>> [版本B: ReentrantLock] 池大小 N = 3，提交 8 个任务");
        MyThreadPool pool = new MyThreadPool(3);
        for (int i = 1; i <= 8; i++) {
            final int id = i;
            pool.submit(() -> {
                String name = Thread.currentThread().getName();
                System.out.println("  [开始] 任务 " + id + " @ " + name);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                System.out.println("  [完成] 任务 " + id + " @ " + name);
            });
        }
        pool.shutdown();
        System.out.println(">>> 全部完成，线程池已关闭");
    }
}
