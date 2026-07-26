package diningphilosophersolutions;

import java.util.concurrent.Semaphore;


public class ForkBinarySemaphore {
    private Semaphore semaphore = new Semaphore(1);
    public boolean pickUp(){
        return semaphore.tryAcquire();
    }
    public void putDown(){
        semaphore.release();
    }

}
