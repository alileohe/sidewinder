/**
 * Copyright Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.sidewinder.core.monitoring;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.srotya.sidewinder.core.rpc.Point;
import com.srotya.sidewinder.core.rpc.Tag;
import com.srotya.sidewinder.core.storage.StorageEngine;
import com.srotya.sidewinder.core.utils.MiscUtils;

/**
 * @author ambud
 */
public class ResourceMonitor {

	private static final String DB = "_internal";
	private static Logger logger = Logger.getLogger(ResourceMonitor.class.getName());
	private static final ResourceMonitor INSTANCE = new ResourceMonitor();
	private AtomicBoolean reject = new AtomicBoolean(false);
	private StorageEngine storageEngine;

	private ResourceMonitor() {
	}

	public static ResourceMonitor getInstance() {
		return INSTANCE;
	}

	public void init(StorageEngine storageEngine, ScheduledExecutorService bgTasks) {
		this.storageEngine = storageEngine;
		Map<String, String> conf = new HashMap<>(storageEngine.getConf());
		if (bgTasks != null) {
			if (!MetricsRegistryService.DISABLE_SELF_MONITORING) {
				try {
					storageEngine.getOrCreateDatabase(DB, 28, conf);
				} catch (IOException e) {
					throw new RuntimeException("Unable create internal database", e);
				}
				bgTasks.scheduleAtFixedRate(() -> systemMonitor(), 0, 2, TimeUnit.SECONDS);
			}
		}
	}

	private void systemMonitor() {
		monitorGc();

		MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
		MemoryUsage heap = mem.getHeapMemoryUsage();
		MemoryUsage nonheap = mem.getNonHeapMemoryUsage();

		validateCPUUsage();
		validateMemoryUsage("heap", heap, 10_485_760);
		validateMemoryUsage("nonheap", nonheap, 1_073_741_824);
		monitorDisk();
	}

	private void monitorDisk() {
		File disk = new File("/");

		Point point = Point.newBuilder().setDbName(DB).setMeasurementName("disk")
				.setTimestamp(System.currentTimeMillis())
				.addTags(Tag.newBuilder().setTagKey("node").setTagValue("local").build()).addValueFieldName("total")
				.addFp(false).addValue(disk.getTotalSpace()).addValueFieldName("free").addFp(false)
				.addValue(disk.getFreeSpace()).addValueFieldName("usable").addFp(false).addValue(disk.getUsableSpace())
				.build();

		try {
			storageEngine.writeDataPointWithLock(point, true);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to write internal metrics", e);
		}
	}

	private void monitorGc() {
		List<GarbageCollectorMXBean> garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
		long count = 0;
		long time = 0;
		for (GarbageCollectorMXBean bean : garbageCollectorMXBeans) {
			count += bean.getCollectionCount();
			time += bean.getCollectionTime();
		}

		Point point = Point.newBuilder().setDbName(DB).setMeasurementName("gc").setTimestamp(System.currentTimeMillis())
				.addTags(Tag.newBuilder().setTagKey("node").setTagValue("local").build()).addValueFieldName("count")
				.addFp(false).addValue(count).addValueFieldName("time").addFp(false).addValue(time).build();
		try {
			storageEngine.writeDataPointWithLock(point, true);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to write internal metrics", e);
		}
	}

	private void validateCPUUsage() {
		double systemLoadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
		try {
			storageEngine.writeDataPointWithLock(MiscUtils.buildDataPoint(DB, "cpu", "load",
					Arrays.asList(Tag.newBuilder().setTagKey("node").setTagValue("local").build()),
					System.currentTimeMillis(), systemLoadAverage), true);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to write internal metrics", e);
		}
	}

	public void validateMemoryUsage(String type, MemoryUsage mem, int min) {
		long max = mem.getMax();
		if (max == -1) {
			max = Integer.MAX_VALUE;
		}
		long used = mem.getUsed();
		try {
			storageEngine.writeDataPointWithLock(MiscUtils.buildDataPoint(DB, "memory", "used",
					Arrays.asList(Tag.newBuilder().setTagKey("type").setTagValue(type).build()),
					System.currentTimeMillis(), used), true);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to write internal metrics", e);
		}

		Point point = Point.newBuilder().setDbName(DB).setMeasurementName("memory")
				.setTimestamp(System.currentTimeMillis())
				.addTags(Tag.newBuilder().setTagKey("type").setTagValue(type).build()).addValueFieldName("max")
				.addFp(false).addValue(max).addValueFieldName("used").addFp(false).addValue(used).build();

		try {
			storageEngine.writeDataPointWithLock(point, true);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to write internal metrics", e);
		}

		logger.log(Level.FINE, "Used:" + used + ",Max:" + max);
		if ((max - used) < min) {
			logger.warning("Insufficient memory(" + used + "/" + max + "), new metrics can't be created");
		}
	}

	public boolean isReject() {
		return reject.get();
	}

}
