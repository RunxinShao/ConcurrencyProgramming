package diningphilosophersolutions;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ForkLock {
    private Lock lock = new ReentrantLock();
    public boolean pickUp(){
        return lock.tryLock();
    }
    public void putDown(){
        lock.unlock();
    }

}
