package com.example.bookingload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LoaderSettings.class)
public class LoadTestApplication {
    public static void main(String[] args) {
        // Application retries own the budget. Never enable JDK retry of arbitrary methods.
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        SpringApplication.run(LoadTestApplication.class, args);
    }
}
