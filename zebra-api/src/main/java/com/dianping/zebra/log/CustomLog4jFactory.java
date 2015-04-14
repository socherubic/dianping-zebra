package com.dianping.zebra.log;

import org.apache.log4j.LogManager;
import org.apache.log4j.xml.DOMConfigurator;

public class CustomLog4jFactory {
	public static void init() {
		new DOMConfigurator().doConfigure(CustomLog4jFactory.class.getClassLoader().getResource("zebra_log4j.xml"),
		      LogManager.getLoggerRepository());
	}
}
