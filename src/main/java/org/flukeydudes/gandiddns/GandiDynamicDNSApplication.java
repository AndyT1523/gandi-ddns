package org.flukeydudes.gandiddns;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.flukeydudes.gandiddns.service.DynamicDNSservice;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.sun.jna.Library;
import com.sun.jna.Native;

import jakarta.annotation.PreDestroy;

@SpringBootApplication
@EnableScheduling
public class GandiDynamicDNSApplication {

	private static final String OPERATING_SYSTEM = System.getProperty("os.name").toLowerCase();
	private static final boolean USE_SYSTEMD;
	private static Logger logger = LoggerFactory.getLogger(GandiDynamicDNSApplication.class);
	private static volatile Properties properties = new Properties();
	private static String pathToEnv;

	/**
	 * Static block to determine if the application is running on a linux system
	 * with systemd.
	 */
	static {
		boolean useSystemd = false;

		if (OPERATING_SYSTEM.contains("linux")) {
			try {
				useSystemd = "systemd".equalsIgnoreCase(
						new String(new ProcessBuilder("ps", "--no-headers", "-o", "comm", "1")
								.start()
								.getInputStream()
								.readAllBytes(), StandardCharsets.UTF_8).trim());
			} catch (IOException e) {
				// do nothing
			}
		}
		USE_SYSTEMD = useSystemd;
	}

	public static void main(String[] args) {

		if (USE_SYSTEMD) {
			logger.info("systemd detected, using sd_notify.");
		}

		pathToEnv = System.getenv("APP_ENV_PATH");
		if (StringUtils.isBlank(pathToEnv)) {
			pathToEnv = "/etc/gandi-ddns.properties";
		}
		reloadProperties();

		ApplicationContext context = SpringApplication.run(GandiDynamicDNSApplication.class, args);

		SystemdNotifySocket.ready();

		Signal.handle(new Signal("HUP"), new SignalHandler() {

			@Override
			public void handle(Signal signal) {
				logger.info("Received SIGHUP signal, reloading Properties.");
				SystemdNotifySocket.reloading();
				reloadProperties();
				context.getBean(DynamicDNSservice.class).updateProperties();
				SystemdNotifySocket.ready();
			}
		});
	}

	@PreDestroy
	public void onShutdown() {
		logger.info("Application is halting.");
		SystemdNotifySocket.stopping();
	}

	private static synchronized void reloadProperties() {
		synchronized (properties) {
			try {
				properties.clear();
				properties.load(Files.newBufferedReader(Paths.get(pathToEnv)));
			} catch (IOException e) {
				logger.error("Failed to load Properties from {}. Halting!", pathToEnv, e);
				System.exit(1);
			}
		}
	}

	@Bean
	@Primary
	public Properties appProperties() {
		return properties;
	}

	public static class SystemdNotifySocket {

		public interface LibSystemd extends Library {
			LibSystemd INSTANCE = (LibSystemd) Native.load("systemd", LibSystemd.class);

			// https://man7.org/linux/man-pages/man3/sd_notify.3.html
			int sd_notify(int unset_environment, String state);
		}

		public static void ready() {
			if (USE_SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0, "READY=1");
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for READY state update, error code: " + result);
				}
			}
		}

		public static void reloading() {
			if (USE_SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0,
						"RELOADING=1\nMONOTONIC_USEC=" + (System.nanoTime() / 1000));
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for RELOADING state update, error code: " + result);
				}
			}
		}

		public static void stopping() {
			if (USE_SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0, "STOPPING=1");
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for STOPPING state update, error code: " + result);
				}
			}
		}
	}
}