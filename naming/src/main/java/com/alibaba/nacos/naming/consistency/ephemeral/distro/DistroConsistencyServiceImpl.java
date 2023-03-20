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

package com.alibaba.nacos.naming.consistency.ephemeral.distro;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.consistency.DataOperation;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.DistroProtocol;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataProcessor;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.distributed.distro.entity.DistroKey;
import com.alibaba.nacos.naming.cluster.ServerStatus;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.ephemeral.EphemeralConsistencyService;
import com.alibaba.nacos.naming.consistency.ephemeral.distro.combined.DistroHttpCombinedKey;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.core.v2.upgrade.UpgradeJudgement;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.pojo.Record;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import org.javatuples.Pair;
import org.springframework.context.annotation.DependsOn;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A consistency protocol algorithm called <b>Distro</b>
 *
 * <p>Use a distro algorithm to divide data into many blocks. Each Nacos server node takes responsibility for exactly
 * one block of data. Each block of data is generated, removed and synchronized by its responsible server. So every
 * Nacos server only handles writings for a subset of the total service data.
 *
 * <p>At mean time every Nacos server receives data sync of other Nacos server, so every Nacos server will eventually
 * have a complete set of data.
 *
 * @author nkorange
 * @since 1.0.0
 */
@DependsOn("ProtocolManager")
@org.springframework.stereotype.Service("distroConsistencyService")
public class DistroConsistencyServiceImpl implements EphemeralConsistencyService, DistroDataProcessor {
    
    private static final String ON_RECEIVE_CHECKSUMS_PROCESSING_TAG = "1";
    
    private final DistroMapper distroMapper;
    
    private final DataStore dataStore;
    
    private final Serializer serializer;
    
    private final SwitchDomain switchDomain;
    
    private final GlobalConfig globalConfig;
    
    private final DistroProtocol distroProtocol;
    
    private volatile Notifier notifier = new Notifier();
    
    private Map<String, ConcurrentLinkedQueue<RecordListener>> listeners = new ConcurrentHashMap<>();
    
    private Map<String, String> syncChecksumTasks = new ConcurrentHashMap<>(16);
    
    public DistroConsistencyServiceImpl(DistroMapper distroMapper, DataStore dataStore, Serializer serializer,
            SwitchDomain switchDomain, GlobalConfig globalConfig, DistroProtocol distroProtocol) {
        this.distroMapper = distroMapper;
        this.dataStore = dataStore;
        this.serializer = serializer;
        this.switchDomain = switchDomain;
        this.globalConfig = globalConfig;
        this.distroProtocol = distroProtocol;
    }
    
    @PostConstruct
    public void init() {
        // spring容器启动, 当前bean初始化后会调用此方法.
        /**
         * 全局线程池执行器执行线程,
         * @see Notifier#run()
         */
        GlobalExecutor.submitDistroNotifyTask(notifier);
    }
    
    @Override
    public void put(String key, Record value) throws NacosException {
        onPut(key, value);
        // If upgrade to 2.0.X, do not sync for v1.
        if (ApplicationUtils.getBean(UpgradeJudgement.class).isUseGrpcFeatures()) {
            return;
        }
        // Nacos集群架构, 注册到注册其他节点同步
        distroProtocol.sync(new DistroKey(key, KeyBuilder.INSTANCE_LIST_KEY_PREFIX), DataOperation.CHANGE,
                DistroConfig.getInstance().getSyncDelayMillis());
    }
    
    @Override
    public void remove(String key) throws NacosException {
        onRemove(key);
        listeners.remove(key);
    }
    
    @Override
    public Datum get(String key) throws NacosException {
        return dataStore.get(key);
    }
    
    /**
     * 先将服务实例的信息存入内存的Map中, 再向阻塞队列中添加一个任务, 会有全局线程池从阻塞队列中获取任务, 异步完成注册表的实例注册.
     *
     * 为什么不在这里直接将服务实例注册到注册表中, 而要通过队列异步的注册?
     * 1. 为了提升服务注册的性能, 高并发, 如果是通过同步的方式, 性能不一定有这么高.
     * 服务注册不一定要等所有的流程执行完, 只要将服务实例的信息放入队列中, 等线程池异步去执行就好了.
     * 2. 还有各个微服务启动, 可能会引入很多中间件, 如果每个都同步去执行, 也会导致服务启动时间过长
     *
     * Put a new record.
     *
     * @param key   key of record
     * @param value record
     */
    public void onPut(String key, Record value) {
        // 将服务key和实例对象列表构建成一个新对象存入dataStore这个内存的Map中
        if (KeyBuilder.matchEphemeralInstanceListKey(key)) {
            Datum<Instances> datum = new Datum<>();
            datum.value = (Instances) value;
            datum.key = key;
            datum.timestamp.incrementAndGet();
            dataStore.put(key, datum);
        }
        
        if (!listeners.containsKey(key)) {
            return;
        }
        /**
         * 向队列添加新的通知任务。
         *
         * 会有全局线程池从阻塞队列中获取任务, 异步完成注册表的实例注册. 异步注册代码:
         * @see Notifier#run()
         */
        notifier.addTask(key, DataOperation.CHANGE);
    }
    
    /**
     * Remove a record.
     *
     * @param key key of record
     */
    public void onRemove(String key) {
        
        dataStore.remove(key);
        
        if (!listeners.containsKey(key)) {
            return;
        }
        
        notifier.addTask(key, DataOperation.DELETE);
    }
    
    /**
     * Check sum when receive checksums request.
     *
     * @param checksumMap map of checksum
     * @param server      source server request checksum
     */
    public void onReceiveChecksums(Map<String, String> checksumMap, String server) {
        
        if (syncChecksumTasks.putIfAbsent(server, ON_RECEIVE_CHECKSUMS_PROCESSING_TAG) != null) {
            // Already in process of this server:
            Loggers.DISTRO.warn("sync checksum task already in process with {}", server);
            return;
        }
        
        try {
            
            List<String> toUpdateKeys = new ArrayList<>();
            List<String> toRemoveKeys = new ArrayList<>();
            for (Map.Entry<String, String> entry : checksumMap.entrySet()) {
                if (distroMapper.responsible(KeyBuilder.getServiceName(entry.getKey()))) {
                    // this key should not be sent from remote server:
                    Loggers.DISTRO.error("receive responsible key timestamp of " + entry.getKey() + " from " + server);
                    // abort the procedure:
                    return;
                }
                
                if (!dataStore.contains(entry.getKey()) || dataStore.get(entry.getKey()).value == null || !dataStore
                        .get(entry.getKey()).value.getChecksum().equals(entry.getValue())) {
                    toUpdateKeys.add(entry.getKey());
                }
            }
            
            for (String key : dataStore.keys()) {
                
                if (!server.equals(distroMapper.mapSrv(KeyBuilder.getServiceName(key)))) {
                    continue;
                }
                
                if (!checksumMap.containsKey(key)) {
                    toRemoveKeys.add(key);
                }
            }
            
            Loggers.DISTRO
                    .info("to remove keys: {}, to update keys: {}, source: {}", toRemoveKeys, toUpdateKeys, server);
            
            for (String key : toRemoveKeys) {
                onRemove(key);
            }
            
            if (toUpdateKeys.isEmpty()) {
                return;
            }
            
            try {
                DistroHttpCombinedKey distroKey = new DistroHttpCombinedKey(KeyBuilder.INSTANCE_LIST_KEY_PREFIX,
                        server);
                distroKey.getActualResourceTypes().addAll(toUpdateKeys);
                DistroData remoteData = distroProtocol.queryFromRemote(distroKey);
                if (null != remoteData) {
                    processData(remoteData.getContent());
                }
            } catch (Exception e) {
                Loggers.DISTRO.error("get data from " + server + " failed!", e);
            }
        } finally {
            // Remove this 'in process' flag:
            syncChecksumTasks.remove(server);
        }
    }
    
    private boolean processData(byte[] data) throws Exception {
        if (data.length > 0) {
            Map<String, Datum<Instances>> datumMap = serializer.deserializeMap(data, Instances.class);
            
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
                dataStore.put(entry.getKey(), entry.getValue());
                
                if (!listeners.containsKey(entry.getKey())) {
                    // pretty sure the service not exist:
                    if (switchDomain.isDefaultInstanceEphemeral()) {
                        // create empty service
                        Loggers.DISTRO.info("creating service {}", entry.getKey());
                        Service service = new Service();
                        String serviceName = KeyBuilder.getServiceName(entry.getKey());
                        String namespaceId = KeyBuilder.getNamespace(entry.getKey());
                        service.setName(serviceName);
                        service.setNamespaceId(namespaceId);
                        service.setGroupName(Constants.DEFAULT_GROUP);
                        // now validate the service. if failed, exception will be thrown
                        service.setLastModifiedMillis(System.currentTimeMillis());
                        service.recalculateChecksum();
                        
                        // The Listener corresponding to the key value must not be empty
                        RecordListener listener = listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX).peek();
                        if (Objects.isNull(listener)) {
                            return false;
                        }
                        listener.onChange(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName), service);
                    }
                }
            }
            
            for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
                
                if (!listeners.containsKey(entry.getKey())) {
                    // Should not happen:
                    Loggers.DISTRO.warn("listener of {} not found.", entry.getKey());
                    continue;
                }
                
                try {
                    for (RecordListener listener : listeners.get(entry.getKey())) {
                        listener.onChange(entry.getKey(), entry.getValue().value);
                    }
                } catch (Exception e) {
                    Loggers.DISTRO.error("[NACOS-DISTRO] error while execute listener of key: {}", entry.getKey(), e);
                    continue;
                }
                
                // Update data store if listener executed successfully:
                dataStore.put(entry.getKey(), entry.getValue());
            }
        }
        return true;
    }
    
    @Override
    public boolean processData(DistroData distroData) {
        DistroHttpData distroHttpData = (DistroHttpData) distroData;
        Datum<Instances> datum = (Datum<Instances>) distroHttpData.getDeserializedContent();
        onPut(datum.key, datum.value);
        return true;
    }
    
    @Override
    public String processType() {
        return KeyBuilder.INSTANCE_LIST_KEY_PREFIX;
    }
    
    @Override
    public boolean processVerifyData(DistroData distroData, String sourceAddress) {
        // If upgrade to 2.0.X, do not verify for v1.
        if (ApplicationUtils.getBean(UpgradeJudgement.class).isUseGrpcFeatures()) {
            return true;
        }
        DistroHttpData distroHttpData = (DistroHttpData) distroData;
        Map<String, String> verifyData = (Map<String, String>) distroHttpData.getDeserializedContent();
        onReceiveChecksums(verifyData, sourceAddress);
        return true;
    }
    
    @Override
    public boolean processSnapshot(DistroData distroData) {
        try {
            return processData(distroData.getContent());
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void listen(String key, RecordListener listener) throws NacosException {
        if (!listeners.containsKey(key)) {
            listeners.put(key, new ConcurrentLinkedQueue<>());
        }
        
        if (listeners.get(key).contains(listener)) {
            return;
        }
        
        listeners.get(key).add(listener);
    }
    
    @Override
    public void unListen(String key, RecordListener listener) throws NacosException {
        if (!listeners.containsKey(key)) {
            return;
        }
        for (RecordListener recordListener : listeners.get(key)) {
            if (recordListener.equals(listener)) {
                listeners.get(key).remove(listener);
                break;
            }
        }
    }
    
    @Override
    public boolean isAvailable() {
        return isInitialized() || ServerStatus.UP.name().equals(switchDomain.getOverriddenServerStatus());
    }
    
    @Override
    public Optional<String> getErrorMsg() {
        String errorMsg;
        if (!isInitialized() && !ServerStatus.UP.name().equals(switchDomain.getOverriddenServerStatus())) {
            errorMsg = "Distro protocol is not initialized";
        } else {
            errorMsg = null;
        }
        return Optional.ofNullable(errorMsg);
    }
    
    public boolean isInitialized() {
        return distroProtocol.isInitialized() || !globalConfig.isDataWarmup();
    }
    
    public class Notifier implements Runnable {
        
        private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(10 * 1024);
        /** 阻塞队列 */
        private BlockingQueue<Pair<String, DataOperation>> tasks = new ArrayBlockingQueue<>(1024 * 1024);
        
        /**
         * Add new notify task to queue.
         * 向队列添加新的通知任务。
         *
         * @param datumKey data key
         * @param action   action for data
         */
        public void addTask(String datumKey, DataOperation action) {
            
            if (services.containsKey(datumKey) && action == DataOperation.CHANGE) {
                return;
            }
            if (action == DataOperation.CHANGE) {
                services.put(datumKey, StringUtils.EMPTY);
            }
            // 将服务key和事件类型封装对象, 存入内存队列, 会有其他线程从队列中获取数据执行
            tasks.offer(Pair.with(datumKey, action));
        }
        
        public int getTaskSize() {
            return tasks.size();
        }
        
        @Override
        public void run() {
            Loggers.DISTRO.info("distro notifier started");
            // 无限循环处理
            for (; ; ) {
                try {
                    // 从内存阻塞队列中获取由服务key和事件类型构件的对象
                    Pair<String, DataOperation> pair = tasks.take();
                    // 处理由服务key和事件类型构件的对象
                    handle(pair);
                } catch (Throwable e) {
                    Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
                }
            }
        }
        
        private void handle(Pair<String, DataOperation> pair) {
            try {
                // 服务key
                String datumKey = pair.getValue0();
                DataOperation action = pair.getValue1();
                
                services.remove(datumKey);
                
                int count = 0;
                
                if (!listeners.containsKey(datumKey)) {
                    return;
                }
                
                for (RecordListener listener : listeners.get(datumKey)) {
                    
                    count++;
                    
                    try {
                        if (action == DataOperation.CHANGE) {
                            /**
                             * 通过服务key去注册表中获取服务实例列表
                             *
                             * @see Service#onChange(java.lang.String, com.alibaba.nacos.naming.core.Instances)
                             */
                            listener.onChange(datumKey, dataStore.get(datumKey).value);
                            continue;
                        }
                        
                        if (action == DataOperation.DELETE) {
                            listener.onDelete(datumKey);
                            continue;
                        }
                    } catch (Throwable e) {
                        Loggers.DISTRO.error("[NACOS-DISTRO] error while notifying listener of key: {}", datumKey, e);
                    }
                }
                
                if (Loggers.DISTRO.isDebugEnabled()) {
                    Loggers.DISTRO
                            .debug("[NACOS-DISTRO] datum change notified, key: {}, listener count: {}, action: {}",
                                    datumKey, count, action.name());
                }
            } catch (Throwable e) {
                Loggers.DISTRO.error("[NACOS-DISTRO] Error while handling notifying task", e);
            }
        }
    }
}
