package dev.mahlernim.gasselfmeter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SamchullyClientTest {
    private fun httpClient(code: Int, body: String) = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code).message("synthetic")
            .body(okhttp3.ResponseBody.create(null, body)).build()
    }.build()

    @Test fun reportsHttpCodeAndLoginStageWithoutReturningResponseBody() {
        SamchullyReadClient(Providers.get("samchully"), Credentials("synthetic", "synthetic"), httpClient(401, "private body")).use { client ->
            val failure = assertThrows(ProviderFailure::class.java) { client.login() }
            assertEquals("login", failure.stage)
            assertEquals("authentication", failure.category)
            assertEquals(401, failure.httpCode)
            assertFalse(failure.message.orEmpty().contains("private body"))
        }
    }

    @Test fun reportsBillSchemaFailureForMalformedSuccessfulResponse() {
        SamchullyReadClient(Providers.get("samchully"), null, httpClient(200, "not-json")).use { client ->
            val failure = assertThrows(ProviderFailure::class.java) {
                client.bills(SamchullySession("synthetic", "PER"), "123", java.time.YearMonth.of(2026, 1), java.time.YearMonth.of(2026, 2))
            }
            assertEquals("bills", failure.stage)
            assertEquals("parse", failure.category)
        }
    }

    @Test fun rejectsInvalidMonthsAndMalformedNumbersInsteadOfRepairingThem() {
        for (month in listOf("202600", "202613")) {
            assertThrows(IllegalArgumentException::class.java) {
                SamchullyReadClient.parseBills(JSONObject("""{"E_TAB":[{"BILLING_PERIOD":"$month"}]}"""))
            }
        }
        for (value in listOf("12oops3", "1,2", "NaN", "1e3", "--1")) {
            assertThrows(IllegalArgumentException::class.java) {
                SamchullyReadClient.parseBills(JSONObject("""{"E_TAB":[{"BILLING_PERIOD":"202601","CONSUMPTION":"$value"}]}"""))
            }
        }
    }

    @Test fun rejectsDuplicateBillsAndAmbiguousSubmissionRows() {
        assertThrows(IllegalArgumentException::class.java) {
            SamchullyReadClient.parseBills(JSONObject("""{"E_TAB":[{"BILLING_PERIOD":"202601"},{"BILLING_PERIOD":"202601"}]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SamchullyReadClient.parseSelfReadState(JSONObject(), JSONObject(),
                JSONObject("""{"E_TAB":[{"E_YN":"N"},{"E_YN":"X"}]}"""))
        }
    }

    @Test fun parsesUserAndMultipleContractsWithoutUsingAddressAsIdentity() {
        val user = SamchullyReadClient.parseUser(JSONObject("""{
            "data":{"userName":"홍길동","birthDate":"19900101","phoneNumber":"010-0000-0000"}
        }"""))
        assertEquals("19900101", user.birthDate)
        val contracts = SamchullyReadClient.parseContracts(JSONObject("""{
            "E_TAB":[
                {"VKONT":"0012345678","VKBEZ_M":"우리 집","ADDR_D_M":"합성 주소","PHONE":"01000000000","LOGIKZW":"meter-a"},
                {"VKONT":"0098765432","VKBEZ":"다른 집","ADDR_J_M":"합성 지번","GERNR":"meter-b"}
            ]
        }"""))
        assertEquals(2, contracts.size)
        assertEquals("우리 집", contracts.first().label)
        assertFalse(contracts.first().key.contains("0012345678"))
    }

    @Test fun parsesAndSortsBillPeriodsWhileKeepingReportedUsageSeparate() {
        val bills = SamchullyReadClient.parseBills(JSONObject("""{
            "data":{"E_TAB":[
                {"BILLING_PERIOD":"202602","MR_DATE_FR":"20260110","MR_DATE_TO":"20260209","PR_ZWSTNDAB":"110","ZWSTNDAB":"125","CONSUMPTION":"14.7","BETRW_TOT":"18,000","LOGIKZW":"meter-a"},
                {"BILLING_PERIOD":"202601","MR_DATE_FR":"20251210","MR_DATE_TO":"20260109","PR_ZWSTNDAB":"100","ZWSTNDAB":"110","CONSUMPTION":"9.8","BETRW_TOT_T":"12,000","LOGIKZW":"meter-a"}
            ]}
        }"""))
        assertEquals(listOf("202601", "202602"), bills.map { it.billMonth })
        assertEquals("2025-12-10", bills.first().start)
        assertEquals(10.0, bills.first().currentReading!! - bills.first().previousReading!!, .00001)
        assertEquals(9.8, bills.first().reportedUsage!!, .00001)
        assertEquals(12000.0, bills.first().amount!!, .00001)
    }

    @Test fun rejectsMeterDecreaseUntilReplacementIsHandled() {
        assertThrows(IllegalArgumentException::class.java) {
            SamchullyReadClient.parseBills(JSONObject("""{
                "E_TAB":[{"BILLING_PERIOD":"202601","PR_ZWSTNDAB":"100","ZWSTNDAB":"5"}]
            }"""))
        }
    }

    @Test fun combinesPeriodTargetAndPreviousSubmissionState() {
        val state = SamchullyReadClient.parseSelfReadState(
            JSONObject("""{"ET_RESULT":[{"KKO_MR_SDATE":"20260901","KKO_MR_EDATE":"20260905"}]}"""),
            JSONObject("""{"data":{"E_TIDNR":"target-1","E_PRV_M_ZWSTAND":"120"}}"""),
            JSONObject("""{"E_TAB":[{"E_YN":"X","E_ZWSTAND":"132","E_ERDAT":"20260903"}]}"""),
        )
        assertEquals("2026-09-01", state.start)
        assertEquals("2026-09-05", state.end)
        assertEquals("target-1", state.targetId)
        assertEquals(120.0, state.previousReading!!, .00001)
        assertTrue(state.submitted == true)
        assertEquals(132.0, state.submittedReading!!, .00001)
    }
}
