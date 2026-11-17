package com.friday.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentRetrieverTest {
    private fun doc(name: String, content: String) = LocalDocument(
        name = name,
        location = "local://$name",
        mimeType = "application/pdf",
        sizeBytes = content.length.toLong(),
        content = content
    )

    @Test
    fun retrievesRelevantPageFromScannedPdfText() {
        val documents = listOf(
            doc("policy-scan.pdf", "[PAGE 1]\nWelcome to the policy.\n[PAGE 2]\nPre-existing condition coverage is excluded for the first waiting period.")
        )

        val result = LocalDocumentRetriever.retrieve(
            "What does the policy say about pre-existing condition coverage?",
            documents
        )

        assertTrue(result.isNotEmpty())
        assertEquals(2, result.first().page)
        assertTrue(result.first().text.contains("Pre-existing condition coverage"))
    }

    @Test
    fun doesNotReturnUnrelatedDocumentAsEvidence() {
        val documents = listOf(
            doc("holiday.pdf", "[PAGE 1]\nA travel itinerary with hotel check-in and sightseeing details.")
        )

        val result = LocalDocumentRetriever.retrieve(
            "What is the pre-existing condition coverage exclusion?",
            documents
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun keepsAadhaarAndPanEvidenceSeparate() {
        val documents = listOf(
            doc("identity.pdf", "[PAGE 1]\nPAN is ABCDE1234F.\n[PAGE 2]\nAadhaar number is 1234 5678 9012.")
        )

        val result = LocalDocumentRetriever.retrieve("Find the Aadhaar number", documents)

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.text.contains("Aadhaar", ignoreCase = true) })
        assertFalse(result.any { it.text.contains("PAN is", ignoreCase = true) })
    }

    @Test
    fun tokenizesTeluguText() {
        val documents = listOf(
            doc("telugu.pdf", "[PAGE 1]\nపేరు రవి కుమార్ అని పత్రంలో ఉంది.")
        )

        val result = LocalDocumentRetriever.retrieve("పేరు", documents)

        assertTrue(result.isNotEmpty())
        assertTrue(result.first().text.contains("పేరు"))
    }
}
