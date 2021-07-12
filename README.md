# Zephyr data service (0.0.1)

Zephyr data service synchronizes the test in th2 with Zephyr test.
It searches for events that match format in the configuration and updates (or create new) executions.


## Event tree

When the data service finds the event it tries to extract information about folder, version and cycle.
It checks the event tree. It should have the following format

```
Root event with name `version|CycleName|Any other information you want`
|- Sub event with folder name. _NOTE: if there are several sub events the closest one to the issue event will be taken_
   |- TEST-1253  // the issue event. Its name must match the format in the configuration
```

## Configuration

There is an example of full configuration for the data service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: zephyr-service
spec:
  image-name: <image name>
  image-version: <image version>
  type: th2-act
  pins:
    - name: server
      connection-type: grpc
    - name: to_data_provider
      connection-type: grpc
  custom-config:
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
    httpLogging:
      level: INFO
  extended-settings:
    service:
      enabled: true
      type: NodePort
      endpoints:
        - name: 'grpc'
          targetPort: 8080
          nodePort: <free port>
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

#### connection

Contains information about the endpoint ot connect

+ baseUrl - url to the Jira instance
+ jira - block contains credentials to connect to Jira
    + username - the Jira username
    + key - the Jira password or API key for authentication

#### dataService

Contains information about name and version for current data service. It is used to mark the events that already processed by this service

+ name - the data service name
+ versionMarker - the data service version

#### syncParameters

Contains parameters for synchronization with Zephyr

+ issueFormat - the regular expression to match the event that corresponds to the issue
+ delimiter - the delimiter to use to extract version and cycle from the root event
+ statusMapping - mapping between event status and status in Zephyr. **NOTE: mapping for SUCCESS and FAILED event statuses is required**
+ jobAwaitTimeout - the timeout to await the job for adding test to a cycle/folder

#### httpLogging

Contains parameters to set up the Logging for inner HTTP clients that are used to connect to the Jira and Zephyr

+ level - level logging for HTTP client. Available levels: **ALL**, **HEADERS**, **BODY**, **INFO**, **NONE**

## Links example

The **data service zephyr** requires the link to the **data provider** working in gRPC mode. Link example:

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Link
metadata:
  name: zephyr-service-links
spec:
  boxes-relation:
    router-grpc:
    - name: data-service-zephyr-to-data-provider
      from:
        strategy: filter
        box: zephyr-service
        pin: to_data_provider
      to:
        service-class: com.exactpro.th2.dataprovider.grpc.DataProviderService
        strategy: robin
        box: data-provider
        pin: server
```

# Useful links

+ th2-common - https://github.com/th2-net/th2-common-j