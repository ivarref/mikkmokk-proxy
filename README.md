# mikkmokk-proxy

mikkmokk-proxy is a reverse proxy server that injects faults on the HTTP layer.

You can use it to test how well your services handles
* failed requests
* duplicate requests
* delayed requests

## Usage

#### Start the reverse proxy

The following setup proxies to `http://example.com`:
```bash
docker run ... \
  -e DESTINATION_URL=http://example.com \
  -e PROXY_BIND=0.0.0.0 \
  -e PROXY_PORT=8080 \
  -e ADMIN_BIND=0.0.0.0 \
  -e ADMIN_PORT=7070 \
  -p 8080:8080 \
  -p 7070:7070
```

There are two ports being exposed:
* The reverse proxy on port 8080.
* The admin server on port 7070.

There are three ways mikkmokk can be instructed to inject faults:
* Dynamically per request using HTTP headers when accessing the reverse proxy.
* Set new defaults during runtime using the admin server.
* Using environment variables when starting the proxy.

#### Issue a regular request

```
$ curl http://localhost:8080
...
    <h1>Example Domain</h1>
    <p>This domain is for use in illustrative examples in documents. You may use this
    domain in literature without prior coordination or asking for permission.</p>
    <p><a href="https://www.iana.org/domains/example">More information...</a></p>
...
```
This request succeeded because mikkmokk was not told to do any fault injection.

#### Insert a failure before a request reaches the destination

The header `x-mikkmokk-fail-before-percentage` can be used to simulate that
the destination could not be reached. If present, it must be an int in the range
\[0, 100\], i.e. it's the percentage chance that a request fails.
The default value for this header is `0`. 
The value `100` means that the request will always fail.
This can be used to test if clients are retrying or not.

```
$ curl -v -H 'x-mikkmokk-fail-before-percentage: 100' http://localhost:8080
...
< HTTP/1.1 503 Service Unavailable
< Content-Type: application/json
...
{"error":"fail-before"}
```

The default HTTP status code for this is `503`, and may be changed
using the header `x-mikkmokk-fail-before-code`. 

#### Insert a failure after a request has been processed by the destination

The header `x-mikkmokk-fail-after-percentage` can be used to simulate that the 
destination received and processed the request, but
that the network between the proxy and the destination failed before
the proxy received the response. Thus, the client will receive an incorrect response.
If the client retries, will the backend handle a duplicate request?

```
$ curl -v -H 'x-mikkmokk-fail-after-percentage: 100' http://localhost:8080
...
< HTTP/1.1 502 Bad Gateway
< Content-Type: application/json
...
{"error":"fail-after","destination-response-code":"200"}
```

The field `destination-response-code` states which HTTP status code the destination
actually responded with.
The default HTTP status code for this is `502`, and may be changed using the header `x-mikkmokk-fail-after-code`.

#### Insert a duplicate request

The header `x-mikkmokk-duplicate-percentage` instructs mikkmokk to make two identical, parallel requests.

```
$ curl -H 'x-mikkmokk-duplicate-percentage: 100' http://localhost:8080

# In the mikkmokk logs you will see something like:
> Duplicate request returned identical HTTP status code 200 for 200 for GET /
```

#### Only match a specific URI and/or request method

```
$ curl -H 'x-mikkmokk-match-uri: /something' \
       -H 'x-mikkmokk-match-method: GET' \
       -H 'x-mikkmokk-fail-before-percentage: 100' \
       http://localhost:8080/something
{"error":"fail-before"}
```

The default value of the `x-mikkmokk-match-uri` and `x-mikkmokk-match-method` headers is `*`, meaning that all URIs and all request methods will match.

#### Inserting delays

Delays may be inserted using `x-mikkmokk-delay-before-percentage` and
`x-mikkmokk-delay-before-ms`:

```
$ time curl -H 'x-mikkmokk-delay-before-percentage: 100' \
            -H 'x-mikkmokk-delay-before-ms: 3000' \
            http://localhost:8080
...
real    0m3.252s
```

This delay will be inserted before the destination service is accessed.

It's also possible to inject delays after the destination service has
been accessed using `x-mikkmokk-delay-after-percentage` and
`x-mikkmokk-delay-after-ms`.

### Use the admin API to set new defaults

```
$ curl -XPOST -H 'x-mikkmokk-fail-before-percentage: 20' http://localhost:7070/api/v1/update
{"fail-before-percentage":20}

# Using the hey load generator https://github.com/rakyll/hey, 
# we can test if 20% of requests fail:
$ hey -n 100 http://localhost:8080
...
Status code distribution:
  [200] 78 responses
  [503] 22 responses

# List current settings
$ curl http://localhost:7070/api/v1/list
{"fail-before-percentage":20}

# Reset the admin API settings
$ curl -XPOST http://localhost:7070/api/v1/reset
{}

$ hey -n 100 http://localhost:8080
...
Status code distribution:
  [200] 100 responses
```

## Limitations
No TLS/SSL support for the proxy server.
No WebSocket support.

## Alternatives and related software

[envoyproxy](https://www.envoyproxy.io/) has a [fault injection filter](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/fault_filter#config-http-filters-fault-injection). That seems equivalent
to `x-mikkmokk-fail-before-` headers.

[toxiproxy](https://github.com/Shopify/toxiproxy): A chaotic TCP proxy.

[testcontainers-java](https://github.com/testcontainers/testcontainers-java): Has a separate module for toxiproxy.

## License

Copyright © 2022 Ivar Refsdal

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
