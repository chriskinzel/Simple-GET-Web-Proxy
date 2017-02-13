/**
 * WebProxy Class
 * 
 * @author      Chris Kinzel 10160447
 * @version     1.0, 20 Jan 2017
 *
 */

import java.net.*;
import java.io.*;


public class WebProxy {
    ServerSocket serverSocket; // Listens for incoming clients
        

     /**
     *  Constructor that initalizes the server listenig port
     *
     * @param port      Proxy server listening port
     */

	public WebProxy(int port) throws IOException {
        serverSocket = new ServerSocket(port);
	}

    /**
     * Runs an infinite loop that serves incoming client requests
     */
    public void start() throws IOException {
        while(true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected " + clientSocket.getRemoteSocketAddress().toString() + "...\n");
            
            InputStream clientStream = clientSocket.getInputStream();
            
            // Read HTTP header from client
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            boolean badInput = readHTTPHeaderFromStream(new PushbackInputStream(clientStream, 4096), headerBytes);
            
            // Something went wrong, send 400 Bad Request, close the connection
            // and wait for a new client
            if(badInput) {
                sendBadRequestResponse(clientSocket);
                continue;
            }
            
            String header = headerBytes.toString();
            System.out.println("[Begin Header]\n" + header + "[End Header]\n");
            
            // Check if method is GET
            String[] lines = header.split("\\r\\n");
            if(lines.length < 2) {
                // Bad request
                System.out.println("Malformed request");
                sendBadRequestResponse(clientSocket);
                continue;
            }
            
            String[] components = lines[0].split(" ");
            if(components.length < 3 || !components[0].equals("GET")) {
                System.out.println("Request was not GET");
                sendBadRequestResponse(clientSocket);
                continue;
            }
            
            // Extract hostname/pathname
            String path = components[1].substring(7);
            
            // Check if resource exists in local cache
            File requestedResource = new File(path);
            if(requestedResource.exists()) {
                // Resource is in local cache send to client
                String responseHeader = generateResponseHeaderForResource(requestedResource);
                
                System.out.println("Resource was in local cache replying with...");
                System.out.println("[Begin Header]\n" + responseHeader + "[End Header] (Data not shown)");
                
                // Read in file from local cache
                byte[] resourceBytes = new byte[(int)requestedResource.length()];
                BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(requestedResource));
                fileInputStream.read(resourceBytes, 0, resourceBytes.length);
                
                // Send header and resource to client
                OutputStream outputStream = clientSocket.getOutputStream();
                outputStream.write(responseHeader.getBytes("US-ASCII"));
                outputStream.write(resourceBytes, 0, resourceBytes.length);
                outputStream.flush();
                
                // Finally close the connection
                System.out.println("[NOTICE] Closing connection to client.\n");
                clientSocket.close();
            } else {
                // Resource is not in local cache download it from origin
                String host = (path.indexOf("/") == -1) ? path : path.substring(0, path.indexOf("/"));
                for(String line : lines) {
                    if(line.startsWith("Host: ")) {
                        // Extract value
                        host = line.substring(6);
                        
                        // Host line may contain a port number
                        // we need to remove that
                        if(host.lastIndexOf(":") != -1) {
                            host = host.substring(0, host.lastIndexOf(":"));
                        }
                        
                        break;
                    }
                }
                
                // Connect to host
                System.out.println("Resource was not in local cache. Contacting origin server...");
                
                Socket hostSocket;
                try {
                    hostSocket = new Socket(host, 80);
                } catch(UnknownHostException e) {
                    System.out.println("[ERROR] Could not contact origin server.\n");
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                
                // Forward client request
                OutputStream hostOutputStream = hostSocket.getOutputStream();
                hostOutputStream.write(headerBytes.toByteArray());
                hostOutputStream.flush();
                
                // Get reply
                PushbackInputStream hostInputStream = new PushbackInputStream(hostSocket.getInputStream(), 4096);
                ByteArrayOutputStream originHeaderBytes = new ByteArrayOutputStream();
                
                boolean badReply = readHTTPHeaderFromStream(hostInputStream, originHeaderBytes);
                
                // Something went wrong, send 400 Bad Request, close the connection
                // and wait for a new client
                if(badReply) {
                    hostSocket.close();
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                String originHeader = originHeaderBytes.toString();
                
                // Make sure response is 200 OK
                lines = originHeader.split("\\r\\n");
                if(lines.length < 2) {
                    // Bad reply
                    System.out.println("Malformed response");
                    hostSocket.close();
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                // Print header
                System.out.println("\nReply from origin:\n[Begin Header]");
                for(String line : lines) {
                    System.out.println(line);
                    
                    if(line.equals("")) {
                        break;
                    }
                }
                System.out.println("[End Header] (Data not shown)\n");
                
                
                components = lines[0].split(" ");
                if(components.length < 3 || !components[1].equals("200")) {
                    System.out.println("Response was not 200 OK");
                    hostSocket.close();
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                // Read data from origin server
                int contentLength = -1;
                for(String line : lines) {
                    if(line.startsWith("Content-Length: ")) {
                        contentLength = Integer.parseInt(line.substring(16));
                        break;
                    }
                }
                
                // Unknown amount of data to read, error
                if(contentLength == -1) {
                    System.out.println("Server response error");
                    hostSocket.close();
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                // Transfer data from origin socket to file and
                // client socket
                if(path.lastIndexOf("/") != -1) {
                    File directoryStructure = new File(path.substring(0, path.lastIndexOf("/")));
                    directoryStructure.mkdirs();
                }
                
                requestedResource.createNewFile();
                
                FileOutputStream fileOutputStream = new FileOutputStream(requestedResource);
                OutputStream clientOutputStream = clientSocket.getOutputStream();
                
                System.out.println("Caching requested resource and sending to client...\n");
                
                // Send response header
                String responseHeader = generateResponseHeaderWithContentLength(contentLength);
                clientOutputStream.write(responseHeader.getBytes("US-ASCII"));
                clientOutputStream.flush();
                
                System.out.println("[Begin Header]\n" + responseHeader + "[End Header] (Data not shown)\n");
                
                byte[] buf = new byte[4096];
                do {
                    int bytesRead;
                    try {
                        bytesRead = hostInputStream.read(buf);
                    } catch(IOException e) {
                        System.out.println("[ERROR] An IOException occured: " + e.getMessage());
                        break;
                    }
                    
                    // End of stream
                    if(bytesRead < 0) {
                        System.out.println("[ERROR] Unexpected end of stream.");
                        break;
                    }
                    
                    // Send data to client
                    clientOutputStream.write(buf, 0, bytesRead);
                    clientOutputStream.flush();
                    
                    // Write data to file
                    fileOutputStream.write(buf, 0, bytesRead);
                    
                    contentLength -= bytesRead;
                } while(contentLength > 0);
                
                // Check if an error occured
                if(contentLength != 0) {
                    fileOutputStream.close();
                    hostSocket.close();
                    
                    requestedResource.delete();
                    
                    sendBadRequestResponse(clientSocket);
                    
                    continue;
                }
                
                System.out.println("[NOTICE] Download successful.");
                System.out.println("[NOTICE] Closing connection to client.\n");
                
                fileOutputStream.flush();
                fileOutputStream.close();
                
                hostSocket.close();
                clientSocket.close();
            }
        }
    }
    
    
    /**
     * Blocks until a full HTTP header has been read from the given stream. The HTTP
     * header data will be contained in the ByteArrayOutputStream object. Terminated
     * with \r\n (note excess bytes may be present in the ByteArrayOutputStream but
     * will be pushed back onto the stream).
     *
     * @param stream        the PushbackInputStream to read from
     * @param headerBytes   the ByteArrayOutputStream object to put the HTTP header data into must be initialized
     *
     * @return              a boolean indicating whether or not an error occured (true for error, false for success)
     * 
     * @throws              UnsupportedEncodingException if the received header bytes were not US ASCII
     */
    private boolean readHTTPHeaderFromStream(PushbackInputStream stream, ByteArrayOutputStream headerBytes) throws UnsupportedEncodingException, IOException {
        byte[] buf = new byte[4096];
        
        int bytesRead;
        do {
            try {
                bytesRead = stream.read(buf);
            } catch(IOException e) {
                System.out.println("[ERROR] An IOException occured: " + e.getMessage());
                return true;
            }
            
            // End of stream
            if(bytesRead < 0) {
                System.out.println("[ERROR] Unexpected end of stream.");
                return true;
            }
            
            headerBytes.write(buf, 0, bytesRead);
        } while(!headerBytes.toString("US-ASCII").contains("\r\n\r\n"));
        
        // Pushback excess bytes not part of header
        String header = headerBytes.toString("US-ASCII");
        int excess = header.length() - (header.indexOf("\r\n\r\n") + 4);
        
        stream.unread(buf, bytesRead-excess, excess);
        
        return false;
    }
    
    
    /**
     * Creates and returns a valid HTTP 1.0 200 OK response header for a given resource.
     * The content length of the resource is included in the header.
     *
     * @param resource      the resource to be sent in the data section
     *
     * @return              the response header as a string
     */
    private String generateResponseHeaderForResource(File resource) {
        return "HTTP/1.0 200 OK\r\nConnection: close\r\nContent-Length: " + resource.length() + "\r\n\r\n";
    }
    
    /**
     * Creates and returns a valid HTTP 1.0 200 OK response header for a given content length.
     *
     * @param contentLength      the contentLength to be used in the header
     *
     * @return              the response header as a string
     */
    private String generateResponseHeaderWithContentLength(int contentLength) {
        return "HTTP/1.0 200 OK\r\nConnection: close\r\nContent-Length: " + contentLength + "\r\n\r\n";
    }
    
    
    /**
     * Creates and sends a valid HTTP 1.0 400 Bad Request response header.
     * The socket is closed after the response is sent.
     *
     * @param socket        the socket to send the 400 Bad Request reply to
     *
     */
    private void sendBadRequestResponse(Socket socket) {
        // Create header
        String header = "HTTP/1.0 400 Bad Request\r\nConnection: close\r\n";
        
        // Send header to client
        try {
            System.out.println("Replying with...");
            System.out.println("[Begin Header]\n" + header + "[End Header]\n");
            
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(header.getBytes("US-ASCII"));
            outputStream.flush();
        } catch(Exception e) {
            System.out.println("[ERROR] An exception occured while trying to reply to the client: " + e.getMessage());
        }
        
        System.out.println("[NOTICE] Closing connection to client.\n");
        
        
        // Close connection
        try {
            socket.close();
        } catch(IOException e) {
            
        }
    }
    

    /**
     * A simple test driver
     */
	public static void main(String[] args) {

                String server = "localhost"; // webproxy and client runs in the same machine
                int server_port = 0;
		try {
                // check for command line arguments
                	if (args.length == 1) {
                        	server_port = Integer.parseInt(args[0]);
                	}
                	else {
                        	System.out.println("wrong number of arguments, try again.");
                        	System.out.println("usage: java WebProxy port");
                        	System.exit(0);
                	}


                	WebProxy proxy = new WebProxy(server_port);

                	System.out.printf("Proxy server started...\n\n");
                	proxy.start();
        	} catch (Exception e)
		{
			System.out.println("Exception in main: " + e.getMessage());
                        e.printStackTrace();
	
		}
		
	}
}
