package de.doridian.logstashlogger.actions;

import de.doridian.logstashlogger.config.Configuration;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public abstract class BaseJSONAction {
	private final Date timestamp = new Date();
	private final Player user;
	private final String action;

	private static final String HOSTNAME;
	private static final DateFormat JSON_DATE_FORMAT;

	static {
		String _hostname;
		try {
			_hostname = InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			e.printStackTrace();
			_hostname = "N/A";
		}
		HOSTNAME = _hostname;

		TimeZone tz = TimeZone.getTimeZone("UTC");
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		df.setTimeZone(tz);
		JSON_DATE_FORMAT = df;
	}

	public BaseJSONAction(Player user, String action) {
		this.user = user;
		this.action = action;
	}

	public JSONObject toJSONObject() {
		final JSONObject thisBlockChange = new JSONObject();

		thisBlockChange.put("@version", "1");
		thisBlockChange.put("@timestamp", JSON_DATE_FORMAT.format(timestamp));
		thisBlockChange.put("type", "minecraft_action");

		thisBlockChange.put("username", user.getName());
		thisBlockChange.put("useruuid", user.getUniqueId().toString());
		thisBlockChange.put("action", action);

		thisBlockChange.put("host", HOSTNAME);
		thisBlockChange.put("server", Configuration.getValue("server-name", "N/A"));

		return thisBlockChange;
	}

}
