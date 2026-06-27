package com.discordrolesync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin HTTP client for the backend. Uses the OkHttpClient and Gson provided by RuneLite.
 * All calls are async (OkHttp enqueue) so they never block the client thread.
 */
@Singleton
class DiscordRoleSyncApiClient
{
	private static final Logger log = LoggerFactory.getLogger(DiscordRoleSyncApiClient.class);
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	DiscordRoleSyncApiClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	interface RedeemCallback
	{
		void onResult(boolean ok, @javax.annotation.Nullable String reportToken, String message);
	}

	/** Redeem the access key, linking this account. On success the response carries a report token. */
	void redeem(String baseUrl, String key, long accountHash, String displayName, RedeemCallback cb)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("key", key);
		// Account hash is 64-bit; send as a string so the backend keeps full precision.
		body.addProperty("accountHash", Long.toString(accountHash));
		body.addProperty("displayName", displayName);

		final Request request = new Request.Builder()
			.url(base(baseUrl) + "/api/plugin/redeem")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				cb.onResult(false, null, "Network error: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					final String text = rb != null ? rb.string() : "";
					if (!response.isSuccessful())
					{
						cb.onResult(false, null, "Link failed (" + response.code() + "): " + text);
						return;
					}
					final JsonObject json = gson.fromJson(text, JsonObject.class);
					final String token = json != null && json.has("reportToken")
						&& !json.get("reportToken").isJsonNull()
						? json.get("reportToken").getAsString()
						: null;
					cb.onResult(true, token, "Linked " + displayName);
				}
				catch (Exception e)
				{
					cb.onResult(false, null, "Unexpected response: " + e.getMessage());
				}
			}
		});
	}

	/** Push the clan roster. Authenticated by the per-account report token from {@link #redeem}. */
	void reportClan(String baseUrl, String reportToken, String clanName, List<ClanReporter.Member> members)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("reportToken", reportToken);
		body.addProperty("clanName", clanName);

		final JsonArray arr = new JsonArray();
		for (ClanReporter.Member m : members)
		{
			final JsonObject o = new JsonObject();
			o.addProperty("name", m.name);
			o.addProperty("rank", m.rank);
			if (m.title != null)
			{
				o.addProperty("title", m.title);
			}
			arr.add(o);
		}
		body.add("members", arr);

		final Request request = new Request.Builder()
			.url(base(baseUrl) + "/api/plugin/clan")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Clan report failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					if (!response.isSuccessful())
					{
						log.debug("Clan report returned {}", response.code());
					}
				}
			}
		});
	}

	private static String base(String url)
	{
		return url.replaceAll("/+$", "");
	}
}
