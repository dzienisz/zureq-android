package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.HttpResponse
import com.dzienisz.zureqshopper.auth.AuthHttp
import com.dzienisz.zureqshopper.auth.ZureqAuth
import com.dzienisz.zureqshopper.data.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppModelSignInTest {
    private class FailOnceHttp : AuthHttp {
        var calls = 0
        override suspend fun post(url: String, contentType: String, body: String): HttpResponse {
            calls++
            if (calls == 1) throw IOException("dns down")
            return HttpResponse(200, """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""")
        }
    }

    @Test
    fun finishSignInRetriesTokenExchangeAfterNetworkError() = runTest {
        val secrets = InMemorySecretStore()
        val authHttp = FailOnceHttp()
        val request = ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.APP_REDIRECT_URI)
        secrets.write(ZureqAuth.encodeRequest(request), AppModel.PENDING_SIGN_IN_ACCOUNT)
        val model = AppModel(secrets, FakePrefs(), FakeTransport(ArrayDeque()), authHttp)

        assertTrue(model.signIn.value is SignIn.Waiting)
        model.finishSignIn("com.dzienisz.zureqshopper:/oauth?code=abc&state=s")

        assertEquals(2, authHttp.calls)
        assertEquals(SignIn.Idle, model.signIn.value)
        assertTrue(model.auth.hasSession)
    }
}
