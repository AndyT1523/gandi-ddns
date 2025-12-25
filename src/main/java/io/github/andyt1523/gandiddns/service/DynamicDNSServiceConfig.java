package io.github.andyt1523.gandiddns.service;

final class DynamicDNSServiceConfig {
    final String apiKey;
    final String domainName;
    final String apiUrl;

    public DynamicDNSServiceConfig(String apiKey, String domainName, String apiUrl) {
        this.apiKey = apiKey;
        this.domainName = domainName;
        this.apiUrl = apiUrl;
    }
}
