package com.steven10172.corptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("corpeventtracker")
public interface CorpEventTrackerConfig extends Config {
	@ConfigItem(
		keyName = "showInProgress",
		name = "Show In Progress Kills",
		description = "Whether to show kills currently in progress"
	)
	default boolean showInProgress() {
		return false;
	}
}
