package cn.zcj.aether.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

/**
 * P2: SSRF 防护拦截器。
 *
 * <p>在 RestTemplate 发起 HTTP 请求前校验目标 IP，
 * 阻止私有网络、回环、链路本地和云元数据端点。
 */
@Slf4j
public class SsrfSafeInterceptor implements ClientHttpRequestInterceptor {

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
        "metadata.google.internal",
        "metadata.goog"
    );

    private final boolean allowPrivateUrls;

    public SsrfSafeInterceptor(boolean allowPrivateUrls) {
        this.allowPrivateUrls = allowPrivateUrls;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        if (allowPrivateUrls) {
            return execution.execute(request, body);
        }

        URI uri = request.getURI();
        String host = uri.getHost();

        if (host == null) {
            throw new SecurityException("SSRF blocked: null host");
        }

        if (BLOCKED_HOSTNAMES.contains(host.toLowerCase())) {
            throw new SecurityException(
                    "SSRF blocked: metadata endpoint " + host);
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            if (isBlockedAddress(address)) {
                throw new SecurityException(
                        "SSRF blocked: private/reserved address " +
                        address.getHostAddress() + " (" + host + ")");
            }
        } catch (SecurityException e) {
            log.warn("SSRF 拦截: {}", e.getMessage());
            throw e;
        } catch (IOException e) {
            log.warn("SSRF: DNS 解析失败，拒绝请求: {}", host);
            throw new SecurityException("SSRF blocked: DNS resolution failed for " + host);
        }

        return execution.execute(request, body);
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return true;
        if (address.isLinkLocalAddress()) return true;
        if (address.isSiteLocalAddress()) return true;
        if (address.isMulticastAddress()) return true;

        // CGNAT (100.64.0.0/10) — Tailscale/WireGuard
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) return true;
        }

        return false;
    }
}
