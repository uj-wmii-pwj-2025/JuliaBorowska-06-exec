package uj.wmii.pwj.exec;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ExecServiceTest {

    @Test
    void testExecute() {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();
        s.execute(r);
        doSleep(10);
        assertTrue(r.wasRun);
    }

    @Test
    void testScheduleRunnable() {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();
        s.submit(r);
        doSleep(10);
        assertTrue(r.wasRun);
    }

    @Test
    void testScheduleRunnableWithResult() throws Exception {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r = new TestRunnable();
        Object expected = new Object();
        Future<Object> f = s.submit(r, expected);
        doSleep(10);
        assertTrue(r.wasRun);
        assertTrue(f.isDone());
        assertEquals(expected, f.get());
    }

    @Test
    void testScheduleCallable() throws Exception {
        MyExecService s = MyExecService.newInstance();
        StringCallable c = new StringCallable("X", 10);
        Future<String> f = s.submit(c);
        doSleep(20);
        assertTrue(f.isDone());
        assertEquals("X", f.get());
    }

    @Test
    void testShutdown() {
        ExecutorService s = MyExecService.newInstance();
        s.execute(new TestRunnable());
        doSleep(10);
        s.shutdown();
        assertThrows(
            RejectedExecutionException.class,
            () -> s.submit(new TestRunnable()));
    }

    @Test
    void testIsShutdownFalseInitially() {
        ExecutorService s = MyExecService.newInstance();
        assertFalse(s.isShutdown(), "Executor should not be shutdown right after creation");
    }

    @Test
    void testIsShutdownAfterShutdown() {
        ExecutorService s = MyExecService.newInstance();
        s.shutdown();
        assertTrue(s.isShutdown(), "Executor should report shutdown after shutdown() call");
    }

    @Test
    void testIsShutdownAfterShutdownNow() {
        ExecutorService s = MyExecService.newInstance();
        s.shutdownNow();
        assertTrue(s.isShutdown(), "Executor should report shutdown after shutdownNow() call");
    }

    @Test
    void testIsTerminatedBeforeAndAfter() {
        MyExecService s = MyExecService.newInstance();
        Callable<Void> longTask = () -> {
            doSleep(20);
            return null;
        };
        s.submit(longTask);
        s.shutdown();
        assertFalse(s.isTerminated(), "Executor should not be terminated immediately after shutdown");
        doSleep(50);
        assertTrue(s.isTerminated(), "Executor should be terminated after finishing all tasks");
    }

    @Test
    void testTasksAreExecutedInOrderFIFO() {
        MyExecService s = MyExecService.newInstance();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        Runnable r1 = () -> order.add(1);
        Runnable r2 = () -> order.add(2);
        Runnable r3 = () -> order.add(3);

        s.submit(r1);
        s.submit(r2);
        s.submit(r3);

        doSleep(30);
        assertEquals(List.of(1, 2, 3), order, "Tasks should be executed in the order they were submitted (FIFO)");
    }

    @Test
    void testSubmitCallablePropagatesException() {
        MyExecService s = MyExecService.newInstance();
        Callable<Void> failingTask = () -> {
            throw new RuntimeException("Test exception");
        };
        Future<Void> f = s.submit(failingTask);
        assertThrows(ExecutionException.class, f::get, "Future.get() should wrap task exception in ExecutionException");
    }

    @Test
    void testSubmitRunnableNoResult() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Runnable task = () -> doSleep(10);
        Future<?> f = s.submit(task);
        doSleep(20);
        assertTrue(f.isDone(), "Future should be done after task execution");
        assertNull(f.get(), "Future.get() should return null for submit(Runnable)");
    }

    @Test
    void testShutdownFinishesExistingTasks() {
        MyExecService s = MyExecService.newInstance();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());

        Runnable r1 = () -> {
            doSleep(20);
            results.add(1);
        };
        Runnable r2 = () -> {
            doSleep(20);
            results.add(2);
        };
        Runnable r3 = () -> {
            doSleep(20);
            results.add(3);
        };

        s.submit(r1);
        s.submit(r2);
        s.submit(r3);
        s.shutdown();

        doSleep(100);
        assertEquals(List.of(1, 2, 3), results, "Executor should finish all tasks submitted before shutdown()");
        assertTrue(s.isTerminated(), "Executor should be terminated after all queued tasks finish");
    }

    @Test
    void testShutdownNowReturnsPendingTasks() {
        MyExecService s = MyExecService.newInstance();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());

        Runnable longTask = () -> {
            doSleep(1500);
            results.add(1);
        };
        Runnable r2 = () -> results.add(2);
        Runnable r3 = () -> results.add(3);

        s.submit(longTask);
        s.submit(r2);
        s.submit(r3);

        List<Runnable> pending = s.shutdownNow();
        assertEquals(2, pending.size(), "Two tasks should remain unexecuted");
        assertTrue(pending.contains(r2), "Task r2 should be pending");
        assertTrue(pending.contains(r3), "Task r3 should be pending");

        doSleep(20);
        assertTrue(results.isEmpty(), "Long task should not finish, because worker was interrupted by shutdownNow");
    }

    @Test
    void testShutdownNowInterruptsWorker() {
        MyExecService s = MyExecService.newInstance();
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        Runnable taskToInterrupt = () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            }
        };

        s.submit(taskToInterrupt);
        doSleep(30);
        s.shutdownNow();
        doSleep(30);

        assertTrue(wasInterrupted.get(), "Task should have been interrupted by shutdownNow()");
        assertTrue(s.isTerminated(), "Executor should be terminated after shutdownNow()");
    }

    @Test
    void testAwaitTerminationSuccess() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Runnable task = () -> doSleep(20);
        s.submit(task);
        s.shutdown();

        boolean terminated = s.awaitTermination(1, TimeUnit.SECONDS);
        assertTrue(terminated, "awaitTermination should return true when worker finishes before timeout");
        assertTrue(s.isTerminated(), "Executor should be terminated after finishing tasks");
    }

    @Test
    void testAwaitTerminationTimesOut() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Runnable longTask = () -> doSleep(2000);
        s.submit(longTask);
        s.shutdown();

        boolean terminated = s.awaitTermination(10, TimeUnit.MILLISECONDS);
        assertFalse(terminated, "awaitTermination should return false if timeout expires before worker finishes");
        assertFalse(s.isTerminated(), "Executor should not be terminated immediately after awaitTermination times out");
    }

    @Test
    void testInvokeAllReturnsAllFutures() throws Exception {
        MyExecService s = MyExecService.newInstance();

        Callable<Integer> c1 = () -> {
            doSleep(20);
            return 1;
        };
        Callable<Integer> c2 = () -> {
            doSleep(20);
            return 2;
        };
        Callable<Integer> c3 = () -> {
            doSleep(20);
            return 3;
        };

        List<Callable<Integer>> tasks = List.of(c1, c2, c3);
        List<Future<Integer>> futures = s.invokeAll(tasks);

        assertTrue(futures.get(0).isDone());
        assertTrue(futures.get(1).isDone());
        assertTrue(futures.get(2).isDone());

        assertEquals(1, futures.get(0).get());
        assertEquals(2, futures.get(1).get());
        assertEquals(3, futures.get(2).get());
    }

    @Test
    void testInvokeAllTimeout() throws Exception {
        MyExecService s = MyExecService.newInstance();

        Callable<Integer> fast = () -> {
            doSleep(20);
            return 1;
        };
        Callable<Integer> slow = () -> {
            doSleep(2000);
            return 2;
        };

        List<Callable<Integer>> tasks = List.of(fast, slow);
        List<Future<Integer>> futures = s.invokeAll(tasks, 50, TimeUnit.MILLISECONDS);

        assertTrue(futures.get(0).isDone(), "Fast task should complete before timeout");
        assertEquals(1, futures.get(0).get());
        assertTrue(futures.get(1).isCancelled() || !futures.get(1).isDone(), "Slow task should NOT complete before timeout");
        assertFalse(s.isTerminated(), "Executor should not terminate automatically after invokeAll timeout");
    }

    @Test
    void testInvokeAnyReturnsFirstCompletedResult() throws Exception {
        MyExecService s = MyExecService.newInstance();

        Callable<Integer> fast = () -> {
            doSleep(20);
            return 1;
        };
        Callable<Integer> slow1 = () -> {
            doSleep(2000);
            return 2;
        };
        Callable<Integer> slow2 = () -> {
            doSleep(2000);
            return 3;
        };

        int result = s.invokeAny(List.of(slow1, fast, slow2));
        assertEquals(1, result, "invokeAny should return result of the first task that completes");

        s.shutdown();
        s.awaitTermination(200, TimeUnit.MILLISECONDS);
        assertTrue(s.isTerminated(), "Executor should terminate properly after invokeAny and shutdown");
    }

    @Test
    void testInvokeAnyThrowsIfAllThrow() {
        MyExecService s = MyExecService.newInstance();

        Callable<Integer> t1 = () -> { throw new RuntimeException("fail1"); };
        Callable<Integer> t2 = () -> { throw new RuntimeException("fail2"); };
        Callable<Integer> t3 = () -> { throw new RuntimeException("fail3"); };

        List<Callable<Integer>> tasks = List.of(t1, t2, t3);
        assertThrows(ExecutionException.class, () -> s.invokeAny(tasks), "invokeAny should throw ExecutionException if all tasks fail");
    }

    @Test
    void testInvokeAnyTimeout() throws Exception {
        MyExecService s = MyExecService.newInstance();

        Callable<Integer> slow1 = () -> {
            doSleep(2000);
            return 1;
        };
        Callable<Integer> slow2 = () -> {
            doSleep(2000);
            return 2;
        };

        List<Callable<Integer>> tasks = List.of(slow1, slow2);
        assertThrows(TimeoutException.class, () -> s.invokeAny(tasks, 50, TimeUnit.MILLISECONDS), "invokeAny should throw TimeoutException when no task completes in time");

        s.shutdown();
        s.awaitTermination(200, TimeUnit.MILLISECONDS);
        assertTrue(s.isTerminated(), "Executor should terminate after invokeAny timeout and shutdown");
    }

    @Test
    void testFutureCancelBeforeExecution() {
        MyExecService s = MyExecService.newInstance();
        AtomicBoolean wasRun = new AtomicBoolean(false);

        Runnable task = () -> wasRun.set(true);
        Future<?> future = s.submit(task);
        future.cancel(true);
        doSleep(50);

        assertTrue(future.isCancelled(), "Future should report canceled after cancel(true)");
        assertTrue(future.isDone(), "Canceled future should be in done state");
        assertFalse(wasRun.get(), "Task should not have been executed after cancellation");
    }

    @Test
    void testFutureCancelDuringExecution() {
        MyExecService s = MyExecService.newInstance();
        AtomicBoolean taskWasInterrupted = new AtomicBoolean(false);
        AtomicBoolean taskFinishedNormally = new AtomicBoolean(false);

        Runnable longTask = () -> {
          try {
              Thread.sleep(5000);
              taskFinishedNormally.set(true);
          } catch (InterruptedException e) {
              taskWasInterrupted.set(true);
          }
        };

        Future<?> future = s.submit(longTask);
        doSleep(100);
        future.cancel(true);
        doSleep(100);

        assertTrue(taskWasInterrupted.get(), "Task should have been interrupted during execution");
        assertFalse(taskFinishedNormally.get(), "Task should NOT finish normally after being canceled");
        assertTrue(future.isCancelled(), "Future must report cancelled after cancel(true) in mid-execution");
        assertTrue(future.isDone(), "Canceled future should be in done state");
    }

    @Test
    void testWorkerContinuesAfterTaskException() {
        MyExecService s = MyExecService.newInstance();
        AtomicBoolean secondTaskRan = new AtomicBoolean(false);

        Runnable taskToFail = () -> {
            throw new RuntimeException("Intentional failure");
        };
        Runnable secondTask = () -> secondTaskRan.set(true);

        s.submit(taskToFail);
        s.submit(secondTask);
        doSleep(100);
        assertTrue(secondTaskRan.get(), "Worker should continue processing tasks even after a task throws an exception");
    }

    @Test
    void testSubmitNullTaskThrowsNullPointer() {
        MyExecService s = MyExecService.newInstance();

        assertThrows(NullPointerException.class, () -> s.submit((Runnable) null), "submit(Runnable) should throw NullPointerException for null task");
        assertThrows(NullPointerException.class, () -> s.submit((Callable<?>) null), "submit(Callable) should throw NullPointerException for null task");
    }

    @Test
    void testDoubleShutdownDoesNotCrash() {
        MyExecService s = MyExecService.newInstance();

        s.shutdown();
        s.shutdown();
    }

    @Test
    void testQueueWakeUpOnNotify() {
        MyExecService s = MyExecService.newInstance();
        AtomicBoolean wasRun = new AtomicBoolean(false);
        Runnable r = () -> wasRun.set(true);
        s.submit(r);
        doSleep(30);
        assertTrue(wasRun.get(), "Worker should wake up from wait() and execute the submitted task");
    }


    static void doSleep(int milis) {
        try {
            Thread.sleep(milis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}

class StringCallable implements Callable<String> {

    private final String result;
    private final int milis;

    StringCallable(String result, int milis) {
        this.result = result;
        this.milis = milis;
    }

    @Override
    public String call() throws Exception {
        ExecServiceTest.doSleep(milis);
        return result;
    }
}
class TestRunnable implements Runnable {

    boolean wasRun;
    @Override
    public void run() {
        wasRun = true;
    }
}
