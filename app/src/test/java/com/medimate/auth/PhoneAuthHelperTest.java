package com.medimate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PhoneAuthHelperTest {

    @Test
    public void convertsKoreanMobileToE164() {
        assertEquals("+821012345678", PhoneAuthHelper.toE164("010-1234-5678"));
        assertEquals("+821012345678", PhoneAuthHelper.toE164("01012345678"));
        assertEquals("+821012345678", PhoneAuthHelper.toE164("010 1234 5678"));
        assertEquals("+821012345678", PhoneAuthHelper.toE164("+82 10-1234-5678"));
    }

    @Test
    public void rejectsInvalidNumbers() {
        assertNull(PhoneAuthHelper.toE164(null));
        assertNull(PhoneAuthHelper.toE164(""));
        assertNull(PhoneAuthHelper.toE164("02-123-4567"));
        assertNull(PhoneAuthHelper.toE164("0101234"));
    }
}
