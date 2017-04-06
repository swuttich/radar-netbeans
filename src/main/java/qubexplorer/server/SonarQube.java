package qubexplorer.server;

import java.net.URI;
import java.net.URISyntaxException;
import qubexplorer.filter.IssueFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.netbeans.api.keyring.Keyring;
import org.openide.util.NetworkSettings;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.sonar.wsclient.Host;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.SonarClient;
import org.sonar.wsclient.base.HttpException;
import org.sonar.wsclient.connectors.ConnectionException;
import org.sonar.wsclient.connectors.HttpClient4Connector;
import org.sonar.wsclient.issue.ActionPlan;
import org.sonar.wsclient.issue.Issue;
import org.sonar.wsclient.issue.IssueClient;
import org.sonar.wsclient.issue.IssueQuery;
import org.sonar.wsclient.issue.Issues;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;
import org.sonar.wsclient.services.ServerQuery;
import qubexplorer.UserCredentials;
import qubexplorer.AuthorizationException;
import qubexplorer.Classifier;
import qubexplorer.IssuesContainer;
import qubexplorer.NoSuchProjectException;
import qubexplorer.PassEncoder;
import qubexplorer.RadarIssue;
import qubexplorer.ResourceKey;
import qubexplorer.SonarQubeProjectConfiguration;
import qubexplorer.GenericSonarQubeProjectConfiguration;
import qubexplorer.Rule;
import qubexplorer.ClassifierSummary;
import qubexplorer.ClassifierType;

/**
 *
 * @author Victor
 */
public class SonarQube implements IssuesContainer {

    private static final String VIOLATIONS_DENSITY_METRICS = "violations_density";
    private static final int UNAUTHORIZED_RESPONSE_STATUS = 401;
    private static final int PAGE_SIZE = 500;
    private String serverUrl;

    public SonarQube(String servelUrl) {
        this.serverUrl = servelUrl;
        /* remove ending '/' if needed because of a problem with the underlying http client. */
        assert this.serverUrl.length() > 1;
        if (this.serverUrl.endsWith("/")) {
            this.serverUrl = this.serverUrl.substring(0, this.serverUrl.length() - 1);
        }
    }

    public SonarQube() {
        this("http://localhost:9000");
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public Version getVersion(UserCredentials userCredentials) {
        Sonar sonar = createSonar(userCredentials);
        ServerQuery serverQuery = new ServerQuery();
        return new Version(sonar.find(serverQuery).getVersion());
    }

    public double getRulesCompliance(UserCredentials userCredentials, ResourceKey resourceKey) {
        try {
            if (!existsProject(userCredentials, resourceKey)) {
                throw new NoSuchProjectException(resourceKey);
            }
            Sonar sonar = createSonar(userCredentials);
            ResourceQuery query = new ResourceQuery(resourceKey.toString());
            query.setMetrics(VIOLATIONS_DENSITY_METRICS);
            Resource r = sonar.find(query);
            return r.getMeasure(VIOLATIONS_DENSITY_METRICS).getValue();
        } catch (ConnectionException ex) {
            if (isError401(ex)) {
                throw new AuthorizationException(ex);
            } else {
                throw ex;
            }
        }
    }

    @Override
    public List<RadarIssue> getIssues(UserCredentials auth, ResourceKey projectKey, List<IssueFilter> filters) {
        if (!existsProject(auth, projectKey)) {
            throw new NoSuchProjectException(projectKey);
        }
        IssueQuery query = IssueQuery.create().componentRoots(projectKey.toString()).pageSize(PAGE_SIZE).statuses("OPEN");
        filters.forEach((filter) -> {
            filter.apply(query);
        });
        return getIssues(auth, query);
    }

    private List<RadarIssue> getIssues(UserCredentials userCredentials, IssueQuery query) {
        try {
            SonarClient sonarClient = createSonarClient(userCredentials);
            IssueClient issueClient = sonarClient.issueClient();
            List<RadarIssue> issues = new LinkedList<>();
            Map<String, Rule> rulesCache = new HashMap<>();
            Issues result;
            int pageIndex = 1;
            do {
                query.pageIndex(pageIndex);
                result = issueClient.find(query);
                for (Issue issue : result.list()) {
                    Rule rule = searchInCacheOrLoadFromServer(rulesCache, issue.ruleKey(), userCredentials);
                    RadarIssue radarIssue=new RadarIssue();
                    radarIssue.setComponentKey(issue.componentKey());
                    radarIssue.setCreationDate(issue.creationDate());
                    radarIssue.setKey(issue.key());
                    radarIssue.setLine(issue.line());
                    radarIssue.setMessage(issue.message());
                    radarIssue.setRule(rule);
                    radarIssue.setRuleKey(issue.ruleKey());
                    radarIssue.setSeverity(issue.severity());
                    radarIssue.setStatus(issue.status());
                    radarIssue.setUpdateDate(issue.updateDate());
                    issues.add(radarIssue);
                }
                pageIndex++;
            } while (result.paging().pages() != null && pageIndex <= result.paging().pages());
            return issues;
        } catch (HttpException ex) {
            if (ex.status() == UNAUTHORIZED_RESPONSE_STATUS) {
                throw new AuthorizationException(ex);
            } else {
                throw ex;
            }
        }
    }

    private Rule searchInCacheOrLoadFromServer(Map<String, Rule> rulesCache, String ruleKey, UserCredentials userCredentials) {
        Rule rule = rulesCache.get(ruleKey);
        if (rule == null) {
            rule = getRule(userCredentials, ruleKey);
            if (rule != null) {
                rulesCache.put(ruleKey, rule);
            }
        }
        if (rule == null) {
            throw new IllegalStateException("No such rule in server: " + ruleKey);
        }
        return rule;
    }

    private Sonar createSonar(UserCredentials userCredentials) {
        Host host = new Host(serverUrl);
        if (userCredentials != null) {
            host.setUsername(userCredentials.getUsername());
            host.setPassword(PassEncoder.decodeAsString(userCredentials.getPassword()));
        }
        HttpClient4Connector connector = new HttpClient4Connector(host);
        final ProxySettings proxySettings = getProxySettings();
        if (proxySettings != null) {
            DefaultHttpClient httpClient = connector.getHttpClient();
            if (proxySettings.getUsername() != null) {
                httpClient.getCredentialsProvider()
                        .setCredentials(new AuthScope(proxySettings.getHost(), proxySettings.getPort()), new UsernamePasswordCredentials(proxySettings.getUsername(), proxySettings.getPassword()));
            }
            HttpHost proxy = new HttpHost(proxySettings.getHost(), proxySettings.getPort());
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            httpClient.setRoutePlanner(routePlanner);
        }
        return new Sonar(connector);
    }

    private SonarClient createSonarClient(UserCredentials userCredentials) {
        SonarClient.Builder builder = SonarClient.builder().url(serverUrl);
        if (userCredentials != null) {
            builder.login(userCredentials.getUsername()).password(PassEncoder.decodeAsString(userCredentials.getPassword()));
        }
        ProxySettings proxySettings = getProxySettings();
        if (proxySettings != null) {
            builder.proxy(proxySettings.getHost(), proxySettings.getPort());
            if (proxySettings.getUsername() != null) {
                builder.proxyLogin(proxySettings.getUsername()).proxyPassword(proxySettings.getPassword());
            }
        }
        return builder.build();
    }

    private ProxySettings getProxySettings() {
        try {
            ProxySettings settings = null;
            URI uri = new URI(serverUrl);
            String proxyHost = NetworkSettings.getProxyHost(uri);
            if (proxyHost != null) {
                int proxyPort = 8080;
                String stringProxyPort = NetworkSettings.getProxyPort(uri);
                if (stringProxyPort != null) {
                    proxyPort = Integer.parseInt(stringProxyPort);
                }
                settings = new ProxySettings(proxyHost, proxyPort);
                String authenticationUsername = NetworkSettings.getAuthenticationUsername(uri);
                if (authenticationUsername != null) {
                    settings.setUsername(authenticationUsername);
                    settings.setKeyForPassword(NetworkSettings.getKeyForAuthenticationPassword(uri));
                }
            }
            return settings;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Wrong URI " + serverUrl, ex);
        }
    }

    public List<ActionPlan> getActionPlans(UserCredentials userCredentials, ResourceKey resourceKey) {
        try {
            SonarClient client = createSonarClient(userCredentials);
            return client.actionPlanClient().find(resourceKey.toString());
        } catch (HttpException ex) {
            if (ex.status() == UNAUTHORIZED_RESPONSE_STATUS) {
                throw new AuthorizationException(ex);
            } else {
                throw ex;
            }
        }
    }

    public Rule getRule(UserCredentials userCredentials, String ruleKey) {
        try {
            return new RuleSearchClient(serverUrl).getRule(userCredentials, ruleKey);
        } catch (HttpException ex) {
            if (ex.status() == UNAUTHORIZED_RESPONSE_STATUS) {
                throw new AuthorizationException(ex);
            }
            throw ex;
        }
    }

    public List<ResourceKey> getProjectsKeys(UserCredentials userCredentials) {
        try {
            Sonar sonar = createSonar(userCredentials);
            List<Resource> resources = sonar.findAll(new ResourceQuery());
            List<ResourceKey> keys = new ArrayList<>(resources.size());
            for (Resource r : resources) {
                keys.add(ResourceKey.valueOf(r.getKey()));
            }
            return keys;
        } catch (ConnectionException ex) {
            if (isError401(ex)) {
                throw new AuthorizationException(ex);
            } else {
                throw ex;
            }
        }
    }

    private static boolean isError401(ConnectionException ex) {
        return ex.getMessage().contains("HTTP error: 401");
    }

    public List<SonarQubeProjectConfiguration> getProjects(UserCredentials userCredentials) {
        try {
            Sonar sonar = createSonar(userCredentials);
            List<Resource> resources = sonar.findAll(new ResourceQuery());
            List<SonarQubeProjectConfiguration> projects = new ArrayList<>(resources.size());
            for (Resource r : resources) {
                projects.add(new GenericSonarQubeProjectConfiguration(r.getName(), ResourceKey.valueOf(r.getKey()), r.getVersion()));
            }
            return projects;
        } catch (ConnectionException ex) {
            if (isError401(ex)) {
                throw new AuthorizationException(ex);
            } else {
                throw ex;
            }
        }
    }

    public boolean existsProject(UserCredentials auth, ResourceKey projectKey) {
        for (ResourceKey tmp : getProjectsKeys(auth)) {
            if (tmp.equals(projectKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T extends Classifier> ClassifierSummary<T> getSummary(ClassifierType<T> classifierType, UserCredentials auth, ResourceKey resourceKey, List<IssueFilter> filters) {
        if (!existsProject(auth, resourceKey)) {
            throw new NoSuchProjectException(resourceKey);
        }
        SimpleClassifierSummary<T> simpleSummary = new SimpleClassifierSummary<>();
        List<T> values=classifierType.getValues();
        for (T classifier : values) {
            List<IssueFilter> tempFilters = new LinkedList<>();
            tempFilters.add(classifier.createFilter());
            tempFilters.addAll(filters);
            List<RadarIssue> issues = getIssues(auth, resourceKey, tempFilters);
            issues.forEach((issue) -> {
                simpleSummary.increment(classifier, issue.rule(), 1);
            });
        }
        return simpleSummary;
    }
    

    private static class ProxySettings {

        private final String host;
        private final int port;
        private String username;
        private String keyForPassword;

        public ProxySettings(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return new String(Keyring.read(keyForPassword));
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public void setKeyForPassword(String keyForPassword) {
            this.keyForPassword = keyForPassword;
        }

    }

}
