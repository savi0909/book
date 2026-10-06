package com.example.bookingload;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("loadtest")
public record LoaderSettings(URI target, Path resultsDir, boolean allowLocal,
                             String observerStopFile, String ingressToken) {
    void validateTarget() {
        if (target == null || !("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
                || target.getHost() == null || target.getUserInfo() != null || target.getQuery() != null
                || target.getFragment() != null || !(target.getPath().isEmpty() || target.getPath().equals("/")))
            throw new IllegalArgumentException("Configure a plain HTTP(S) gateway base URL");
        if (!allowLocal) {
            String host = target.getHost();
            String[] octets = host.split("\\.");
            if (octets.length != 4 || !octets[0].equals("100"))
                throw new IllegalArgumentException("Remote experiments require a literal Tailscale IPv4");
            try {
                int second = Integer.parseInt(octets[1]);
                for (String octet : octets) if (Integer.parseInt(octet) < 0 || Integer.parseInt(octet) > 255)
                    throw new IllegalArgumentException("Invalid Tailscale IPv4");
                if (second < 64 || second > 127) throw new IllegalArgumentException("Invalid Tailscale IPv4");
                if (!InetAddress.getLocalHost().getHostName().toLowerCase().contains("ankita"))
                    throw new IllegalArgumentException("Run the remote generator on Ankita");
            } catch (java.net.UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Cannot validate generator host/target");
            }
        } else if (!(target.getHost().equals("127.0.0.1") || target.getHost().equals("localhost")
                || target.getHost().equals("gateway"))) {
            throw new IllegalArgumentException("Local validation target must be loopback or the booking Docker gateway");
        }
    }

    String observerProblem() {
        if (observerStopFile == null || observerStopFile.isBlank())
            return allowLocal ? "OBSERVER_REQUIRED" : null;
        try {
            Path stop = Path.of(observerStopFile);
            if (Files.exists(stop)) return "OBSERVER_STOP";
            Instant heartbeat = Instant.parse(Files.readString(Path.of(observerStopFile + ".heartbeat")).trim());
            long age = Duration.between(heartbeat, Instant.now()).toMillis();
            return age < -2000 || age > 15000 ? "OBSERVER_STALE" : null;
        } catch (Exception e) { return "OBSERVER_UNAVAILABLE"; }
    }
}
