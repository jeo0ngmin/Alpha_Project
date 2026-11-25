package com.medimate.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalNetworkPermissionTest {

    @Test
    public void privateAddressesNeedPermission() {
        assertTrue(LocalNetworkPermission.isPrivateHost("http://10.0.2.2:8080/"));
        assertTrue(LocalNetworkPermission.isPrivateHost("http://192.168.0.5:8080/"));
        assertTrue(LocalNetworkPermission.isPrivateHost("http://172.30.1.76:8080/"));
        assertTrue(LocalNetworkPermission.isPrivateHost("http://localhost:8080/"));
    }

    @Test
    public void publicAddressesDoNot() {
        assertFalse(LocalNetworkPermission.isPrivateHost("https://api.medimate.kr/v1/"));
        assertFalse(LocalNetworkPermission.isPrivateHost("http://172.32.0.1/"));
        assertFalse(LocalNetworkPermission.isPrivateHost("not a url"));
    }
}
