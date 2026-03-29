package com.phoenixfire.installer;

public class FirestickDevice {
    private String ipAddress;
    private String name;
    private int port;
    private boolean connected;

    public FirestickDevice(String ipAddress, String name, int port) {
        this.ipAddress = ipAddress;
        this.name = name;
        this.port = port;
        this.connected = false;
    }

    public String getIpAddress() { return ipAddress; }
    public String getName() { return name; }
    public int getPort() { return port; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public String getAdbAddress() { return ipAddress + ":" + port; }

    @Override
    public String toString() { return name + " (" + ipAddress + ")"; }
}
