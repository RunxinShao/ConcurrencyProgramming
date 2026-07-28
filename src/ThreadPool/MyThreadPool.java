package ThreadPool;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个用 ReentrantLock + Condition 实现的固定大小线程池。
 *
 * - 固定 poolSize 个 worker 线程，启动后各自在 workerLoop 里循环取任务执行。
 * - 任务放在一个共享队列里，用一把 ReentrantLock 保护，notEmpty 条件变量做“队列非空”的等待/唤醒。
 * - submit 往队列里塞任务并 signal；shutdown 置标志位、signalAll 唤醒所有 worker 并 join。
 */
public class MyThreadPool {
    private final int poolSize;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final Thread[] workers;
    private volatile boolean shutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition(); // “队列非空”这个条件

    public MyThreadPool(int poolSize) {
        this.poolSize = poolSize;
        this.workers = new Thread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            workers[i] = new Thread(this::workerLoop, "worker-" + i);
            workers[i].start();
        }
    }

    private void workerLoop() {
        while (true) {
            Runnable task;
            lock.lock();                       // 相当于进入 synchronized
            try {
                while (queue.isEmpty() && !shutdown) {
                    try {
                        notEmpty.await();      // 相当于 lock.wait()：挂起并释放锁
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (shutdown && queue.isEmpty()) {
                    return;
                }
                task = queue.poll();
            } finally {
                lock.unlock();                 // 必须放 finally，保证锁一定释放
            }
            // 干活放在锁外
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("[异常] " + Thread.currentThread().getName() + ": " + t);
            }
        }
    }

    public void submit(Runnable task) {
        lock.lock();
        try {
            if (shutdown) throw new IllegalStateException("线程池已关闭，拒绝提交");
            queue.offer(task);
            notEmpty.signal();                 // 相当于 lock.notify()
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();              // 相当于 lock.notifyAll()
        } finally {
            lock.unlock();
        }
        for (Thread w : workers) {
            try { w.join(); } catch (InterruptedException ignored) {}
        }
    }
}
