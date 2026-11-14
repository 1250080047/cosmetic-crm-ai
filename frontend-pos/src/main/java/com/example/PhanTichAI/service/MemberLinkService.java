package com.example.PhanTichAI.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The address a customer's phone uses to open the member page, best first:
 * <ol>
 *   <li>{@code member.public-url} when set (a fixed domain, e.g. a named Cloudflare tunnel) — any network;</li>
 *   <li>the Cloudflare quick tunnel started by {@code scripts/public-member.cmd} — any network (4G, another Wi-Fi).
 *       cloudflared tells its https://….trycloudflare.com address on its local metrics page;</li>
 *   <li>this computer's address on the shop Wi-Fi (192.168.x.x) — only phones on the same Wi-Fi.</li>
 * </ol>
 */
@Service
public class MemberLinkService {
    public static final String INTERNET = "internet", WIFI = "wifi", LOCAL_ONLY = "local";
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "[::1]", "::1");
    private static final List<String> VIRTUAL = List.of("virtual", "vethernet", "vmware", "hyper-v", "wsl", "loopback",
            "bluetooth", "warp", "cloudflare", "tailscale", "zerotier", "vpn", "tap-", "docker");
    private static final Pattern QUICK_TUNNEL = Pattern.compile("\"hostname\"\\s*:\\s*\"([a-z0-9-]+\\.trycloudflare\\.com)\"");
    private static final long CACHE_MILLIS = 10_000;

    private final String configured;
    private final Supplier<Optional<String>> tunnel;
    private Optional<String> cachedTunnel = Optional.empty();
    private long cachedAt = Long.MIN_VALUE / 2;

    @Autowired
    public MemberLinkService(@Value("${member.public-url:}") String configured,
                             @Value("${member.tunnel-metrics-url:http://127.0.0.1:20241/quicktunnel}") String metricsUrl) {
        this(configured, () -> askCloudflared(metricsUrl));
    }

    MemberLinkService(String configured, Supplier<Optional<String>> tunnel) {
        this.configured = configured == null ? "" : configured.trim();
        this.tunnel = tunnel;
    }

    /** {@code scope}: INTERNET (any network), WIFI (same Wi-Fi as this computer) or LOCAL_ONLY (this computer only). */
    public record Link(String url, List<String> alternatives, boolean reachableFromPhones, String scope) {}

    public Link link(HttpServletRequest request) {
        if (!configured.isEmpty()) return new Link(configured.replaceAll("/+$", "") + "/member", List.of(), true, INTERNET);
        String scheme = request.getScheme();
        int port = request.getServerPort();
        String suffix = (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443) ? "" : ":" + port) + "/member";
        List<String> lan = LOCAL.contains(request.getServerName().toLowerCase(Locale.ROOT))
                ? lanAddresses().stream().map(address -> scheme + "://" + address + suffix).toList()
                : List.of(scheme + "://" + request.getServerName() + suffix);
        Optional<String> publicHost = tunnelHost();
        if (publicHost.isPresent()) return new Link("https://" + publicHost.get() + "/member", lan, true, INTERNET);
        if (lan.isEmpty()) return new Link(scheme + "://localhost" + suffix, List.of(), false, LOCAL_ONLY);
        return new Link(lan.get(0), lan.subList(1, lan.size()), true, WIFI);
    }

    private synchronized Optional<String> tunnelHost() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MILLIS) { cachedTunnel = tunnel.get(); cachedAt = now; }
        return cachedTunnel;
    }

    /** cloudflared's metrics page answers {"hostname":"abc-def.trycloudflare.com"} while a quick tunnel runs. */
    static Optional<String> askCloudflared(String metricsUrl) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(metricsUrl)).timeout(Duration.ofMillis(800)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return answer.statusCode() == 200 ? hostnameIn(answer.body()) : Optional.empty();
        } catch (Exception notRunning) {
            if (notRunning instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static Optional<String> hostnameIn(String body) {
        Matcher match = QUICK_TUNNEL.matcher(body == null ? "" : body.toLowerCase(Locale.ROOT));
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /** IPv4 addresses of real network cards, home/shop Wi-Fi ranges first. */
    static List<String> lanAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = (card.getName() + " " + card.getDisplayName()).toLowerCase(Locale.ROOT);
                if (!card.isUp() || card.isLoopback() || card.isVirtual() || VIRTUAL.stream().anyMatch(name::contains)) continue;
                for (InetAddress address : Collections.list(card.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) found.add(address.getHostAddress());
            }
        } catch (SocketException ignored) { /* no network: fall back to localhost */ }
        found.sort(Comparator.comparingInt(MemberLinkService::rank));
        return found;
    }

    private static int rank(String address) {
        if (address.startsWith("192.168.")) return 0;
        if (address.startsWith("10.")) return 1;
        return 2;
    }
}
