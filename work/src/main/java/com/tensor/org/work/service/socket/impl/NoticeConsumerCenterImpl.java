package com.tensor.org.work.service.socket.impl;

import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.tensor.org.api.dao.enpity.notice.NoticePackage;
import com.tensor.org.work.service.socket.NoticeChannelHandler;
import com.tensor.org.work.service.socket.NoticeConsumerCenter;
import com.tensor.org.work.service.socket.NoticePublishCenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知消费者中心，websocket channel从这里获取信息进行消费
 *
 * @author liaochuntao
 */
@Slf4j
@Component
public class NoticeConsumerCenterImpl extends Observable implements NoticeConsumerCenter {

    private final static ConcurrentHashSet<String> ONLINE_PEOPLES = new ConcurrentHashSet<>();

    @Autowired
    private NoticeChannelHandler noticeChannelHandler;
    @Autowired
    private NoticePublishCenter publishCenter;

    private static ThreadPoolExecutor PublishThreadPool;

    @PostConstruct
    private void init() {
        addObserver(publishCenter);
        PublishThreadPool = new ThreadPoolExecutor(4,
                12,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20),
                new NoticeThreadFactory(),
                new RejectHandler());
    }

    /**
     * @param o
     * @param arg
     */
    @Override
    public void update(Observable o, Object arg) {
        NoticePackage noticePackage = (NoticePackage) arg;
        PublishThreadPool.execute(new NoticeConsumeTask(noticePackage));
    }

    public static void addReceiver(String receiver) {
        if (!ONLINE_PEOPLES.contains(receiver)) {
            ONLINE_PEOPLES.add(receiver);
        }
    }

    public static void removeReceiver(String receiver) {
        ONLINE_PEOPLES.remove(receiver);
    }

    private class NoticeConsumeTask implements Runnable {

        private NoticePackage noticePackage;

        NoticeConsumeTask(NoticePackage noticePackage) {
            this.noticePackage = noticePackage;
        }

        /**
         *
         */
        @Override
        public void run() {
            HashSet<String> receivers = new HashSet<>();
            noticePackage.getReceivers()
                    .parallelStream()
                    .filter(receiver -> ONLINE_PEOPLES.contains(receiver))
                    .peek(receiver -> {
                        receivers.add(receiver);
                    })
                    .count();
            receivers.remove(noticeChannelHandler.publishMsg(noticePackage, receivers));
            noticePackage.getReceivers().removeAll(receivers);
            noticePackage.setTotalReceivers(noticePackage.getReceivers().size());
            setChanged();
            notifyObservers(noticePackage);
        }
    }

    /**
     * 线程池任务拒绝策略
     */
    private class RejectHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.error("{} 消息推送服务任务被拒绝。 {}", r.toString(), executor.toString());
        }
    }

    private class NoticeThreadFactory implements ThreadFactory {

        private final String namePrefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        NoticeThreadFactory() {
            namePrefix = "消息推送-工作线程-";
        }

        @Override
        public Thread newThread(Runnable r) {
            String name = namePrefix + nextId.getAndDecrement();
            return new Thread(r, name);
        }
    }

}
