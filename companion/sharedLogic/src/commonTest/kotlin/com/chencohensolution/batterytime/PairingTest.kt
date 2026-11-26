package com.chencohensolution.batterytime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingTest {
    @Test
    fun readsTheMacsQrLink() {
        val p = Pairing.fromLink("batterytime://pair?h=192.168.1.20&p=47070&c=ABCD2345&n=Hen%E2%80%99s%20MacBook%20Pro")
        assertEquals(Pairing("192.168.1.20", 47070, "ABCD2345", "Hen’s MacBook Pro"), p)
        assertEquals("http://192.168.1.20:47070", p!!.baseUrl)
        assertEquals("ABCD-2345", p.displayCode)
    }

    @Test
    fun keepsPlusInTheName() {
        assertEquals("Mac+Pro", Pairing.fromLink("batterytime://pair?h=10.0.0.2&c=ABCD2345&n=Mac+Pro")?.name)
    }

    @Test
    fun rejectsOtherLinksAndBadCodes() {
        assertNull(Pairing.fromLink("https://example.com/?h=1.2.3.4&c=ABCD2345"))
        assertNull(Pairing.fromLink("batterytime://pair?h=1.2.3.4&c=ABC"))
        assertNull(Pairing.fromLink("batterytime://pair?c=ABCD2345"))
        // 0, O, 1 and I aren't in the Mac's alphabet
        assertNull(Pairing.normalizeCode("ABCD0123"))
    }

    @Test
    fun readsATypedAddressAndCode() {
        assertEquals(Pairing("192.168.1.20", 47070, "ABCDEFGH"), Pairing.fromTyped(" 192.168.1.20 ", "abcd-efgh"))
        assertEquals(Pairing("mac.local", 5000, "ABCDEFGH"), Pairing.fromTyped("mac.local:5000", "ABCD EFGH"))
        assertEquals("http://[fe80::1]:47070", Pairing.fromTyped("fe80::1", "ABCDEFGH")?.baseUrl)
        assertNull(Pairing.fromTyped("", "ABCDEFGH"))
        assertNull(Pairing.fromTyped("1.2.3.4:99999", "ABCDEFGH"))
    }
}
