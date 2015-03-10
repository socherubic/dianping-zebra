package com.dianping.zebra.mysql.ha;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import org.unidal.helper.Files;
import org.unidal.helper.Urls;

import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DalHaHandler {

	private static final String LION_SET_CONFIG_URL = "http://lionapi.dp:8080/config2/set?env=%s&id=%s&key=%s&value=%s";

	private static final String DEFAULT_ENV = "product";

	private static final String ID = "2";

	private static final String UP = "true";

	private static final String DOWN = "false";

	public static boolean markdown(String dsId) {
		String key = String.format("ds.%s.jdbc.active", dsId);

		String value = getConfigFromZk(key);

		if (value == null || value.length() == 0 || value.equalsIgnoreCase(UP)) {
			return setConfig(DEFAULT_ENV, key, DOWN);
		} else {
			return true;
		}
	}

	public static boolean markup(String dsId) {
		String key = String.format("ds.%s.jdbc.active", dsId);

		String value = getConfigFromZk(key);

		if (value == null || value.length() == 0 || value.equalsIgnoreCase(DOWN)) {
			return setConfig(DEFAULT_ENV, key, UP);
		} else {
			return true;
		}
	}

	private static String sendGet(String url) {
		InputStream inputStream;
		try {
			inputStream = Urls.forIO().connectTimeout(1000).readTimeout(5000).openStream(url);
			return Files.forIO().readFrom(inputStream, "utf-8");
		} catch (IOException e) {
			return "";
		}
	}

	private static String getConfigFromZk(String key) {
		try {
			return ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty(key);
		} catch (LionException e) {
			return null;
		}
	}

	private static boolean setConfig(String env, String key, String value) {
		String result = null;
		try {
			result = sendGet(String.format(LION_SET_CONFIG_URL, env, ID, key, URLEncoder.encode(value, "utf-8")));
		} catch (UnsupportedEncodingException e) {
			return false;
		}

		if (result != null && result.length() > 0) {
			JsonParser parser = new JsonParser();
			JsonObject obj = parser.parse(result).getAsJsonObject();

			if (obj.get("status").getAsString().equals("success")) {
				return true;
			} else {
				return false;
			}
		} else {
			return false;
		}
	}
}
