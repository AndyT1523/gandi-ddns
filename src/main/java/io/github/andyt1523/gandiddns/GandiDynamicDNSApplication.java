package io.github.andyt1523.gandiddns;

import java.io.File;
import java.io.FileWriter;
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
import org.springframework.web.client.RestClient;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.sun.jna.Library;
import com.sun.jna.Native;

import io.github.andyt1523.gandiddns.service.DynamicDNSservice;
import jakarta.annotation.PreDestroy;

@SpringBootApplication
@EnableScheduling
public class GandiDynamicDNSApplication {

	private static final boolean LINUX, SYSTEMD;
	private static final String PATH_TO_PROPERTIES;
	private static final String PID_FILE_PATH = "/run/gandi-ddns.pid";
	private static Logger logger = LoggerFactory.getLogger(GandiDynamicDNSApplication.class);
	private static volatile Properties properties = new Properties();

	/**
	 * Static block to determine if the application is running on a linux system
	 * with systemd.
	 */
	static {
		String pathToProperties = System.getenv("APP_ENV_PATH");
		if (StringUtils.isBlank(pathToProperties)) {
			pathToProperties = "/etc/gandi-ddns.properties";
		}
		PATH_TO_PROPERTIES = pathToProperties;

		LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
		boolean systemd = false;

		if (LINUX) {
			try {
				systemd = "systemd".equalsIgnoreCase(
						new String(new ProcessBuilder("ps", "--no-headers", "-o", "comm", "1")
								.start()
								.getInputStream()
								.readAllBytes(), StandardCharsets.UTF_8).trim());
			} catch (IOException e) {
				logger.info("Failed to detect systemd, writing PID file.");
			}
			if (!systemd) {
				try (FileWriter writer = new FileWriter(new File(PID_FILE_PATH))) {
					writer.write(String.valueOf(ProcessHandle.current().pid()));

				} catch (IOException e) {
					logger.error("Failed to write PID file at {}!", PID_FILE_PATH, e);
				}
			}
		}
		SYSTEMD = systemd;
	}

	public static void main(String[] args) {
		if (LINUX && SYSTEMD) {
			logger.info("systemd detected, using sd_notify().");
		} else if (LINUX) {
			logger.info("Linux detected, PID file written at {}.", PID_FILE_PATH);
		}

		reloadProperties();

		ApplicationContext context = SpringApplication.run(GandiDynamicDNSApplication.class, args);

		if (LINUX) {
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

		SystemdNotifySocket.ready();
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
				properties.load(Files.newBufferedReader(Paths.get(PATH_TO_PROPERTIES)));
			} catch (IOException e) {
				logger.error("Failed to load Properties from {}. Halting!", PATH_TO_PROPERTIES, e);
				System.exit(1);
			}
		}
	}

	@Bean
	@Primary
	public Properties appProperties() {
		return properties;
	}

	@Bean
	public RestClient restClient() {
		return RestClient.builder().build();
	}

	private static class SystemdNotifySocket {

		private interface LibSystemd extends Library {
			LibSystemd INSTANCE = (LibSystemd) Native.load("systemd", LibSystemd.class);

			// https://man7.org/linux/man-pages/man3/sd_notify.3.html
			int sd_notify(int unset_environment, String state);
		}

		private static void ready() {
			if (SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0, "READY=1");
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for READY state update, error code: " + result);
				}
			}
		}

		private static void reloading() {
			if (SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0,
						"RELOADING=1\nMONOTONIC_USEC=" + (System.nanoTime() / 1000));
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for RELOADING state update, error code: " + result);
				}
			}
		}

		private static void stopping() {
			if (SYSTEMD) {
				int result = LibSystemd.INSTANCE.sd_notify(0, "STOPPING=1");
				if (result < 0) {
					throw new RuntimeException(
							"Failed to notify systemd for STOPPING state update, error code: " + result);
				}
			}
		}
	}
}