/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.common.notify;

import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.alibaba.nacos.common.notify.NotifyCenter.ringBufferSize;

/**
 * 默认事件发布者, 借助阻塞队列
 * The default event publisher implementation.
 *
 * <p>Internally, use {@link ArrayBlockingQueue <Event/>} as a message staging queue.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
public class DefaultPublisher extends Thread implements EventPublisher {

    protected static final Logger LOGGER = LoggerFactory.getLogger(NotifyCenter.class);

    private volatile boolean initialized = false;

    private volatile boolean shutdown = false;

    private Class<? extends Event> eventType;

    protected final ConcurrentHashSet<Subscriber> subscribers = new ConcurrentHashSet<>();

    private int queueMaxSize = -1;

    /**
     * 阻塞队列: 用户存放事件
     */
    private BlockingQueue<Event> queue;

    protected volatile Long lastEventSequence = -1L;

    private static final AtomicReferenceFieldUpdater<DefaultPublisher, Long> UPDATER = AtomicReferenceFieldUpdater
            .newUpdater(DefaultPublisher.class, Long.class, "lastEventSequence");

    @Override
    public void init(Class<? extends Event> type, int bufferSize) {
        setDaemon(true);
        setName("nacos.publisher-" + type.getName());
        this.eventType = type;
        this.queueMaxSize = bufferSize;
        // 实例化阻塞队列
        this.queue = new ArrayBlockingQueue<>(bufferSize);
        // 启动线程, 执行
        start();
    }

    public ConcurrentHashSet<Subscriber> getSubscribers() {
        return subscribers;
    }

    @Override
    public synchronized void start() {
        if (!initialized) {
            // start just called once
            /**
             * 因为当前事件发布者继承了Thread, 所以这里调用父类的start(). 实际上会执行{@link DefaultPublisher#run()}方法, 来执行事件
             */
            super.start();
            if (queueMaxSize == -1) {
                queueMaxSize = ringBufferSize;
            }
            initialized = true;
        }
    }
    
    @Override
    public long currentEventSize() {
        return queue.size();
    }

    /**
     * 启动线程, 执行事件
     */
    @Override
    public void run() {
        openEventHandler();
    }

    /**
     * 打开事件处理器, 执行事件
     */
    void openEventHandler() {
        try {
            
            // This variable is defined to resolve the problem which message overstock in the queue.
            int waitTimes = 60;
            // To ensure that messages are not lost, enable EventHandler when
            // waiting for the first Subscriber to register
            for (; ; ) {
                if (shutdown || hasSubscriber() || waitTimes <= 0) {
                    break;
                }
                ThreadUtils.sleep(1000L);
                waitTimes--;
            }
            
            for (; ; ) {
                if (shutdown) {
                    break;
                }
                // 从队列中取出事件
                final Event event = queue.take();
                // 通知订阅者处理事件
                receiveEvent(event);
                UPDATER.compareAndSet(this, lastEventSequence, Math.max(lastEventSequence, event.sequence()));
            }
        } catch (Throwable ex) {
            LOGGER.error("Event listener exception : ", ex);
        }
    }
    
    private boolean hasSubscriber() {
        return CollectionUtils.isNotEmpty(subscribers);
    }
    
    @Override
    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }
    
    @Override
    public void removeSubscriber(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }
    
    @Override
    public boolean publish(Event event) {
        // 检查事件发布器是否已初始化, 可以开始
        checkIsStart();
        // 把事件放入阻塞队列中, 如果队列满了, 不能容纳, 返回false
        boolean success = this.queue.offer(event);
        if (!success) {
            LOGGER.warn("Unable to plug in due to interruption, synchronize sending time, event : {}", event);
            // 如果队列满了, 直接通知订阅者处理事件
            receiveEvent(event);
            return true;
        }
        return true;
    }
    
    void checkIsStart() {
        if (!initialized) {
            throw new IllegalStateException("Publisher does not start");
        }
    }
    
    @Override
    public void shutdown() {
        this.shutdown = true;
        this.queue.clear();
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 接收并通知订阅者处理事件。
     * Receive and notifySubscriber to process the event.
     *
     * @param event {@link Event}.
     */
    void receiveEvent(Event event) {
        final long currentEventSequence = event.sequence();
        // 判断是否有订阅者/监听器
        if (!hasSubscriber()) {
            LOGGER.warn("[NotifyCenter] the {} is lost, because there is no subscriber.", event);
            return;
        }
        
        // Notification single event listener 遍历通知每个事件的订阅者/监听器
        for (Subscriber subscriber : subscribers) {
            // 事件范围匹配
            if (!subscriber.scopeMatches(event)) {
                continue;
            }
            
            // Whether to ignore expiration events 是否忽略到期事件
            if (subscriber.ignoreExpireEvent() && lastEventSequence > currentEventSequence) {
                LOGGER.debug("[NotifyCenter] the {} is unacceptable to this subscriber, because had expire",
                        event.getClass());
                continue;
            }
            
            // Because unifying smartSubscriber and subscriber, so here need to think of compatibility. 因为统一了smartSubscriber和subscriber，所以这里需要考虑兼容性。
            // Remove original judge part of codes. 删除代码的原始法官部分。
            // 通知订阅者执行
            notifySubscriber(subscriber, event);
        }
    }
    
    @Override
    public void notifySubscriber(final Subscriber subscriber, final Event event) {
        
        LOGGER.debug("[NotifyCenter] the {} will received by {}", event, subscriber);
        /**
         * 订阅者处理事件
         * @see
         *
         */
        final Runnable job = () -> subscriber.onEvent(event);
        final Executor executor = subscriber.executor();
        
        if (executor != null) {
            executor.execute(job);
        } else {
            try {
                job.run();
            } catch (Throwable e) {
                LOGGER.error("Event callback exception: ", e);
            }
        }
    }
}
