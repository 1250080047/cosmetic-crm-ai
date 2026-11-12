package com.example.PhanTichAI.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;

/**
 * The address a customer's phone uses to open the member page. "localhost" only works on the store computer itself,
 * so when the POS is opened as localhost we look for this computer's address on the shop Wi-Fi (192.168.x.x …).
 * Set {@code member.public-url} to force an address (for example after installing HTTPS or a domain name).
 */
@Service
public class MemberLinkService {
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "[::1]", "::1");
    private static final List<String> VIRTUAL = List.of("virtual", "vethernet", "vmware", "hyper-v", "wsl", "loopback",
            "bluetooth", "warp", "cloudflare", "tailscale", "zerotier", "vpn", "tap-", "docker");
    private final String configured;

    public MemberLinkService(@Value("${member.public-url:}") String configured) { this.configured = configured.trim(); }

    public record Link(String url, List<String> alternatives, boolean reachableFromPhones) {}

    public Link link(HttpServletRequest request) {
        if (!configured.isEmpty()) return new Link(configured.replaceAll("/+$", "") + "/member", List.of(), true);
        String scheme = request.getScheme();
        int port = request.getServerPort();
        String suffix = (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443) ? "" : ":" + port) + "/member";
        if (!LOCAL.contains(request.getServerName().toLowerCase(Locale.ROOT)))
            return new Link(scheme + "://" + request.getServerName() + suffix, List.of(), true);
        List<String> addresses = lanAddresses();
        if (addresses.isEmpty()) return new Link(scheme + "://localhost" + suffix, List.of(), false);
        List<String> urls = addresses.stream().map(address -> scheme + "://" + address + suffix).toList();
        return new Link(urls.get(0), urls.subList(1, urls.size()), true);
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
