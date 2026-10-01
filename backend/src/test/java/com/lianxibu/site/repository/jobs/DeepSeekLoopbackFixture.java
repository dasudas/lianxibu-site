package com.lianxibu.site.repository.jobs;

import java.net.URI;
import java.net.http.HttpClient;

/** Test-only access to the existing injected transport; no real credential or endpoint. */
public final class DeepSeekLoopbackFixture {
    private DeepSeekLoopbackFixture() { }
    public static DeepSeekChatClient client(HttpClient transport, URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"localhost".equals(endpoint.getHost())
                || endpoint.getPort() <= 0 || !"/chat/completions".equals(endpoint.getPath()))
            throw new IllegalArgumentException("owned loopback fixture required");
        return new DeepSeekChatClient(transport, endpoint, () -> "fictional-stub-credential");
    }
}
