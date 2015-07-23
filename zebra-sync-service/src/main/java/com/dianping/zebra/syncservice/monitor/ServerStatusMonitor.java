package com.dianping.zebra.syncservice.monitor;

import com.dianping.zebra.biz.service.SyncServerMonitorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dozer @ 6/4/15
 * mail@dozer.cc
 * http://www.dozer.cc
 */
@Component
public class ServerStatusMonitor {
    @Autowired
    private SyncServerMonitorService syncServerMonitorService;

    @Scheduled(fixedDelay = 10 * 1000)
    public void uploadStatus() {
        syncServerMonitorService.uploadStatus();
    }
}