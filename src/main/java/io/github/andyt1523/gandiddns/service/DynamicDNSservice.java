package io.github.andyt1523.gandiddns.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DynamicDNSservice {

    private static final Logger logger = LoggerFactory.getLogger(DynamicDNSservice.class);
    private static final int MAX_DOMAIN_RESOLVE_USES = 2;
    private static final int MAX_TRIES = 3;
    private static final long RETRY_DELAY = 5000;
    private static final int DOMAIN_TTL = 300;
    private static final String BASE_API_URL = "https://api.gandi.net/v5/livedns/domains";
    private final Supplier<Properties> propertiesSupplier;
    private volatile DynamicDNSServiceConfig config;
    private AtomicInteger domainResolveUses = new AtomicInteger(MAX_DOMAIN_RESOLVE_USES);
    private InetAddress wanIP, domainIP;
    private ObjectMapper objectMapper;
    private RestClient restClient;

    public DynamicDNSservice(Supplier<Properties> propertiesSupplier, ObjectMapper objectMapper,
            RestClient restClient) {
        this.propertiesSupplier = propertiesSupplier;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        updateProperties();
    }

    public synchronized void updateProperties() {
        Properties properties = propertiesSupplier.get();

        String apiKey = properties.getProperty("gandi.apikey");
        String domain = properties.getProperty("target.hostname");

        if (StringUtils.isBlank(apiKey) || StringUtils.isBlank(domain)) {
            throw new RuntimeException(
                    "Application properties file is missing gandi.apikey and target.hostname they must be set!");
        }

        String[] parts = domain.split("\\.");
        String apiUrl = BASE_API_URL + "/"
                + String.join(".", Arrays.copyOfRange(parts, 1, 3))
                + "/records/" + parts[0] + "/A";

        config = new DynamicDNSServiceConfig(apiKey, domain, apiUrl);

        domainResolveUses.set(MAX_DOMAIN_RESOLVE_USES);
        domainIP = null;
    }

    @Scheduled(fixedDelay = 300000) // TTL=300s
    public void scheduleDNSUpdate() {
        run();
    }

    private synchronized void run() {

        DynamicDNSServiceConfig cfg = config;

        try {
            wanIP = tryThrice(this::getWanIP, "getWanIP()");
        } catch (Exception e) {
            logger.error("Failed to get WAN IP: {}, aborting run...", e.getMessage(), e);
            return;
        }

        if (domainResolveUses.incrementAndGet() >= MAX_DOMAIN_RESOLVE_USES) {
            domainResolveUses.set(0);
            try {
                logger.info("Last domain lookup has been used twice Resolving IPv4 for {}.", cfg.domainName);
                domainIP = Inet4Address.getByName(cfg.domainName);
            } catch (UnknownHostException e) {
                logger.error("Failed to get IPv4 of {}, aborting run...", cfg.domainName, e);
                return;
            }
        }

        if (domainIP.equals(wanIP)) {
            logger.info("WAN IP and {} match {}. No update needed.", cfg.domainName, wanIP.getHostAddress());
            return;
        }

        logger.info("WAN IP and {} do not match. Updating DNS...", cfg.domainName);
        try {
            tryThrice(() -> putToGandi(cfg), "putToGandi()");
        } catch (Exception e) {
            logger.error(e.getMessage());
            return;
        }

        // Invalidate cached domain IP after successful update
        domainResolveUses.set(0);
        try {
            domainIP = InetAddress.getByAddress(wanIP.getAddress());
        } catch (UnknownHostException e) {
            logger.error("Failed to invalidate cached domain IP after successful API call.", e);
        }
    }

    private InetAddress getWanIP() throws Exception {
        return Inet4Address.getByName(restClient
                .get()
                .uri("https://api.ipify.org")
                .retrieve()
                .body(String.class));
    }

    /**
     * Sends a PUT request to the Gandi API to update the A record of the sub domain
     * specified in the env file.
     * 
     * @return null if the request is successful.
     * @throws Exception if the request fails.
     */
    private Void putToGandi(DynamicDNSServiceConfig cfg) throws Exception {

        ObjectNode bodyJson = objectMapper.createObjectNode();
        bodyJson.set("rrset_values", objectMapper.createArrayNode().add(wanIP.getHostAddress()));
        bodyJson.put("rrset_ttl", DOMAIN_TTL);

        ResponseEntity<JsonNode> response = restClient.put()
                .uri(cfg.apiUrl)
                .header("Authorization", "Bearer " + cfg.apiKey)
                .header("Content-Type", "application/json")
                .body(bodyJson)
                .retrieve()
                .toEntity(JsonNode.class);

        logger.info("Status code: " + response.getStatusCode() + " Response: " + (response.getBody() != null
                ? response.getBody()
                : "No HTTP body!"));
        logger.info("Gandi API call successful. IP address updated to {}.", wanIP.getHostAddress());

        return null;
    }

    /**
     * Tries to execute a task up to three times before giving up. Pauses for 10
     * seconds between attempts.
     * 
     * @param <T>      the return type of the task.
     * @param task     the task to execute.
     * @param taskName the name of the task being executed for logging purposes.
     * @return the result of the task.
     * @throws Exception if the task fails after three attempts.
     */
    private static <T> T tryThrice(Callable<T> task, String taskName) throws Exception {

        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                logger.error("{} attempt {}/{} failed: " + e.getMessage(), taskName, attempt, MAX_TRIES);
                if (attempt < MAX_TRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY * (1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        logger.error("Retry thread sleep interrupted.", ie);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new Exception("Task " + taskName + " failed after " + MAX_TRIES + " attempts.");
    }
}
