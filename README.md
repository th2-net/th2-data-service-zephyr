# Zephyr data processor (1.1.0)

Zephyr data processor synchronizes the test in th2 with Zephyr Squad and Zephyr Scale.
It searches for events that match format in the configuration and updates test executions.


## Event tree

When the data processor finds the event it tries to extract information about folder, version and cycle.
It checks the event tree. It should have the following format

### Zephyr Squad

```
Root event with name `version|CycleName|Any other information you want`
|- Sub event with folder name. _NOTE: if there are several sub events the closest one to the issue event will be taken_
   |- TEST-1253  // the issue event. Its name must match the format in the configuration
```

### Zephyr Scale (Sever / Cloud)

```
Root event
|- Sub event(s)
  |- Event with cycle name and version `<CycleName>|<version>|<Any other information>
    |- TEST-T1253  // the test case event. Its name must match the format in the configuration
```

## Configuration

There is an example of full configuration (infra-2.0) for the data processor

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: zephyr-processor
spec:
  image-name: ghcr.io/th2-net/th2-data-processor-zephyr
  image-version: 0.3.0
  type: th2-act
  pins:
    grpc:
      client:
        - name: to_data_provider
          service-class: com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
          linkTo:
            - box: lw-data-provider
              pin: server
        - name: to_data_provider_stream
          service-class: com.exactpro.th2.dataprovider.lw.grpc.QueueDataProviderService
          linkTo:
            - box: lw-data-provider
              pin: server
    mq:
      subscribers:
        - name: events
          attributes:
            - event
            - in
      publishers:
        - name: state
          attributes:
            - store
  custom-config:
    stateSessionAlias: my-processor-state
    enableStoreState: false
    
    crawler:
      from: 2021-06-16T12:00:00.00Z
      to: 2021-06-17T14:00:00.00Z
      intervalLength: PT10M
      syncInterval: PT10M
      awaitTimeout: 10
      awaitUnit: SECONDS
      events:
        bookToScope:
          book1: []
          book2: []
    processorSettings:
      zephyrType: SQUAD
      connection:
        baseUrl: "https://your.jira.address.com"
        jira:
          username: "jira-user"
          key: "you password" # or api key
      dataService:
        name: "ZephyrService"
        versionMarker: "0.0.1"
      syncParameters:
        issueFormat: "QAP_\\d+"
        delimiter: '|'
        statusMapping:
          SUCCESS: PASS
          FAILED: WIP
        jobAwaitTimeout: 1000
        testExecutionMode: UPDATE_LAST # CREATE_NEW
        relatedIssuesStrategies:
          - type: linked
            trackLinkedIssues:
                - linkName: "is cloned by"
                  whitelist:
                      - projectKey: "P1"
                        issues:
                            - TEST-1
                            - TEST-2
                            - TEST-3
                      - projectName: "P2 Project"
                        issues:
                            - TEST-1
                            - TEST-2
                            - TEST-4
      httpLogging:
        level: INFO
  extended-settings:
    service:
      enabled: true
      type: ClusterIP
      endpoints:
        - name: 'grpc'
          targetPort: 8080
    envVariables:
      JAVA_TOOL_OPTIONS: '-XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport -XX:MaxRAMPercentage=85'
    resources:
      limits:
        memory: 250Mi
        cpu: 200m
      requests:
        memory: 100Mi
        cpu: 50m
```

### Parameters description

#### zephyrType

Determinate which type of synchronization to use. Possible values:
+ SQUAD
+ SCALE_SERVER
+ SCALE_CLOUD

The default value is `SQUAD`

#### connection

Contains information about the endpoint ot connect

+ baseUrl - url to the Jira instance
+ jira - block contains credentials to connect to Jira. There are several types of credentials:
  + basic - username and password
    + username - the Jira username
    + key - the Jira password or API key for authentication
    ```yaml
      jira:
        username: "jira-user"
        key: "you password" # or api key
    ```
  + bearer - generated token
    + token - the generated token to access jira
    ```yaml
      jira:
        type: bearer
        token: "some generated token"
    ```
+ zephyr - block contains credentials to connect to Zephyr. By default, the same credentials as for Jira are used.
+ zephyrUrl - url to the Zephyr API. By default, equal to _baseUrl_.

#### dataService

Contains information about name and version for current data service. It is used to mark the events that already processed by this service

+ name - the data service name
+ versionMarker - the data service version

#### syncParameters

Contains parameters for synchronization with Zephyr

+ **issueFormat** - the regular expression to match the event that corresponds to the issue
+ **delimiter** - the delimiter to use to extract version and cycle from the root event
+ **statusMapping** - mapping between event status and status in Zephyr. **NOTE: mapping for SUCCESS and FAILED event statuses is required**
+ **jobAwaitTimeout** - the timeout to await the job for adding test to a cycle/folder
+ **relatedIssuesStrategies** - configures the strategies to find the additional issues related to the currently processing one.
  They will be updated using the version, cycle and folder for the current issue.
+ **testExecutionMode** - defines how the test execution should be reported. By default, it tries to update an existing execution.
  You can change the behavior by using _CREATE_NEW_ value. The default value it _UPDATE_LAST_.
+ **versionPattern** - the regexp that will be used to match the version part for Zephyr Scale events structure [see structure here](#zephyr-scale-sever--cloud).
  **_Has no effect for Zephyr SQUAD_**. The default value is `(((\d+)|([a-zA-Z]+))\.?)+` (please do not forget to escape `\` by adding another one before `\\`)
+ **customFields** - the custom fields that should be added to the test case execution. It is a mapping between field name and how its value should be computed.
  **_Has no effect for Zephyr SQUAD_**.
  The value can be:
  + some constant value
    ```yaml
    customFields:
      "Const Field":
        type: const
        value: 42
    ```
  + extracted from event (ID, NAME)
    ```yaml
    customFields:
      "From Event Field":
        type: event
        extract: ID
    ```
  + extracted from related JIRA information (VERSION, ACCOUNT_ID, ACCOUNT_NAME)
    ```yaml
    customFields:
      "From Jira Field":
        type: jira
        extract: VERSION
    ```
+ **cachesConfiguration** - configuration that will be applied to the internal caches inside the zephyr processors (e.g. cycles cache)
  + **cycles** - configuration for cycle caching
    + **size** - cache size. Default value is 100.
    + **expireAfterSeconds** - element expiration time in seconds. Default value is 86400 (1 day).
    + **invalidateAt** - time in UTC (e.g. 00:00:00) when all values in cache should be invalidated. Repeats every day.
      By default, `null` meaning no scheduled invalidation is configured

##### Strategies (only for Zephyr Squad)

All strategies should be configured in the following way:
```yaml
relatedIssuesStrategies:
  - type: <strat type>
    strategySpecificParameters:
    #...
  - type: <another strat>
    strategySpecificParameters:
    #...
```

###### **linked**

Finds the issues that are linked to the current one. Uses configuration to decide which links should be taken into account.
Configuration example:
```yaml
relatedIssuesStrategies:
  - type: linked
    trackLinkedIssues:
        - linkName: "is cloned by"
          whitelist:
              - projectKey: P1
                issues:
                    - TEST-3
              - projectName: "P2 Project"
                issues:
                    - TEST-1
                    - TEST-2
        - linkName: "is similar to"
          direction: INWARD # or OUTWARD
          whitelist:
              - projectKey: P1
                issues:
                    - TEST-42
        - linkName: "is duplicated by"
          disable: true
          whitelist:
              - projectKey: P3
                issues:
                    - *
```

###### _Parameters_

+ **trackLinkedIssues** - the list of links to track
    + **linkName** - the link name to track. Can be found in the JIRA in _Issue Links_/_Linked Issues_ block. **Required parameter**
    + **direction** - the link direction. Can be used to clarify which link to use if the **linkName** for both directions is the same.
        Possible values are: **INWARD** - another issue is linked to us. **OUTWARD** - we are linked to another issue.
    + **disable** - you can disable the tracking of the specific link in the configuration
    + **whitelist** - the list contains information about which issues should be taken to follow and for which projects.
      _E.g. we have issue A-42 that is cloned by the issue B-42 (project B).
      If the issue A-42 is in the whitelist for project **B** this link will be followed_
      + **projectKey** - project identifier. It is inner key for JIRA
      + **projectName** - project name in JIRA. Can be used instead of **projectKey**.
        _NOTE: only one of the parameters **projectKey** or **projectName** can be used_
      + **issues** - the set of issues for with the link with specified type should be tracked if the linked issues belongs to the specified project.
        If you want to allow to track any issue which has links with specified type and linked issue belongs to the specified project
        you can use wildcard mark `*`

#### httpLogging

Contains parameters to set up the Logging for inner HTTP clients that are used to connect to the Jira and Zephyr

+ level - level logging for HTTP client. Available levels: **ALL**, **HEADERS**, **BODY**, **INFO**, **NONE**

# Changes

## v1.1.0

### Added

+ Parameters to configure when cycle cache for Zephyr Scale processor is invalidated. Please refer to [configuration block](#configuration).

### Updated

+ common: `5.14.0-dev`
+ common-utils: `2.2.3-dev`
+ processor-core: `0.3.0-dev`
+ grpc-lw-data-provider: `2.3.3-dev`

## v1.0.0

### Added

+ Support for Zephyr Scale Cloud. Separate **zephyrUrl** parameter was added to support different URL for Jira and Zephyr
  By default it has the same value as **baseUrl**.</br>
  **NOTE: the _testExecutionMode UPDATE_LAST_ is not support by Zephyr Scale Cloud**

### Changed

+ Dependencies update:
    + BOM (4.1.0 -> 4.4.0)
    + Kotlin (1.5.32 -> 1.6.21)
    + JIRA rest Java client (5.2.2 -> 5.2.6)
    + Ktor (1.6.5 -> 1.6.8)

## v0.3.0

+ migrated to processor-core

### Added

+ vulnerability check

### Updated

+ bom:4.1.0
+ common:5.1.0-dev-version
+ grpc-lw-data-provider:2.0.0-dev-version
+ processor-core:0.1.0-dev-version

## v0.1.0

### Added

+ Strategies for finding related issues
+ Create strategy for following issue links


# Useful links

+ th2-common - https://github.com/th2-net/th2-common-j