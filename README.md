# Simple GET Web Proxy
Simple non-persistent HTTP 1.0 GET request proxy in Java with caching. This program will accept incoming connections parse any GET requests and check if it is possible to serve the request using local cache. If the requested resource is in local cache then it will be sent to the client, otherwise, the request will be forwarded to the appropriate origin server, the resource will be downloaded to local cache and simultaneously be sent to the client. Any other requests or forms of communication will trigger a 400 Bad Request response. Only single file resources such as images, text files, PDF's, etc... can be accessed with this proxy.

# Compilation & Running
javac WebProxy.java  
  
java WebProxy 8888  
  
To run the proxy on port 8888, after launching the proxy redirect HTTP traffic via network settings or browser configuration to the port and address of the proxy.