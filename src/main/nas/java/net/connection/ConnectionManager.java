package nas.java.net.connection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import gov.nasa.jpf.JPF;
import gov.nasa.jpf.ListenerAdapter;
import gov.nasa.jpf.search.Search;
import gov.nasa.jpf.util.ArrayByteQueue;
import gov.nasa.jpf.util.StateExtensionClient;
import gov.nasa.jpf.util.StateExtensionListener;
import gov.nasa.jpf.vm.ApplicationContext;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.MJIEnv;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;
import nas.java.net.connection.ConnectionManager.Connection;

/**
 * It captures the current states of all connections made along this execution paths
 * 
 * @author Nastaran Shafiei
 */
public class ConnectionManager implements StateExtensionClient<List<Connection>> {

  // the list of all connections established along "this" execution path
  public List<Connection> curr;

  ConnectionManager() {
    this.curr = new ArrayList<Connection>();
  }

  public static class Connection implements Cloneable {
    
    public enum State {
      PENDING,
      ESTABLISHED,
      CLOSED,
      TERMINATED
    };

    String serverHost;
    State state;
    int port;
    
    // ServerSocket Object
    int serverPassiveSocket;
    int serverEndSocket;
    ApplicationContext serverApp;
    
    int clientEndSocket;
    ApplicationContext clientApp;

    // communication buffers
    ArrayByteQueue server2client; // server out and client in
    ArrayByteQueue client2server; // client out and server in
    
    public Connection(int port) {
      this.port = port;
      this.state = State.PENDING;
      
      this.serverPassiveSocket = MJIEnv.NULL;
      this.serverEndSocket = MJIEnv.NULL;
      this.clientEndSocket = MJIEnv.NULL;
      
      server2client = new ArrayByteQueue();
      client2server = new ArrayByteQueue();
    }
    
    public int getPort() {
      return this.port;
    }

    public int getServerPassiveSocket() {
      return this.serverPassiveSocket;
    }
    
    public int getServerEndSocket() {
      return this.serverEndSocket;
    }

    public int getClientEndSocket() {
      return this.clientEndSocket;
    }
    
    public Object clone() {
      Connection clone = null;

      try {
        clone = (Connection)super.clone();
        clone.client2server = (ArrayByteQueue)this.client2server.clone();
        clone.server2client = (ArrayByteQueue)this.server2client.clone();
      } catch (CloneNotSupportedException e) {
        e.printStackTrace();
      }

      return clone;
    }

    private void setServerInfo(int serverPassiveSocket, int serverEndSocket, ApplicationContext serverApp) {
      this.serverPassiveSocket = serverPassiveSocket;
      this.serverEndSocket = serverEndSocket;
      
      this.serverApp = serverApp;
      this.serverHost = serverApp.getHost();

      if(this.hasClient()) {
        this.establish();
      }
    }
    
    private void setClientInfo(int client, ApplicationContext clientApp, String serverHost) {
      this.clientEndSocket = client;
      this.clientApp = clientApp;
      this.serverHost = serverHost;

      if(this.hasServer()) {
        this.establish();
      }
    }
    
    private void setServerEndSocket(int serverEndSocket) {
      this.serverEndSocket = serverEndSocket;
    }
    
    public void establishedConnWithServer(int serverPassiverSocket, int serverEndSocket, ApplicationContext serverApp) {
      if(this.hasClient()) {
        this.setServerInfo(serverPassiverSocket, serverEndSocket, serverApp);
      } else {
        throw new ConnectionException();
      }
    }
    
    public void establishedConnWithClient(int clientEndSocket, ApplicationContext clientApp, String host, int serverEndSocket) {
      if(this.hasServer()) {
        this.setClientInfo(clientEndSocket, clientApp, host);
        this.setServerEndSocket(serverEndSocket);
      } else {
        throw new ConnectionException();
      }
    }
    
    public String getServerHost() {
      return this.serverHost;
    }

    public String getClientHost() {
      return this.clientApp.getHost();
    }

    public boolean hasServer() {
      return (this.serverPassiveSocket!=MJIEnv.NULL);
    }

    public boolean hasClient() {
      return (this.clientEndSocket!=MJIEnv.NULL);
    }
    
    public boolean isClientEndSocket(int socket) {
      if(this.clientEndSocket == socket) {
        return true;
      } else if(this.serverEndSocket == socket){
        return false;
      } else {
        throw new ConnectionException("the socket does not belong to this connection!");
      }
    }

    // check if the given socket object is an end-point of this connection
    public boolean isConnectionEndpoint(int socket) {
      return (this.clientEndSocket==socket || this.serverEndSocket==socket);
    }

    private void establish() {
      this.state = State.ESTABLISHED;
    }

    public boolean isEstablished() {
      return(this.state==State.ESTABLISHED);
    }

    private void close() {
      this.state = State.CLOSED;
    }

    public boolean isClosed() {
      return(this.state==State.CLOSED);
    }
    
    private void terminate() {
      this.state = State.TERMINATED;
    }

    public boolean isTerminated() {
      return(this.state==State.TERMINATED);
    }
    
    public boolean isPending() {
      return(!isTerminated() && !(this.hasServer() && this.hasClient()));
    }
    
    public String toString() {
      String result = "\nserverPassiveSocket: " + this.serverPassiveSocket + " serverEnd:" + this.serverEndSocket +" (host:" + this.serverHost +")" + " <---port:" + 
        this.port + "--->" + " clientEnd:" + this.clientEndSocket + " ["+this.state+"]\n";
      result += "clinet>=>server buffer: " + client2server + "\n";
      result += "server>=>client buffer: " + server2client + "\n";
      return result;
    }
    
    public int serverRead() {
      // server reading ...
      return client2server.poll().byteValue();
    }
    
    public int clientRead() {
      // client reading ...
      return server2client.poll().byteValue();
    }
    
    public void serverWrite(byte value) {
      // server writing ...
      server2client.add(value);
    }
    
    public void clientWrite(byte value) {
      // client writing ...
      client2server.add(value);
    }
    
    public boolean isServer2ClientBufferEmpty() {
      return server2client.isEmpty();
    }
    
    public boolean isClient2ServerBufferEmpty() {
      return client2server.isEmpty();
    }
    
    public int server2ClientBufferSize() {
      return server2client.size();
    }
    
    public int client2ServerBufferSize() {
      return client2server.size();
    }
  }
  
  
  /*------ connections management ------*/
  
  static ConnectionManager connections;

  static {
    connections = new ConnectionManager();
    connections.registerListener(VM.getVM().getJPF());
  }

  public static ConnectionManager getConnections() {
    return connections;
  }
  
  public Connection getServerPendingConn(int port, String serverHost) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.hasServer() && conn.isPending()) {
        if(conn.getPort()==port && conn.getServerHost().equals(serverHost)) {
          return conn;
        }
      }
    }
 
    return null;
  }
  
  public Connection getServerConn(int port, String serverHost) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.hasServer()) {
        if(conn.getPort()==port && conn.getServerHost().equals(serverHost)) {
          return conn;
        }
      }
    }
 
    return null;
  }
  
  public boolean hasServerConn(int port, String serverHost) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.hasServer()) {
        if(conn.getPort()==port && conn.getServerHost().equals(serverHost)) {
          return true;
        }
      }
    }
 
    return false;
  }
  
  public Connection getClientConn(int port, String serverHost) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.hasClient()) {
        if(conn.getPort()==port && conn.getServerHost().equals(serverHost)) {
          return conn;
        }
      }
    }
 
    return null;
  }

  public Connection getConnection(int endpoint) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.hasClient()) {
        if(conn.getClientEndSocket()==endpoint || conn.getServerEndSocket()==endpoint) {
          return conn;
        }
      }
    }
 
    return null;
  }
  
  public void addNewPendingServerConn(int serverPassiveSocket, int port, String serverHost) {
    VM vm = VM.getVM();
    Connection conn = new Connection(port);
    // the server connection is pending, that is, we don't have serverEndSocket yet and 
    // for now is set to null
    conn.setServerInfo(serverPassiveSocket, MJIEnv.NULL, vm.getApplicationContext(serverPassiveSocket));
    this.curr.add(conn);
  }

  public void addNewPendingClientConn(int client, int port, String serverHost) {
    VM vm = VM.getVM();
    Connection conn = new Connection(port);
    conn.setClientInfo(client, vm.getApplicationContext(client), serverHost);
    this.curr.add(conn);
  }

  public void closeConnection(int endpoint) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.isConnectionEndpoint(endpoint)) {
        conn.close();
        return;
      }
    }
    // there was not a connection with the given endpoint
    throw new ConnectionException("Could not find the connection to close");
  }
  
  public void terminateConnection(int endpoint) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();

      if(conn.isConnectionEndpoint(endpoint)) {
        conn.terminate();
        return;
      }
      
      
    }
  }
  
  // check if there exists a server with the given host and port 
  // TODO: maybe the server itself is in the list
  public boolean isAddressInUse(String host, int port) {
    Iterator<Connection> itr = curr.iterator();
    while(itr.hasNext()) {
      Connection conn = itr.next();
      if(conn.getServerHost().equals("host") && conn.getPort()==port) {
        return true;
      }
    }
    return false;
  } 
  
  
  /*------ state extension management ------*/
  
  @Override
  public List<Connection> getStateExtension () {
    return cloneConnections(this.curr);
  }

  @Override
  public void restore (List<Connection> stateExtension) {
    curr = cloneConnections(stateExtension);
  }

  @Override
  public void registerListener (JPF jpf) {
    StateExtensionListener<List<Connection>> sel = new StateExtensionListener<List<Connection>>(this);
    jpf.addSearchListener(sel);
    
    ConnectionTerminationListener ctl = new ConnectionTerminationListener();
    jpf.addListener(ctl);
  }
  
  // return a deep copy of the connections - a new clone is needed every time
  // JPF advances or backtracks
  public List<Connection> cloneConnections(List<Connection> list) {
    List<Connection> cloneList = new ArrayList<Connection>();
    Iterator<Connection> itr = list.iterator();
    
    while(itr.hasNext()) {
      Connection clone = (Connection)itr.next().clone();
      cloneList.add(clone);
    }
    
    return cloneList;
  }
  
  public class ConnectionTerminationListener extends ListenerAdapter {

    @Override
    public void objectReleased(VM vm, ThreadInfo currentThread, ElementInfo releasedObject) {
      if(releasedObject.instanceOf("Ljava.net.Socket;")) {
        //int objRef = releasedObject.getObjectRef();
        //terminateConnection(objRef);        
      }
    }
  }
}
