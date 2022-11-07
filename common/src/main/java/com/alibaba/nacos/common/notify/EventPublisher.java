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

import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.notify.listener.Subscriber;

/**
 * 事件发布器
 * Event publisher.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
public interface EventPublisher extends Closeable {
    
    /**
     * 初始化事件发布器
     * Initializes the event publisher.
     *
     * @param type       {@link Event >}
     * @param bufferSize Message staging queue size
     */
    void init(Class<? extends Event> type, int bufferSize);
    
    /**
     * The number of currently staged events.
     *
     * @return event size
     */
    long currentEventSize();
    
    /**
     * 添加订阅者/监听器
     * Add listener.
     *
     * @param subscriber {@link Subscriber}
     */
    void addSubscriber(Subscriber subscriber);
    
    /**
     * 移除订阅者/监听器
     * Remove listener.
     *
     * @param subscriber {@link Subscriber}
     */
    void removeSubscriber(Subscriber subscriber);
    
    /**
     * 发布事件
     * publish event.
     *
     * @param event {@link Event}
     * @return publish event is success
     */
    boolean publish(Event event);
    
    /**
     * 通知订阅者/监听器
     * Notify listener.
     *
     * @param subscriber {@link Subscriber}
     * @param event      {@link Event}
     */
    void notifySubscriber(Subscriber subscriber, Event event);
    
}
