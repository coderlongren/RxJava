/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import org.reactivestreams.*;

import io.reactivex.Observable.Operator;
import io.reactivex.Scheduler;
import io.reactivex.Scheduler.Worker;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.subscriptions.*;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.subscribers.SerializedSubscriber;

public final class OperatorBufferTimed<T, U extends Collection<? super T>> implements Operator<U, T> {

    final long timespan;
    final long timeskip;
    final TimeUnit unit;
    final Scheduler scheduler;
    final Supplier<U> bufferSupplier;
    final int maxSize;
    final boolean restartTimerOnMaxSize;
    
    public OperatorBufferTimed(long timespan, long timeskip, TimeUnit unit, Scheduler scheduler, Supplier<U> bufferSupplier, int maxSize,
            boolean restartTimerOnMaxSize) {
        this.timespan = timespan;
        this.timeskip = timeskip;
        this.unit = unit;
        this.scheduler = scheduler;
        this.bufferSupplier = bufferSupplier;
        this.maxSize = maxSize;
        this.restartTimerOnMaxSize = restartTimerOnMaxSize;
    }

    @Override
    public Subscriber<? super T> apply(Subscriber<? super U> t) {
        if (timespan == timeskip && maxSize == Integer.MAX_VALUE) {
            return new BufferExactUnboundedSubscriber<>(
                    new SerializedSubscriber<>(t), 
                    bufferSupplier, timespan, unit, scheduler);
        }
        Scheduler.Worker w = scheduler.createWorker();

        if (timespan == timeskip) {
            return new BufferExactBoundedSubscriber<>(
                    new SerializedSubscriber<>(t),
                    bufferSupplier,
                    timespan, unit, maxSize, restartTimerOnMaxSize, w
            );
        }
        // Can't use maxSize because what to do if a buffer is full but its
        // timespan hasn't been elapsed?
        return new BufferSkipBoundedSubscriber<>(
                new SerializedSubscriber<>(t),
                bufferSupplier, timespan, timeskip, unit, w);
    }
    
    static final class BufferExactUnboundedSubscriber<T, U extends Collection<? super T>> extends AtomicLong implements Subscriber<T>, Subscription, Runnable {
        /** */
        private static final long serialVersionUID = -2494880612098980129L;
        
        final Subscriber<? super U> actual;
        final Supplier<U> bufferSupplier;
        final long timespan;
        final TimeUnit unit;
        final Scheduler scheduler;
        
        Subscription s;
        
        U buffer;
        
        boolean selfCancel;
        
        volatile Disposable timer;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<BufferExactUnboundedSubscriber, Disposable> TIMER =
                AtomicReferenceFieldUpdater.newUpdater(BufferExactUnboundedSubscriber.class, Disposable.class, "timer");
        
        static final Disposable CANCELLED = () -> { };
        
        public BufferExactUnboundedSubscriber(
                Subscriber<? super U> actual, Supplier<U> bufferSupplier,
                long timespan, TimeUnit unit, Scheduler scheduler) {
            this.actual = actual;
            this.bufferSupplier = bufferSupplier;
            this.timespan = timespan;
            this.unit = unit;
            this.scheduler = scheduler;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validateSubscription(this.s, s)) {
                return;
            }
            this.s = s;
            
            U b;
            
            try {
                b = bufferSupplier.get();
            } catch (Throwable e) {
                cancel();
                EmptySubscription.error(e, actual);
                return;
            }
            
            if (b == null) {
                cancel();
                EmptySubscription.error(new NullPointerException("buffer supplied is null"), actual);
                return;
            }
            
            timer = scheduler.schedulePeriodicallyDirect(this, timespan, timespan, unit);
            actual.onSubscribe(this);
        }
        
        @Override
        public void onNext(T t) {
            synchronized (this) {
                U b = buffer;
                if (b == null) {
                    return;
                }
                b.add(t);
            }
        }
        
        @Override
        public void onError(Throwable t) {
            disposeTimer();
            synchronized (this) {
                buffer = null;
            }
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            disposeTimer();
            U b;
            synchronized (this) {
                b = buffer;
                buffer = null;
            }
            if (b != null) {
                long r = get();
                if (r != 0L) {
                    actual.onNext(b);
                    if (r != Long.MAX_VALUE) {
                        decrementAndGet();
                    }
                } else {
                    cancel();
                    actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));
                    return;
                }
            }
            
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validateRequest(n)) {
                return;
            }
            BackpressureHelper.add(this, n);
        }
        
        @Override
        public void cancel() {
            disposeTimer();
            
            s.cancel();
        }
        
        void disposeTimer() {
            Disposable d = timer;
            if (d != CANCELLED) {
                
            }
        }
        
        @Override
        public void run() {
            /*
             * If running on a synchronous scheduler, the timer might never
             * be set so the periodic timer can't be stopped this loopback way.
             * The last resort is to crash the task so it hopefully won't
             * be rescheduled.
             */
            if (selfCancel) {
                throw new CancellationException();
            }
            
            U next;
            
            try {
                next = bufferSupplier.get();
            } catch (Throwable e) {
                selfCancel = true;
                cancel();
                actual.onError(e);
                return;
            }
            
            if (next == null) {
                selfCancel = true;
                cancel();
                actual.onError(new NullPointerException("buffer supplied is null"));
                return;
            }
            
            U current;
            
            synchronized (this) {
                current = buffer;
                if (current != null) {
                    buffer = next;
                }
            }
            
            if (current == null) {
                selfCancel = true;
                disposeTimer();
                return;
            }

            long r = get();
            if (r != 0L) {
                actual.onNext(current);
                if (r != Long.MAX_VALUE) {
                    decrementAndGet();
                }
            } else {
                selfCancel = true;
                cancel();
                actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));
            }
        }
        
    }
    
    static final class BufferSkipBoundedSubscriber<T, U extends Collection<? super T>> extends AtomicLong implements Subscriber<T>, Subscription, Runnable {
        /** */
        private static final long serialVersionUID = -2714725589685327677L;
        final Subscriber<? super U> actual;
        final Supplier<U> bufferSupplier;
        final long timespan;
        final long timeskip;
        final TimeUnit unit;
        final Worker w;
        
        Subscription s;
        
        List<U> buffers;
        
        volatile boolean stop;

        public BufferSkipBoundedSubscriber(Subscriber<? super U> actual, 
                Supplier<U> bufferSupplier, long timespan,
                long timeskip, TimeUnit unit, Worker w) {
            this.actual = actual;
            this.bufferSupplier = bufferSupplier;
            this.timespan = timespan;
            this.timeskip = timeskip;
            this.unit = unit;
            this.w = w;
            this.buffers = new LinkedList<>();
        }
    
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validateSubscription(this.s, s)) {
                return;
            }
            this.s = s;
            
            U b;

            try {
                b = bufferSupplier.get();
            } catch (Throwable e) {
                w.dispose();
                s.cancel();
                EmptySubscription.error(e, actual);
                return;
            }
            
            if (b == null) {
                w.dispose();
                s.cancel();
                EmptySubscription.error(new NullPointerException("The supplied buffer is null"), actual);
                return;
            }
            
            buffers.add(b);
            
            w.schedulePeriodically(this, timeskip, timeskip, unit);
            
            actual.onSubscribe(this);
        }
        
        @Override
        public void onNext(T t) {
            synchronized (this) {
                buffers.forEach(b -> b.add(t));
            }
        }
        
        @Override
        public void onError(Throwable t) {
            stop = true;
            w.dispose();
            clear();
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            stop = true;
            w.dispose();
            List<U> bs;
            synchronized (this) {
                bs = new ArrayList<>(buffers);
                buffers.clear();
            }
            
            long r = get();
            for (U u : bs) {
                if (r != 0L) {
                    actual.onNext(u);
                    if (r != Long.MAX_VALUE) {
                        r = addAndGet(-1);
                    }
                } else {
                    actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));
                    return;
                }
                
            }
            
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validateRequest(n)) {
                return;
            }
            BackpressureHelper.add(this, n);
        }
        
        @Override
        public void cancel() {
            w.dispose();
            clear();
            s.cancel();
        }
        
        void clear() {
            synchronized (this) {
                buffers.clear();
            }
        }
        
        @Override
        public void run() {
            if (stop) {
                return;
            }
            U b;
            
            try {
                b = bufferSupplier.get();
            } catch (Throwable e) {
                cancel();
                actual.onError(e);
                return;
            }
            
            if (b == null) {
                cancel();
                actual.onError(new NullPointerException("The supplied buffer is null"));
                return;
            }
            
            synchronized (this) {
                if (stop) {
                    return;
                }
                buffers.add(b);
            }
            
            w.schedule(() -> {
                synchronized (this) {
                    buffers.remove(b);
                }
                
                long r = get();
                
                if (r != 0L) {
                    actual.onNext(b);
                    if (r != Long.MAX_VALUE) {
                        decrementAndGet();
                    }
                } else {
                    cancel();
                    actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));
                }
                
            }, timespan, unit);
        }
    }
    
    static final class BufferExactBoundedSubscriber<T, U extends Collection<? super T>> extends AtomicLong implements Subscriber<T>, Subscription, Runnable {
        /** */
        private static final long serialVersionUID = -1778453504578862865L;
        final Subscriber<? super U> actual;
        final Supplier<U> bufferSupplier;
        final long timespan;
        final TimeUnit unit;
        final int maxSize;
        final boolean restartTimerOnMaxSize;
        final Worker w;

        U buffer;
        
        Disposable timer;
        
        Subscription s;
        
        long producerIndex;
        
        long consumerIndex;
        
        public BufferExactBoundedSubscriber(
                Subscriber<? super U> actual,
                Supplier<U> bufferSupplier,
                long timespan, TimeUnit unit, int maxSize,
                boolean restartOnMaxSize, Worker w) {
            this.actual = actual;
            this.bufferSupplier = bufferSupplier;
            this.timespan = timespan;
            this.unit = unit;
            this.maxSize = maxSize;
            this.restartTimerOnMaxSize = restartOnMaxSize;
            this.w = w;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validateSubscription(this.s, s)) {
                return;
            }
            this.s = s;
            
            U b;

            try {
                b = bufferSupplier.get();
            } catch (Throwable e) {
                w.dispose();
                s.cancel();
                EmptySubscription.error(e, actual);
                return;
            }
            
            if (b == null) {
                w.dispose();
                s.cancel();
                EmptySubscription.error(new NullPointerException("The supplied buffer is null"), actual);
                return;
            }
            
            buffer = b;
            
            timer = w.schedulePeriodically(this, timespan, timespan, unit);
            
            actual.onSubscribe(this);
        }
        
        @Override
        public void onNext(T t) {
            U b;
            synchronized (this) {
                b = buffer;
                if (b == null) {
                    return;
                }
                
                b.add(t);
                
                if (b.size() >= maxSize && restartTimerOnMaxSize) {
                    buffer = null;
                    producerIndex++;
                } else {
                    return;
                }
            }
            
            timer.dispose();
            
            actual.onNext(b);
            
            try {
                b = bufferSupplier.get();
            } catch (Throwable e) {
                cancel();
                actual.onError(e);
                return;
            }
            
            if (b == null) {
                cancel();
                actual.onError(new NullPointerException("The buffer supplied is null"));
                return;
            }
            

            synchronized (this) {
                buffer = b;
                consumerIndex++;
            }
            
            timer = w.schedulePeriodically(this, timespan, timespan, unit);
        }
        
        @Override
        public void onError(Throwable t) {
            w.dispose();
            synchronized (this) {
                buffer = null;
            }
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            w.dispose();
            
            U b;
            synchronized (this) {
                b = buffer;
                buffer = null;
            }
            
            if (b != null) {
                long r = get();
                if (r != 0L) {
                    actual.onNext(b);
                } else {
                    actual.onError(new IllegalStateException("Could not deliver final buffer due to lack of requests"));
                    return;
                }
            }
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validateRequest(n)) {
                return;
            }
            
            BackpressureHelper.add(this, n);
        }
        
        @Override
        public void cancel() {
            w.dispose();
            synchronized (this) {
                buffer = null;
            }
            s.cancel();
        }

        @Override
        public void run() {
            U next;
            
            try {
                next = bufferSupplier.get();
            } catch (Throwable e) {
                cancel();
                actual.onError(e);
                return;
            }
            
            if (next == null) {
                cancel();
                actual.onError(new NullPointerException("The buffer supplied is null"));
                return;
            }
            
            U current;
            
            synchronized (this) {
                current = buffer;
                if (current == null || producerIndex != consumerIndex) {
                    return;
                }
                buffer = next;
            }
            
            long r = get();
            if (r != 0L) {
                actual.onNext(current);
                if (r != Long.MAX_VALUE) {
                    decrementAndGet();
                }
            } else {
                cancel();
                actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));
            }
        }
    }
}
