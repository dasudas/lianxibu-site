package com.lianxibu.site.service.identity;

/** Composes browser sessions with the separately implemented collector node use cases. */
public final class BrowserIdentityAdapter implements IdentityService {
    public interface NodeOperations {
        CollectorNodePage listNodes(Actor actor, long page, int pageSize);
        NodeCredential createNode(Actor actor, NodeCreateCommand command, String key,
                                  String csrfToken, String origin);
        NodeActionOutcome actOnNode(Actor actor, String nodeId, NodeActionCommand command,
                                    String key, String csrfToken, String origin);
    }

    private final BrowserSessionService browser;
    private final NodeOperations nodes;

    public BrowserIdentityAdapter(BrowserSessionService browser, NodeOperations nodes) {
        if (browser == null) throw new IllegalArgumentException("browser sessions required");
        this.browser = browser;
        this.nodes = nodes;
    }

    @Override
    public SessionOutcome session(String sessionCookie, String anonymousCookie) {
        return browser.session(sessionCookie, anonymousCookie);
    }

    @Override
    public SessionOutcome login(LoginCommand command, String sessionCookie, String anonymousCookie,
                                String csrfToken, String origin) {
        return browser.login(command, sessionCookie, anonymousCookie, csrfToken, origin);
    }

    @Override
    public Actor authenticate(String sessionCookie) { return browser.authenticate(sessionCookie); }

    @Override
    public Actor authorizeWrite(String sessionCookie, String csrfToken, String origin) {
        return browser.authorizeWrite(sessionCookie, csrfToken, origin);
    }

    @Override
    public SessionOutcome logout(Actor actor, String sessionCookie, String csrfToken, String origin) {
        return browser.logout(actor, sessionCookie, csrfToken, origin);
    }

    @Override
    public CollectorNodePage listNodes(Actor actor, long page, int pageSize) {
        return nodeService().listNodes(actor, page, pageSize);
    }

    @Override
    public NodeCredential createNode(Actor actor, NodeCreateCommand command, String key,
                                     String csrfToken, String origin) {
        return nodeService().createNode(actor, command, key, csrfToken, origin);
    }

    @Override
    public NodeActionOutcome actOnNode(Actor actor, String nodeId, NodeActionCommand command,
                                       String key, String csrfToken, String origin) {
        return nodeService().actOnNode(actor, nodeId, command, key, csrfToken, origin);
    }

    private NodeOperations nodeService() {
        if (nodes == null) throw new Rejected(Refusal.TEMPORARILY_UNAVAILABLE, "1");
        return nodes;
    }
}
