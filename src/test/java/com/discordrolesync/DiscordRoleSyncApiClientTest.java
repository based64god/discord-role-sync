package com.discordrolesync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link DiscordRoleSyncApiClient} end-to-end via a capturing OkHttp interceptor: it records
 * the outgoing request and returns a canned response, so we assert on the real URL/method/JSON the
 * client produces and how it interprets responses — no network and no extra test dependencies.
 */
public class DiscordRoleSyncApiClientTest
{
	private static final MediaType JSON = MediaType.get("application/json");

	/** Records the outgoing request and short-circuits with a fixed response. */
	private static final class StubInterceptor implements Interceptor
	{
		final AtomicReference<Request> request = new AtomicReference<>();
		final CountDownLatch served = new CountDownLatch(1);
		private final int code;
		private final String body;

		StubInterceptor(int code, String body)
		{
			this.code = code;
			this.body = body;
		}

		@Override
		public Response intercept(Chain chain) throws IOException
		{
			request.set(chain.request());
			served.countDown();
			return new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message("stub")
				.body(ResponseBody.create(JSON, body))
				.build();
		}
	}

	private static OkHttpClient clientWith(Interceptor i)
	{
		return new OkHttpClient.Builder().addInterceptor(i).build();
	}

	private static String requestBody(Request request) throws IOException
	{
		Buffer buffer = new Buffer();
		request.body().writeTo(buffer);
		return buffer.readUtf8();
	}

	@SuppressWarnings("deprecation")
	private static JsonObject json(String s)
	{
		return new JsonParser().parse(s).getAsJsonObject();
	}

	/** Captured result of a redeem() callback. */
	private static final class Redeemed
	{
		boolean ok;
		String token;
		String message;
		final CountDownLatch latch = new CountDownLatch(1);
	}

	private static Redeemed redeem(OkHttpClient http, String baseUrl) throws InterruptedException
	{
		Redeemed r = new Redeemed();
		new DiscordRoleSyncApiClient(http, new Gson()).redeem(baseUrl, "mykey", 123456789L, "Zezima",
			(ok, token, message) ->
			{
				r.ok = ok;
				r.token = token;
				r.message = message;
				r.latch.countDown();
			});
		assertTrue("redeem callback was not invoked", r.latch.await(5, TimeUnit.SECONDS));
		return r;
	}

	@Test
	public void redeemSuccessReturnsTokenAndPostsExpectedBody() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(200, "{\"reportToken\":\"tok-abc\"}");
		Redeemed r = redeem(clientWith(stub), "https://api.test");

		assertTrue(r.ok);
		assertEquals("tok-abc", r.token);

		Request req = stub.request.get();
		assertEquals("POST", req.method());
		assertEquals("https://api.test/api/plugin/redeem", req.url().toString());

		JsonObject sent = json(requestBody(req));
		assertEquals("mykey", sent.get("key").getAsString());
		assertEquals("Zezima", sent.get("displayName").getAsString());
		// The 64-bit account hash must travel as a STRING to avoid precision loss in JSON numbers.
		assertTrue("accountHash must be a JSON string", sent.get("accountHash").getAsJsonPrimitive().isString());
		assertEquals("123456789", sent.get("accountHash").getAsString());
	}

	@Test
	public void redeemStripsTrailingSlashesFromBaseUrl() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(200, "{\"reportToken\":\"t\"}");
		redeem(clientWith(stub), "https://api.test///");
		assertEquals("https://api.test/api/plugin/redeem", stub.request.get().url().toString());
	}

	@Test
	public void redeemNonSuccessReturnsFailureWithStatusCode() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(403, "forbidden");
		Redeemed r = redeem(clientWith(stub), "https://api.test");
		assertFalse(r.ok);
		assertNull(r.token);
		assertTrue("message should mention the status", r.message.contains("403"));
	}

	@Test
	public void redeemSuccessWithoutTokenIsTreatedAsFailure() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(200, "{}");
		Redeemed r = redeem(clientWith(stub), "https://api.test");
		assertFalse("a 2xx with no token must not be reported as success", r.ok);
		assertNull(r.token);
	}

	@Test
	public void redeemInvalidUrlReportsFailureWithoutSendingARequest() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(200, "{\"reportToken\":\"t\"}");
		Redeemed r = redeem(clientWith(stub), "not-a-url");
		assertFalse(r.ok);
		assertTrue(r.message.toLowerCase().contains("invalid backend url"));
		assertNull("no HTTP request should have been attempted", stub.request.get());
	}

	@Test
	public void reportClanPostsRosterJsonAndOmitsNullTitles() throws Exception
	{
		StubInterceptor stub = new StubInterceptor(200, "");
		new DiscordRoleSyncApiClient(clientWith(stub), new Gson()).reportClan(
			"https://api.test/", "tok", "My Clan", Arrays.asList(
				new ClanReporter.Member("Zezima", 126, "Owner"),
				new ClanReporter.Member("Bob", 5, null)));

		assertTrue("clan report was not sent", stub.served.await(5, TimeUnit.SECONDS));

		Request req = stub.request.get();
		assertEquals("POST", req.method());
		assertEquals("https://api.test/api/plugin/clan", req.url().toString());

		JsonObject sent = json(requestBody(req));
		assertEquals("tok", sent.get("reportToken").getAsString());
		assertEquals("My Clan", sent.get("clanName").getAsString());

		JsonArray members = sent.getAsJsonArray("members");
		assertEquals(2, members.size());

		JsonObject first = members.get(0).getAsJsonObject();
		assertEquals("Zezima", first.get("name").getAsString());
		assertEquals(126, first.get("rank").getAsInt());
		assertEquals("Owner", first.get("title").getAsString());

		JsonObject second = members.get(1).getAsJsonObject();
		assertEquals("Bob", second.get("name").getAsString());
		assertEquals(5, second.get("rank").getAsInt());
		assertFalse("title should be omitted when null", second.has("title"));
	}

	@Test
	public void baseStripsTrailingSlashesAndIsNullSafe()
	{
		assertEquals("", DiscordRoleSyncApiClient.base(null));
		assertEquals("https://a.test", DiscordRoleSyncApiClient.base("https://a.test"));
		assertEquals("https://a.test", DiscordRoleSyncApiClient.base("https://a.test/"));
		assertEquals("https://a.test", DiscordRoleSyncApiClient.base("https://a.test///"));
	}
}
