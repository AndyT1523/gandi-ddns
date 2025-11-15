package io.github.andyt1523.gandiddns.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class DynamicDNSservice {

    private static final Logger logger = LoggerFactory.getLogger(DynamicDNSservice.class);
    private static final int MAX_DOMAIN_RESOLVE_USES = 2;
    private static final int MAX_TRIES = 3;
    private static final long RETRY_DELAY = 10000;
    private static final int DOMAIN_TTL = 300;
    private static final String API_ENDPOINT = "https://api.gandi.net/v5/livedns/domains";
    private final Properties properties;
    private AtomicInteger domainResolveUses = new AtomicInteger(MAX_DOMAIN_RESOLVE_USES);
    private String GANDI_API_KEY, DOMAIN_NAME, API_URL;
    private InetAddress wanIP, domainIP;
    private CompletableFuture<InetAddress> futureWanIP;
    private ObjectMapper objectMapper = new ObjectMapper();

    public DynamicDNSservice(Properties properties) {
        this.properties = properties;
        updateProperties();
    }

    public void updateProperties() {

        if (StringUtils.isBlank(properties.getProperty("gandi.apikey"))
                || StringUtils.isBlank(properties.getProperty("target.hostname"))) {
            throw new RuntimeException(
                    "Application properties file is missing gandi.apikey and target.hostname they must be set!");
        }

        GANDI_API_KEY = properties.getProperty("gandi.apikey");
        DOMAIN_NAME = properties.getProperty("target.hostname");

        String[] parts = DOMAIN_NAME.split("\\.");
        StringBuilder sb = new StringBuilder();
        sb.append(API_ENDPOINT)
                .append("/")
                .append(String.join(".", Arrays.copyOfRange(parts, 1, 3))) // FQDN
                .append("/records/")
                .append(parts[0]) // rrset_name (subdomain)
                .append("/A"); // rrset_type (record type)

        API_URL = sb.toString();
    }

    @Scheduled(fixedDelay = 300000) // TTL=300s
    public void scheduleDNSUpdate() {
        run();
    }

    private void run() {

        futureWanIP = CompletableFuture.supplyAsync(() -> {
            try {
                return tryThrice(this::getWanIP, "getWanIP()");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        if (domainResolveUses.incrementAndGet() >= MAX_DOMAIN_RESOLVE_USES) {
            domainResolveUses.set(0);
            try {
                logger.info("Last domain lookup has been used twice Resolving IPv4 for {}.", DOMAIN_NAME);
                domainIP = Inet4Address.getByName(DOMAIN_NAME);
            } catch (UnknownHostException e) {
                logger.error("Failed to get IPv4 of {}, aborting run...", DOMAIN_NAME, e);
                return;
            }
        }

        try {
            wanIP = futureWanIP.join();
        } catch (CancellationException e) {
            logger.warn("WAN IP lookup task was cancelled, aborting run...\"", e);
            return;

        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                logger.error("Failed to lookup WAN IP: {}, aborting run...\"", cause.getMessage(), cause);
            } else {
                logger.error("Failed to lookup WAN IP (CompletionException with no cause), aborting run...\"", e);
            }
            return;
        }

        if (domainIP.equals(wanIP)) {
            logger.info("WAN IP and {} match {}. No update needed.", DOMAIN_NAME, wanIP.getHostAddress());
            return;

        } else {

            logger.info("WAN IP and {} do not match. Updating DNS...", DOMAIN_NAME);
            try {
                tryThrice(this::putToGandi, "postToGandi()");
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
    }

    private InetAddress getWanIP() throws Exception {
        return Inet4Address.getByName(new RestTemplate()
                .getForEntity("https://api.ipify.org", String.class).getBody());
    }

    /**
     * Sends a PUT request to the Gandi API to update the A record of the sub domain
     * specified in the env file.
     * 
     * @return null if the request is successful.
     * @throws Exception if the request fails.
     */
    @SuppressWarnings("null")
    private Void putToGandi() throws Exception {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + GANDI_API_KEY);
        headers.set("Content-Type", "application/json");

        ObjectNode body = objectMapper.createObjectNode();
        body.set("rrset_values", objectMapper.createArrayNode().add(wanIP.getHostAddress()));
        body.put("rrset_ttl", DOMAIN_TTL);

        ResponseEntity<JsonNode> response;
        try {
            response = new RestTemplate().exchange(
                    API_URL, HttpMethod.PUT, new HttpEntity<>(body, headers), JsonNode.class);
        } catch (Exception e) {
            throw e;
        }

        if (response.getStatusCode().is2xxSuccessful()) {
            logger.info("Gandi API call successful. IP address updated to {}.", wanIP.getHostAddress());

            logger.info("Status code: " + response.getStatusCode() + "\nResponse: " + (response.getBody() != null
                    ? response.getBody().toPrettyString()
                    : "No HTTP body!"));

            return null;

        } else {
            throw new Exception("Status code: " + response.getStatusCode() + "\nResponse: "
                    + (response.getBody() != null
                            ? response.getBody().toPrettyString()
                            : "No HTTP body!"));
        }
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
                logger.error("{} attempt {}/{} failed.", taskName, attempt, MAX_TRIES, e);
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
