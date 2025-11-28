package uj.wmii.pwj.exec;

import java.util.*;
import java.util.concurrent.*;

public class MyExecService implements ExecutorService {
    private volatile boolean shutdown;
    private volatile boolean terminated;
    private final Deque<Runnable> queue;
    private final Map<Runnable, FutureTask<?>> futures = new HashMap<>();
    private Thread workerThread;
    private volatile Runnable runningTask = null;
    private final Object startLock = new Object();

    private MyExecService() {
        this.shutdown = false;
        this.terminated = false;
        this.queue = new ArrayDeque<>();
        startWorker();
    }

    static MyExecService newInstance() {
        return new MyExecService();
    }

    private void startWorker() {
        workerThread = new Thread(() -> {
            try {
                runWorkerLoop();
            } finally {
                synchronized (this) {
                    terminated = true;
                    this.notifyAll();
                }
            }
        }, "MyExecService-Worker");

        workerThread.setDaemon(false);
        workerThread.start();
    }

    private void runWorkerLoop() {
        try {
            while(true) {
                Runnable r;
                FutureTask<?> task;

                synchronized (queue) {
                    while (queue.isEmpty() && !shutdown) {
                        try {
                            queue.wait();
                        } catch (InterruptedException e) {
                            if (shutdown) return;
                        }
                    }
                    if (queue.isEmpty() && shutdown) return;
                    r = queue.pollFirst();
                    runningTask = r;
                    synchronized (startLock) {
                        startLock.notifyAll();
                    }
                }
                synchronized (futures) {
                    task = futures.remove(r);
                }

                if (task != null && !task.isCancelled()) {
                    try {
                        task.run();
                    } catch (Throwable ignored) {}
                }

                synchronized (queue) {
                    if (runningTask == r) runningTask = null;
                }
            }
        } finally {
            synchronized (this) {
                terminated = true;
                this.notifyAll();
            }
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        synchronized (queue) {
            queue.notifyAll();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> snapshot;
        List<Runnable> pending = new ArrayList<>();

        synchronized (queue) {
            snapshot = new ArrayList<>(queue);
        }

        if (!snapshot.isEmpty() && runningTask == null) {
            synchronized (startLock) {
                long waitTime = 50;
                long deadline = System.currentTimeMillis() + waitTime;
                while (runningTask == null) {
                    long toWait = deadline - System.currentTimeMillis();
                    if (toWait <= 0) break;
                    try {
                        startLock.wait(toWait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }

        synchronized (queue) {
            snapshot = new ArrayList<>(queue);
            queue.clear();
        }
        synchronized (futures) {
            for (Runnable r : snapshot) {
                if (r == runningTask) continue;
                FutureTask<?> ft = futures.remove(r);
                if (ft != null) {
                    ft.cancel(true);
                    pending.add(r);
                }
            }
        }

        workerThread.interrupt();
        return pending;
    }


    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit==null) throw new NullPointerException();

        Thread t = workerThread;
        if (t==null) return terminated;

        long millis = unit.toMillis(timeout);
        if (millis<=0) return terminated;

        t.join(millis);
        return terminated;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (task==null) throw new NullPointerException();

        FutureTask<T> ft = new FutureTask<>(task);
        if (shutdown) throw new RejectedExecutionException("Executor already shut down (1)");

        synchronized (futures) {
            if (shutdown) throw new RejectedExecutionException("Executor already shut down (2)");
            futures.put(ft, ft);
        }
        synchronized (queue) {
            queue.addLast(ft);
            queue.notifyAll();
        }

        return ft;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        if (task==null) throw new NullPointerException();

        Callable<T> callable = () -> {
            task.run();
            return result;
        };

        FutureTask<T> ft = new FutureTask<>(callable);
        if (shutdown) throw new RejectedExecutionException("Executor already shut down (1)");

        synchronized (futures) {
            if (shutdown) throw new RejectedExecutionException("Executor already shut down (2)");
            futures.put(task, ft);
        }
        synchronized (queue) {
            queue.addLast(task);
            queue.notifyAll();
        }

        return ft;
    }

    @Override
    public Future<?> submit(Runnable task) {
        if (task==null) throw new NullPointerException();

        FutureTask<?> ft = new FutureTask<>(task, null);
        if (shutdown) throw new RejectedExecutionException("Executor already shut down (1)");

        synchronized (futures) {
            if (shutdown) throw new RejectedExecutionException("Executor already shut down (2)");
            futures.put(task, ft);
        }
        synchronized (queue) {
            queue.addLast(task);
            queue.notifyAll();
        }

        return ft;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        if (tasks==null) throw new NullPointerException();

        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }

        for (Future<T> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ignored) {}
        }

        return futures;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        if (tasks==null || unit==null) throw new NullPointerException();

        long preciseTimeout = unit.toNanos(timeout);
        long deadline = System.nanoTime() + preciseTimeout;
        List<Future<T>> futures = new ArrayList<>();

        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }

        for (Future<T> f : futures) {
            long timeLeft = deadline - System.nanoTime();
            if (timeLeft<=0) {
                for (Future<T> pending : futures) {
                    if(!pending.isDone()) pending.cancel(true);
                }
                return futures;
            }

            try {
                f.get(timeLeft, TimeUnit.NANOSECONDS);
            } catch (ExecutionException ignored) {
            } catch (TimeoutException e) {
                for (Future<T> pending : futures) {
                    if(!pending.isDone()) pending.cancel(true);
                }
                return futures;
            }
        }

        return futures;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        if (tasks==null) throw new NullPointerException();

        List<FutureTask<T>> futures = new ArrayList<>();
        ExecutionException executionException = null;

        for (Callable<T> task : tasks) {
            FutureTask<T> ft = new FutureTask<>(task);
            Thread th = new Thread(ft, "invokeAny-worker");
            futures.add(ft);
            th.start();
        }

        if (futures.isEmpty()) throw new ExecutionException(new Exception("No tasks provided"));

        try {
            while (true) {
                boolean notAnyDone = false;

                for (FutureTask<T> ft : futures) {
                    if (ft.isCancelled()) continue;

                    if (ft.isDone()) {
                        try {
                            T result = ft.get();
                            for (FutureTask<T> other : futures) {
                                if (other!=ft && !other.isDone()) other.cancel(true);
                            }
                            return result;
                        } catch (ExecutionException e) {
                            executionException = e;
                        }
                    } else {
                        notAnyDone = true;
                    }
                }

                if (!notAnyDone) {
                    if (executionException!=null) throw executionException;
                    throw new ExecutionException(new Exception("No task completed successfully"));
                }

                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    for (FutureTask<T> ft : futures) {
                        if (!ft.isDone()) ft.cancel(true);
                    }
                    throw e;
                }
            }
        } finally {
            for (FutureTask<T> ft : futures) {
                if (!ft.isDone()) ft.cancel(true);
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (tasks==null || unit==null) throw new NullPointerException();

        long preciseTimeout = unit.toNanos(timeout);
        long deadline = System.nanoTime() + preciseTimeout;

        List<FutureTask<T>> futures = new ArrayList<>();
        ExecutionException executionException = null;

        for (Callable<T> task : tasks) {
            FutureTask<T> ft = new FutureTask<>(task);
            Thread th = new Thread(ft, "invokeAny-worker");
            futures.add(ft);
            th.start();
        }

        if (futures.isEmpty()) throw new ExecutionException(new Exception("No tasks provided"));

        try {
            while (true) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    for (FutureTask<T> ft : futures) {
                        if (!ft.isDone()) ft.cancel(true);
                    }
                    throw new TimeoutException("invokeAny timed out");
                }

                boolean notAnyDone = true;
                for (FutureTask<T> ft : futures) {
                    if (ft.isCancelled()) continue;

                    if (ft.isDone()) {
                        try {
                            T result = ft.get();
                            for (FutureTask<T> other : futures) {
                                if (other != ft && !other.isDone()) other.cancel(true);
                            }
                            return result;
                        } catch (ExecutionException e) {
                            executionException = e;
                        }
                    } else {
                        notAnyDone = false;
                    }
                }

                if (notAnyDone) {
                    if (executionException != null) throw executionException;
                    throw new ExecutionException(new Exception("No task completed successfully"));
                }

                long sleepTime = Math.min(1, TimeUnit.NANOSECONDS.toMillis(timeLeft));
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    for (FutureTask<T> ft : futures) {
                        if (!ft.isDone()) ft.cancel(true);
                    }
                    throw e;
                }
            }
        } finally {
            for (FutureTask<T> ft : futures) {
                if (!ft.isDone()) ft.cancel(true);
            }
        }
    }

    @Override
    public void execute(Runnable command) {
        if (command==null) throw new NullPointerException();
        submit(command);
    }
}
