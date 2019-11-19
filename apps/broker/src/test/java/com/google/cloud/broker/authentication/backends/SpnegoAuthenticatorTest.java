// Copyright 2019 Google LLC
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.broker.authentication.backends;

import javax.security.auth.Subject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.Base64;

import org.ietf.jgss.*;
import static org.junit.Assert.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import com.google.cloud.broker.settings.AppSettings;
import com.google.cloud.broker.testing.FakeKDC;


public class SpnegoAuthenticatorTest {

    private static FakeKDC fakeKDC;
    private static final String REALM = "EXAMPLE.COM";
    private static final String BROKER_HOST = "testhost";
    private static final String BROKER_NAME = "broker";
    private static final String BROKER = BROKER_NAME + "/" + BROKER_HOST + "@" + REALM;
    private static final String ALICE = "alice@" + REALM;

    @BeforeClass
    public static void setUpClass() {
        fakeKDC = new FakeKDC(REALM);
        fakeKDC.start();
        fakeKDC.createPrincipal(ALICE);
        fakeKDC.createPrincipal(BROKER);
    }

    @Before
    public void setUp() {
        AbstractAuthenticationBackend.reset();
        AppSettings.reset();
        AppSettings.setProperty(AppSettings.AUTHENTICATION_BACKEND, "com.google.cloud.broker.authentication.backends.SpnegoAuthenticator");
    }

    @AfterClass
    public static void tearDownClass() {
        fakeKDC.stop();
    }

    private static byte[] generateToken() {
        String SPNEGO_OID = "1.3.6.1.5.5.2";
        String KRB5_MECHANISM_OID = "1.2.840.113554.1.2.2";
        String KRB5_PRINCIPAL_NAME_OID = "1.2.840.113554.1.2.2.1";

        byte[] token;
        try {
            // Create GSS context for the broker service and the logged-in user
            Oid krb5Mechanism = new Oid(KRB5_MECHANISM_OID);
            Oid krb5PrincipalNameType = new Oid(KRB5_PRINCIPAL_NAME_OID);
            Oid spnegoOid = new Oid(SPNEGO_OID);
            GSSManager manager = GSSManager.getInstance();
            GSSName gssServerName = manager.createName(BROKER, krb5PrincipalNameType, krb5Mechanism);
            GSSContext gssContext = manager.createContext(
                gssServerName, spnegoOid, null, GSSCredential.DEFAULT_LIFETIME);
            gssContext.requestMutualAuth(true);
            gssContext.requestCredDeleg(true);

            // Generate the SPNEGO token
            token = new byte[0];
            token = gssContext.initSecContext(token, 0, token.length);
            return token;
        } catch (GSSException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check that an exception is thrown if the provided KEYTABS_PATH doesn't contain any files.
     */
    @Test
    public void testEmptyKeytabPath() {
        Path emptyFolder;
        try {
            emptyFolder = Files.createTempDirectory("empty");
            AppSettings.setProperty(AppSettings.KEYTABS_PATH, emptyFolder.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
            auth.authenticateUser();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("No valid keytabs found in path `" + emptyFolder.toString() + "` as defined in the `KEYTABS_PATH` setting", e.getMessage());
        }
    }

    /**
     * Check that an exception is thrown if the KEYTABS_PATH doesn't exist.
     */
    @Test
    public void testInexistentKeytabPath() {
        AppSettings.setProperty(AppSettings.KEYTABS_PATH, "/home/does-not-exist");
        try {
            SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
            auth.authenticateUser();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Invalid path `/home/does-not-exist` as defined in the `KEYTABS_PATH` setting", e.getMessage());
        }
    }

    /**
     * Check that an exception is thrown if the KEYTABS_PATH doesn't contain any valid keytabs.
     */
    @Test
    public void testInvalidKeytab() {
        Path folder;
        try {
            folder = Files.createTempDirectory("folder");
            folder.resolve("fake.keytab").toFile().createNewFile();
            AppSettings.setProperty(AppSettings.KEYTABS_PATH, folder.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
            auth.authenticateUser();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("No valid keytabs found in path `" + folder.toString() + "` as defined in the `KEYTABS_PATH` setting", e.getMessage());
        }
    }

    @Test
    public void testHeaderDoesntStartWithNegotiate() {
        AppSettings.setProperty(AppSettings.KEYTABS_PATH, fakeKDC.getBrokerKeytabDir().toString());
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_NAME, BROKER_NAME);
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_HOSTNAME, BROKER_HOST);

        SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
        try {
            auth.authenticateUser("xxx");
            fail();
        } catch (StatusRuntimeException e) {
            assertEquals(Status.UNAUTHENTICATED.getCode(), e.getStatus().getCode());
            assertEquals("UNAUTHENTICATED: Use \"authorization: Negotiate <token>\" metadata to authenticate", e.getMessage());
        }
    }

    @Test
    public void testInvalidSpnegoToken() {
        AppSettings.setProperty(AppSettings.KEYTABS_PATH, fakeKDC.getBrokerKeytabDir().toString());
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_NAME, BROKER_NAME);
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_HOSTNAME, BROKER_HOST);

        SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
        try {
            auth.authenticateUser("Negotiate xxx");
            fail();
        } catch (StatusRuntimeException e) {
            assertEquals(Status.UNAUTHENTICATED.getCode(), e.getStatus().getCode());
            assertEquals("UNAUTHENTICATED: SPNEGO authentication failed", e.getMessage());
        }
    }

    /**
     * Check the happy path: User generates a SPNEGO token, then the broker decrypts it to authenticate the user.
     */
    @Test
    public void testSuccess() {
        AppSettings.setProperty(AppSettings.KEYTABS_PATH, fakeKDC.getBrokerKeytabDir().toString());
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_NAME, BROKER_NAME);
        AppSettings.setProperty(AppSettings.BROKER_SERVICE_HOSTNAME, BROKER_HOST);

        // Let Alice generate a token
        Subject alice = fakeKDC.login("alice");
        byte[] token = Subject.doAs(alice, (PrivilegedAction<byte[]>) () -> {
            return generateToken();
        });

        // Let the SpnegoAuthenticator decrypt the token and authenticate Alice
        String encodedToken = Base64.getEncoder().encodeToString(token);
        SpnegoAuthenticator auth = (SpnegoAuthenticator) AbstractAuthenticationBackend.getInstance();
        String authenticateUser =auth.authenticateUser("Negotiate " + encodedToken);
        assertEquals("alice@EXAMPLE.COM", authenticateUser);
    }
}
