/*
 * Copyright 2005 The Summer Boot Framework Project
 *
 * The Summer Boot Framework Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.summerframework.boot.config;

import org.summerframework.nio.server.NioServer;
import java.io.File;
import java.io.FileFilter;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author Changski Tie Zheng Zhang, Du Xiao
 */
public class ConfigurationMonitor implements FileAlterationListener {

    private static final Logger log = LogManager.getLogger(ConfigurationMonitor.class.getName());

    public static final ConfigurationMonitor listener = new ConfigurationMonitor();

    private volatile boolean running;
    private Map<File, Runnable> cfgUpdateTasks;
    private FileAlterationMonitor monitor;

    private ConfigurationMonitor() {
    }

    public void start(File folder, int intervalSec, Map<File, Runnable> cfgUpdateTasks) throws Exception {
        File pauseFile = Paths.get(folder.getAbsolutePath(), "pause").toFile();
        NioServer.setServicePaused(pauseFile.exists(), "by file detection " + pauseFile.getAbsolutePath());
        if (running) {
            return;
        }
        running = true;
        this.cfgUpdateTasks = cfgUpdateTasks;
        monitor = new FileAlterationMonitor(TimeUnit.SECONDS.toMillis(intervalSec));
        // config files
        for (File listenFile : cfgUpdateTasks.keySet()) {
            FileFilter filter = (File pathname) -> listenFile.getAbsolutePath().equals(pathname.getAbsolutePath());
            FileAlterationObserver observer = new FileAlterationObserver(folder, filter);
            observer.addListener(listener);
            monitor.addObserver(observer);
        }
        // pause trigger file
        FileFilter filter = (File pathname) -> pauseFile.getAbsolutePath().equals(pathname.getAbsolutePath());
        FileAlterationObserver observer = new FileAlterationObserver(folder, filter);
        observer.addListener(listener);
        monitor.addObserver(observer);
        // start
        monitor.start();
    }

    public void start() throws Exception {
        if (monitor != null) {
            monitor.start();
        }
    }

    public void stop() throws Exception {
        if (monitor != null) {
            monitor.stop();
        }
    }

    @Override
    public void onStart(FileAlterationObserver fao) {
        log.debug(() -> "start " + fao);
    }

    @Override
    public void onStop(FileAlterationObserver fao) {
        log.debug(() -> "stop " + fao);
    }

    @Override
    public void onDirectoryCreate(File file) {
        log.info(() -> "dir.new " + file.getAbsoluteFile());
    }

    @Override
    public void onDirectoryChange(File file) {
        log.info(() -> "dir.mod " + file.getAbsoluteFile());
    }

    @Override
    public void onDirectoryDelete(File file) {
        log.info(() -> "dir.del " + file.getAbsoluteFile());
    }

    @Override
    public void onFileCreate(File file) {
        log.info(() -> "new " + file.getAbsoluteFile());
        NioServer.setServicePaused(true, "file created " + file.getAbsolutePath());
    }

    @Override
    public void onFileChange(File file) {
        log.info(() -> "mod " + file.getAbsoluteFile());
        // decouple business logic from framework logic
        // bad example: if(file.equals(AppConstant.CFG_PATH_EMAIL)){...} 
        Runnable task = cfgUpdateTasks.get(file.getAbsoluteFile());
        if (task != null) {
            task.run();
        }
    }

    @Override
    public void onFileDelete(File file) {
        log.info(() -> "del " + file.getAbsoluteFile());
        NioServer.setServicePaused(false, "file deleted " + file.getAbsolutePath());
    }

}
