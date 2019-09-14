package me.NoChance.PvPManager.Libraries.Metrics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.NoChance.PvPManager.Settings.Settings;

/**
 * bStats collects some data for plugin authors.
 * <p>
 * Check out https://bStats.org/ to learn more about bStats!
 */
@SuppressWarnings({ "unused" })
public class Metrics {

	static {
		// You can use the property to disable the check in your test environment
		if (System.getProperty("bstats.relocatecheck") == null || !System.getProperty("bstats.relocatecheck").equals("false")) {
			// Maven's Relocate is clever and changes strings, too. So we have to use this little "trick" ... :D
			final String defaultPackage = new String(new byte[] { 'o', 'r', 'g', '.', 'b', 's', 't', 'a', 't', 's', '.', 'b', 'u', 'k', 'k', 'i', 't' });
			final String examplePackage = new String(new byte[] { 'y', 'o', 'u', 'r', '.', 'p', 'a', 'c', 'k', 'a', 'g', 'e' });
			// We want to make sure nobody just copy & pastes the example and use the wrong package names
			if (Metrics.class.getPackage().getName().equals(defaultPackage) || Metrics.class.getPackage().getName().equals(examplePackage))
				throw new IllegalStateException("bStats Metrics class has not been relocated correctly!");
		}
	}

	// The version of this bStats class
	public static final int B_STATS_VERSION = 1;

	// The url to which the data is sent
	private static final String URL = "https://bStats.org/submitData/bukkit";

	// Is bStats enabled on this server?
	private final boolean enabled;

	// Should failed requests be logged?
	private static boolean logFailedRequests;

	// Should the sent data be logged?
	private static boolean logSentData;

	// Should the response text be logged?
	private static boolean logResponseStatusText;

	// The uuid of the server
	private static String serverUUID;

	// The plugin
	private final Plugin plugin;

	// A list with all custom charts
	private final List<CustomChart> charts = new ArrayList<>();

	/**
	 * Class constructor.
	 *
	 * @param plugin The plugin which stats should be submitted.
	 */
	public Metrics(final Plugin plugin) {
		if (plugin == null)
			throw new IllegalArgumentException("Plugin cannot be null!");
		this.plugin = plugin;

		// Get the config file
		final File bStatsFolder = new File(plugin.getDataFolder().getParentFile(), "bStats");
		final File configFile = new File(bStatsFolder, "config.yml");
		final YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

		// Check if the config file exists
		if (!config.isSet("serverUuid")) {

			// Add default values
			config.addDefault("enabled", true);
			// Every server gets it's unique random id.
			config.addDefault("serverUuid", UUID.randomUUID().toString());
			// Should failed request be logged?
			config.addDefault("logFailedRequests", false);
			// Should the sent data be logged?
			config.addDefault("logSentData", false);
			// Should the response text be logged?
			config.addDefault("logResponseStatusText", false);

			// Inform the server owners about bStats
			config.options().header("bStats collects some data for plugin authors like how many servers are using their plugins.\n" + "To honor their work, you should not disable it.\n" + "This has nearly no effect on the server performance!\n" + "Check out https://bStats.org/ to learn more :)")
			        .copyDefaults(true);
			try {
				config.save(configFile);
			} catch (final IOException ignored) {
			}
		}

		// Load the data
		enabled = !Settings.isOptOutMetrics();
		serverUUID = config.getString("serverUuid");
		logFailedRequests = config.getBoolean("logFailedRequests", false);
		logSentData = config.getBoolean("logSentData", false);
		logResponseStatusText = config.getBoolean("logResponseStatusText", false);

		if (enabled) {
			boolean found = false;
			// Search for all other bStats Metrics classes to see if we are the first one
			for (final Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
				try {
					service.getField("B_STATS_VERSION"); // Our identifier :)
					found = true; // We aren't the first
					break;
				} catch (final NoSuchFieldException ignored) {
				}
			}
			// Register our service
			Bukkit.getServicesManager().register(Metrics.class, this, plugin, ServicePriority.Normal);
			if (!found) {
				// We are the first!
				startSubmitting();
			}
		}
	}

	/**
	 * Checks if bStats is enabled.
	 *
	 * @return Whether bStats is enabled or not.
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Adds a custom chart.
	 *
	 * @param chart The chart to add.
	 */
	public void addCustomChart(final CustomChart chart) {
		if (chart == null)
			throw new IllegalArgumentException("Chart cannot be null!");
		charts.add(chart);
	}

	/**
	 * Starts the Scheduler which submits our data every 30 minutes.
	 */
	private void startSubmitting() {
		final Timer timer = new Timer(true); // We use a timer cause the Bukkit scheduler is affected by server lags
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (!plugin.isEnabled()) { // Plugin was disabled
					timer.cancel();
					return;
				}
				// Nevertheless we want our code to run in the Bukkit main thread, so we have to use the Bukkit scheduler
				// Don't be afraid! The connection to the bStats server is still async, only the stats collection is sync ;)
				Bukkit.getScheduler().runTask(plugin, () -> submitData());
			}
		}, 1000 * 60 * 5, 1000 * 60 * 30);
		// Submit the data every 30 minutes, first time after 5 minutes to give other plugins enough time to start
		// WARNING: Changing the frequency has no effect but your plugin WILL be blocked/deleted!
		// WARNING: Just don't do it!
	}

	/**
	 * Gets the plugin specific data. This method is called using Reflection.
	 *
	 * @return The plugin specific data.
	 */
	public JsonObject getPluginData() {
		final JsonObject data = new JsonObject();

		final String pluginName = plugin.getDescription().getName();
		final String pluginVersion = plugin.getDescription().getVersion();

		data.addProperty("pluginName", pluginName); // Append the name of the plugin
		data.addProperty("pluginVersion", pluginVersion); // Append the version of the plugin
		final JsonArray customCharts = new JsonArray();
		for (final CustomChart customChart : charts) {
			// Add the data of the custom charts
			final JsonObject chart = customChart.getRequestJsonObject();
			if (chart == null) { // If the chart is null, we skip it
				continue;
			}
			customCharts.add(chart);
		}
		data.add("customCharts", customCharts);

		return data;
	}

	/**
	 * Gets the server specific data.
	 *
	 * @return The server specific data.
	 */
	private JsonObject getServerData() {
		// Minecraft specific data
		int playerAmount;
		try {
			// Around MC 1.8 the return type was changed to a collection from an array,
			// This fixes java.lang.NoSuchMethodError: org.bukkit.Bukkit.getOnlinePlayers()Ljava/util/Collection;
			final Method onlinePlayersMethod = Class.forName("org.bukkit.Server").getMethod("getOnlinePlayers");
			playerAmount = onlinePlayersMethod.getReturnType().equals(Collection.class) ? ((Collection<?>) onlinePlayersMethod.invoke(Bukkit.getServer())).size() : ((Player[]) onlinePlayersMethod.invoke(Bukkit.getServer())).length;
		} catch (final Exception e) {
			playerAmount = Bukkit.getOnlinePlayers().size(); // Just use the new method if the Reflection failed
		}
		final int onlineMode = Bukkit.getOnlineMode() ? 1 : 0;
		final String bukkitVersion = Bukkit.getVersion();
		final String bukkitName = Bukkit.getName();

		// OS/Java specific data
		final String javaVersion = System.getProperty("java.version");
		final String osName = System.getProperty("os.name");
		final String osArch = System.getProperty("os.arch");
		final String osVersion = System.getProperty("os.version");
		final int coreCount = Runtime.getRuntime().availableProcessors();

		final JsonObject data = new JsonObject();

		data.addProperty("serverUUID", serverUUID);

		data.addProperty("playerAmount", playerAmount);
		data.addProperty("onlineMode", onlineMode);
		data.addProperty("bukkitVersion", bukkitVersion);
		data.addProperty("bukkitName", bukkitName);

		data.addProperty("javaVersion", javaVersion);
		data.addProperty("osName", osName);
		data.addProperty("osArch", osArch);
		data.addProperty("osVersion", osVersion);
		data.addProperty("coreCount", coreCount);

		return data;
	}

	/**
	 * Collects the data and sends it afterwards.
	 */
	private void submitData() {
		final JsonObject data = getServerData();

		final JsonArray pluginData = new JsonArray();
		// Search for all other bStats Metrics classes to get their plugin data
		for (final Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
			try {
				service.getField("B_STATS_VERSION"); // Our identifier :)

				for (final RegisteredServiceProvider<?> provider : Bukkit.getServicesManager().getRegistrations(service)) {
					try {
						final Object plugin = provider.getService().getMethod("getPluginData").invoke(provider.getProvider());
						if (plugin instanceof JsonObject) {
							pluginData.add((JsonObject) plugin);
						} else { // old bstats version compatibility
							try {
								final Class<?> jsonObjectJsonSimple = Class.forName("org.json.simple.JSONObject");
								if (plugin.getClass().isAssignableFrom(jsonObjectJsonSimple)) {
									final Method jsonStringGetter = jsonObjectJsonSimple.getDeclaredMethod("toJSONString");
									jsonStringGetter.setAccessible(true);
									final String jsonString = (String) jsonStringGetter.invoke(plugin);
									final JsonObject object = new JsonParser().parse(jsonString).getAsJsonObject();
									pluginData.add(object);
								}
							} catch (final ClassNotFoundException e) {
								// minecraft version 1.14+
								if (logFailedRequests) {
									this.plugin.getLogger().log(Level.SEVERE, "Encountered unexpected exception", e);
								}
								continue; // continue looping since we cannot do any other thing.
							}
						}
					} catch (NullPointerException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
					}
				}
			} catch (final NoSuchFieldException ignored) {
			}
		}

		data.add("plugins", pluginData);

		// Create a new thread for the connection to the bStats server
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// Send the data
					sendData(plugin, data);
				} catch (final Exception e) {
					// Something went wrong! :(
					if (logFailedRequests) {
						plugin.getLogger().log(Level.WARNING, "Could not submit plugin stats of " + plugin.getName(), e);
					}
				}
			}
		}).start();
	}

	/**
	 * Sends the data to the bStats server.
	 *
	 * @param plugin Any plugin. It's just used to get a logger instance.
	 * @param data The data to send.
	 * @throws Exception If the request failed.
	 */
	private static void sendData(final Plugin plugin, final JsonObject data) throws Exception {
		if (data == null)
			throw new IllegalArgumentException("Data cannot be null!");
		if (Bukkit.isPrimaryThread())
			throw new IllegalAccessException("This method must not be called from the main thread!");
		if (logSentData) {
			plugin.getLogger().info("Sending data to bStats: " + data.toString());
		}
		final HttpsURLConnection connection = (HttpsURLConnection) new URL(URL).openConnection();

		// Compress the data to save bandwidth
		final byte[] compressedData = compress(data.toString());

		// Add headers
		connection.setRequestMethod("POST");
		connection.addRequestProperty("Accept", "application/json");
		connection.addRequestProperty("Connection", "close");
		connection.addRequestProperty("Content-Encoding", "gzip"); // We gzip our request
		connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
		connection.setRequestProperty("Content-Type", "application/json"); // We send our data in JSON format
		connection.setRequestProperty("User-Agent", "MC-Server/" + B_STATS_VERSION);

		// Send data
		connection.setDoOutput(true);
		final DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
		outputStream.write(compressedData);
		outputStream.flush();
		outputStream.close();

		final InputStream inputStream = connection.getInputStream();
		final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

		final StringBuilder builder = new StringBuilder();
		String line;
		while ((line = bufferedReader.readLine()) != null) {
			builder.append(line);
		}
		bufferedReader.close();
		if (logResponseStatusText) {
			plugin.getLogger().info("Sent data to bStats and received response: " + builder.toString());
		}
	}

	/**
	 * Gzips the given String.
	 *
	 * @param str The string to gzip.
	 * @return The gzipped String.
	 * @throws IOException If the compression failed.
	 */
	private static byte[] compress(final String str) throws IOException {
		if (str == null)
			return null;
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
		gzip.write(str.getBytes(StandardCharsets.UTF_8));
		gzip.close();
		return outputStream.toByteArray();
	}

	/**
	 * Represents a custom chart.
	 */
	public static abstract class CustomChart {

		// The id of the chart
		final String chartId;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 */
		CustomChart(final String chartId) {
			if (chartId == null || chartId.isEmpty())
				throw new IllegalArgumentException("ChartId cannot be null or empty!");
			this.chartId = chartId;
		}

		private JsonObject getRequestJsonObject() {
			final JsonObject chart = new JsonObject();
			chart.addProperty("chartId", chartId);
			try {
				final JsonObject data = getChartData();
				if (data == null)
					// If the data is null we don't send the chart.
					return null;
				chart.add("data", data);
			} catch (final Throwable t) {
				if (logFailedRequests) {
					Bukkit.getLogger().log(Level.WARNING, "Failed to get data for custom chart with id " + chartId, t);
				}
				return null;
			}
			return chart;
		}

		protected abstract JsonObject getChartData() throws Exception;

	}

	/**
	 * Represents a custom simple pie.
	 */
	public static class SimplePie extends CustomChart {

		private final Callable<String> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public SimplePie(final String chartId, final Callable<String> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final String value = callable.call();
			if (value == null || value.isEmpty())
				// Null = skip the chart
				return null;
			data.addProperty("value", value);
			return data;
		}
	}

	/**
	 * Represents a custom advanced pie.
	 */
	public static class AdvancedPie extends CustomChart {

		private final Callable<Map<String, Integer>> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public AdvancedPie(final String chartId, final Callable<Map<String, Integer>> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final JsonObject values = new JsonObject();
			final Map<String, Integer> map = callable.call();
			if (map == null || map.isEmpty())
				// Null = skip the chart
				return null;
			boolean allSkipped = true;
			for (final Map.Entry<String, Integer> entry : map.entrySet()) {
				if (entry.getValue() == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				values.addProperty(entry.getKey(), entry.getValue());
			}
			if (allSkipped)
				// Null = skip the chart
				return null;
			data.add("values", values);
			return data;
		}
	}

	/**
	 * Represents a custom drilldown pie.
	 */
	public static class DrilldownPie extends CustomChart {

		private final Callable<Map<String, Map<String, Integer>>> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public DrilldownPie(final String chartId, final Callable<Map<String, Map<String, Integer>>> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		public JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final JsonObject values = new JsonObject();
			final Map<String, Map<String, Integer>> map = callable.call();
			if (map == null || map.isEmpty())
				// Null = skip the chart
				return null;
			boolean reallyAllSkipped = true;
			for (final Map.Entry<String, Map<String, Integer>> entryValues : map.entrySet()) {
				final JsonObject value = new JsonObject();
				boolean allSkipped = true;
				for (final Map.Entry<String, Integer> valueEntry : map.get(entryValues.getKey()).entrySet()) {
					value.addProperty(valueEntry.getKey(), valueEntry.getValue());
					allSkipped = false;
				}
				if (!allSkipped) {
					reallyAllSkipped = false;
					values.add(entryValues.getKey(), value);
				}
			}
			if (reallyAllSkipped)
				// Null = skip the chart
				return null;
			data.add("values", values);
			return data;
		}
	}

	/**
	 * Represents a custom single line chart.
	 */
	public static class SingleLineChart extends CustomChart {

		private final Callable<Integer> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public SingleLineChart(final String chartId, final Callable<Integer> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final int value = callable.call();
			if (value == 0)
				// Null = skip the chart
				return null;
			data.addProperty("value", value);
			return data;
		}

	}

	/**
	 * Represents a custom multi line chart.
	 */
	public static class MultiLineChart extends CustomChart {

		private final Callable<Map<String, Integer>> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public MultiLineChart(final String chartId, final Callable<Map<String, Integer>> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final JsonObject values = new JsonObject();
			final Map<String, Integer> map = callable.call();
			if (map == null || map.isEmpty())
				// Null = skip the chart
				return null;
			boolean allSkipped = true;
			for (final Map.Entry<String, Integer> entry : map.entrySet()) {
				if (entry.getValue() == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				values.addProperty(entry.getKey(), entry.getValue());
			}
			if (allSkipped)
				// Null = skip the chart
				return null;
			data.add("values", values);
			return data;
		}

	}

	/**
	 * Represents a custom simple bar chart.
	 */
	public static class SimpleBarChart extends CustomChart {

		private final Callable<Map<String, Integer>> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public SimpleBarChart(final String chartId, final Callable<Map<String, Integer>> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final JsonObject values = new JsonObject();
			final Map<String, Integer> map = callable.call();
			if (map == null || map.isEmpty())
				// Null = skip the chart
				return null;
			for (final Map.Entry<String, Integer> entry : map.entrySet()) {
				final JsonArray categoryValues = new JsonArray();
				categoryValues.add(entry.getValue());
				values.add(entry.getKey(), categoryValues);
			}
			data.add("values", values);
			return data;
		}

	}

	/**
	 * Represents a custom advanced bar chart.
	 */
	public static class AdvancedBarChart extends CustomChart {

		private final Callable<Map<String, int[]>> callable;

		/**
		 * Class constructor.
		 *
		 * @param chartId The id of the chart.
		 * @param callable The callable which is used to request the chart data.
		 */
		public AdvancedBarChart(final String chartId, final Callable<Map<String, int[]>> callable) {
			super(chartId);
			this.callable = callable;
		}

		@Override
		protected JsonObject getChartData() throws Exception {
			final JsonObject data = new JsonObject();
			final JsonObject values = new JsonObject();
			final Map<String, int[]> map = callable.call();
			if (map == null || map.isEmpty())
				// Null = skip the chart
				return null;
			boolean allSkipped = true;
			for (final Map.Entry<String, int[]> entry : map.entrySet()) {
				if (entry.getValue().length == 0) {
					continue; // Skip this invalid
				}
				allSkipped = false;
				final JsonArray categoryValues = new JsonArray();
				for (final int categoryValue : entry.getValue()) {
					categoryValues.add(categoryValue);
				}
				values.add(entry.getKey(), categoryValues);
			}
			if (allSkipped)
				// Null = skip the chart
				return null;
			data.add("values", values);
			return data;
		}
	}

}
