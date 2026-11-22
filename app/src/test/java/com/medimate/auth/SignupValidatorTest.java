package com.medimate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SignupValidatorTest {

    @Test
    public void acceptsValidInput() {
        assertNull(SignupValidator.check(" user@example.com ", "abcd1234", "abcd1234", true));
    }

    @Test
    public void reportsFirstProblemInOrder() {
        assertEquals(SignupValidator.MSG_EMAIL_EMPTY, SignupValidator.check("  ", "abcd1234", "abcd1234", true));
        assertEquals(SignupValidator.MSG_EMAIL_FORMAT, SignupValidator.check("user@", "abcd1234", "abcd1234", true));
        assertEquals(SignupValidator.MSG_EMAIL_FORMAT, SignupValidator.check("user example.com", "abcd1234", "abcd1234", true));
        assertEquals(SignupValidator.MSG_PASSWORD_SHORT, SignupValidator.check("a@b.co", "abc123", "abc123", true));
        assertEquals(SignupValidator.MSG_PASSWORD_MIX, SignupValidator.check("a@b.co", "abcdefgh", "abcdefgh", true));
        assertEquals(SignupValidator.MSG_PASSWORD_MIX, SignupValidator.check("a@b.co", "12345678", "12345678", true));
        assertEquals(SignupValidator.MSG_PASSWORD_MISMATCH, SignupValidator.check("a@b.co", "abcd1234", "abcd12345", true));
        assertEquals(SignupValidator.MSG_TERMS, SignupValidator.check("a@b.co", "abcd1234", "abcd1234", false));
    }

    @Test
    public void phoneDisplayFormat() {
        assertEquals("010-1234-5678", PhoneAuthHelper.toDisplay("+821012345678"));
        assertEquals("011-123-4567", PhoneAuthHelper.toDisplay("+82111234567"));
        assertEquals("+12025550123", PhoneAuthHelper.toDisplay("+12025550123"));
        assertNull(PhoneAuthHelper.toDisplay(null));
    }
}
