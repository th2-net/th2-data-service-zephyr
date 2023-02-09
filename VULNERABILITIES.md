# The list of vulnerabilities in transitive dependencies that does not affect the application and cannot be updated

## org.codehaus.jettison:jettison:1.3.7
```
com.atlassian.jira:jira-rest-java-client-core:5.2.4
+--- org.glassfish.jersey.media:jersey-media-json-jettison:2.35
   \--- org.codehaus.jettison:jettison:1.3.7
```
It has 4 high vulnerabilities: [CVE-2022-40149](https://avd.aquasec.com/nvd/cve-2022-40149),
[CVE-2022-40150](https://avd.aquasec.com/nvd/cve-2022-40150),
[CVE-2022-45685](https://avd.aquasec.com/nvd/cve-2022-45685), 
[CVE-2022-45693](https://avd.aquasec.com/nvd/cve-2022-45693)</br>
None of them affect the application because it is used in REST client library for JIRA.
Jira API should not return any responses that can exploit the vulnerability.

## org.springframework:spring-beans:5.3.6

```
+--- com.atlassian.jira:jira-rest-java-client-core:5.2.4
     +--- org.springframework:spring-beans:5.3.6
          \--- org.springframework:spring-core:5.3.6
```
It has 1 critical vulnerability: [CVE-2022-22965](https://avd.aquasec.com/nvd/cve-2022-22965)
It does not affect the application because to exploit that vulnerability the application must be using a Spring MVC or Spring WebFlux
and be running in Tomcat as a WAR deployment.

## org.springframework:spring-core:5.3.6

```
+--- com.atlassian.jira:jira-rest-java-client-core:5.2.4
     +--- org.springframework:spring-beans:5.3.6
          \--- org.springframework:spring-core:5.3.6
```

It has 1 high vulnerability: [CVE-2021-22118](https://avd.aquasec.com/nvd/cve-2021-22118).
It does not affect the application because to exploit the vulnerability the application must be using Spring WebFlux.